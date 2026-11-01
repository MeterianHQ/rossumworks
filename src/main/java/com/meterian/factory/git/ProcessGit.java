package com.meterian.factory.git;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

public final class ProcessGit implements Git {

    private static final String FACTORY_FOLDER_PATTERN = ".factory/";

    private static final String FACTORY_BRANCH_PREFIX = "factory/";
    private static final List<String> FIXED_IDENTITY = List.of(
            "-c", "user.name=claude-ai-factory",
            "-c", "user.email=claude-ai-factory@users.noreply.github.com");
    private static final Map<String, String> ISOLATION = Map.of(
            "GIT_CONFIG_GLOBAL", "/dev/null",
            "GIT_CONFIG_NOSYSTEM", "1",
            "GIT_TERMINAL_PROMPT", "0");

    private final Map<String, String> extraEnvironment;

    public ProcessGit() {
        this(Map.of());
    }

    /** The isolation entries always win over the extra entries. */
    ProcessGit(Map<String, String> extraEnvironment) {
        this.extraEnvironment = Map.copyOf(extraEnvironment);
    }

    private record Result(int exitCode, String output, String errorOutput) {
    }

    @Override
    public void fetch(Path repository, String remote, String branch) {
        run(repository, "fetch", remote, branch);
    }

    @Override
    public void addWorktree(Path clone, Path worktree, String branch, String startPoint) {
        run(clone, "worktree", "add", "-B", branch, worktree.toString(), startPoint);
    }

    @Override
    public void removeWorktree(Path clone, Path worktree) {
        run(clone, "worktree", "remove", "--force", worktree.toString());
    }

    @Override
    public void pruneWorktrees(Path clone) {
        run(clone, "worktree", "prune");
    }

    @Override
    public String treeHash(Path worktree) {
        return run(worktree, "rev-parse", "HEAD^{tree}").trim();
    }

    @Override
    public String patchId(Path worktree, String base) {
        String mergeBase = run(worktree, "merge-base", base, "HEAD").trim();
        String difference = run(worktree, "diff", "--binary", "--full-index", "--no-textconv",
                "--no-ext-diff", "--no-color", mergeBase + "..HEAD");
        return difference.isEmpty() ? "" : hashWithoutPositions(difference);
    }

    /** Git patch-id ignores whitespace, so this hash keeps it. It drops the blob hashes and the line numbers. */
    private static String hashWithoutPositions(String difference) {
        StringBuilder stable = new StringBuilder();
        for (String line : difference.split("\n", -1)) {
            if (line.startsWith("index ")) {
                continue;
            }
            stable.append(line.startsWith("@@") ? "@@" : line).append('\n');
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(stable.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new GitException("The SHA-256 algorithm is missing", exception);
        }
    }

    @Override
    public String treeHashOf(Path worktree, String commit) {
        return run(worktree, "rev-parse", commit + "^{tree}").trim();
    }

    @Override
    public String headCommit(Path worktree) {
        return run(worktree, "rev-parse", "HEAD").trim();
    }

    @Override
    public boolean hasUncommittedChanges(Path worktree) {
        return !run(worktree, "status", "--porcelain").isBlank();
    }

    @Override
    public void commitAll(Path worktree, String message) {
        run(worktree, "add", "-A");
        run(worktree, withIdentity("commit", "-q", "-m", message));
    }

    @Override
    public RebaseOutcome rebase(Path worktree, String upstream) {
        if (containsCommit(worktree, upstream)) {
            return RebaseOutcome.UP_TO_DATE;
        }
        String headBefore = run(worktree, "rev-parse", "HEAD").trim();
        Result result = execute(worktree, null, withIdentity("rebase", upstream));
        if (result.exitCode() == 0) {
            String headAfter = run(worktree, "rev-parse", "HEAD").trim();
            return headBefore.equals(headAfter) ? RebaseOutcome.UP_TO_DATE : RebaseOutcome.REBASED;
        }
        if (isRebaseInProgress(worktree)) {
            run(worktree, "rebase", "--abort");
            return RebaseOutcome.CONFLICT;
        }
        throw failure(List.of("rebase", upstream), result);
    }

    @Override
    public List<String> mergeWithoutCommit(Path worktree, String upstream) {
        Result result = execute(worktree, null, withIdentity("merge", "--no-commit", "--no-ff", upstream));
        List<String> conflictedPaths = run(worktree, "diff", "--name-only", "--diff-filter=U").lines()
                .filter(line -> !line.isBlank())
                .toList();
        if (result.exitCode() != 0 && conflictedPaths.isEmpty()) {
            throw failure(List.of("merge", "--no-commit", "--no-ff", upstream), result);
        }
        return conflictedPaths;
    }

    /** A merge commit can contain the upstream. A rebase then replays old commits, so it must not run. */
    private boolean containsCommit(Path worktree, String commit) {
        return execute(worktree, null, "merge-base", "--is-ancestor", commit, "HEAD").exitCode() == 0;
    }

    @Override
    public void push(Path worktree, String remote, String branch) {
        pushCommit(worktree, remote, "HEAD", branch);
    }

    @Override
    public void pushCommit(Path worktree, String remote, String commit, String branch) {
        if (!branch.startsWith(FACTORY_BRANCH_PREFIX)) {
            throw new GitException("git push refused: the branch " + branch + " does not start with "
                    + FACTORY_BRANCH_PREFIX);
        }
        run(worktree, "push", "--force", remote, commit + ":refs/heads/" + branch);
    }

    @Override
    public void excludeFactoryFolder(Path worktree) {
        Path excludeFile = worktree.resolve(run(worktree, "rev-parse", "--git-path", "info/exclude").trim());
        try {
            Files.createDirectories(excludeFile.getParent());
            String content = Files.exists(excludeFile) ? Files.readString(excludeFile) : "";
            if (!content.lines().anyMatch(FACTORY_FOLDER_PATTERN::equals)) {
                Files.writeString(excludeFile, content + FACTORY_FOLDER_PATTERN + "\n");
            }
        } catch (IOException exception) {
            throw new GitException("Cannot write the exclude file " + excludeFile, exception);
        }
    }

    @Override
    public void setRemote(Path repository, String name, String url) {
        boolean exists = execute(repository, null, "remote", "get-url", name).exitCode() == 0;
        run(repository, "remote", exists ? "set-url" : "add", name, url);
    }

    String run(Path directory, String... arguments) {
        return runWithInput(directory, null, arguments);
    }

    private String runWithInput(Path directory, String input, String... arguments) {
        Result result = execute(directory, input, arguments);
        if (result.exitCode() != 0) {
            throw failure(List.of(arguments), result);
        }
        return result.output();
    }

    private boolean isRebaseInProgress(Path worktree) {
        return isGitPathPresent(worktree, "rebase-merge") || isGitPathPresent(worktree, "rebase-apply");
    }

    private boolean isGitPathPresent(Path worktree, String name) {
        return Files.exists(worktree.resolve(run(worktree, "rev-parse", "--git-path", name).trim()));
    }

    private static String[] withIdentity(String... arguments) {
        List<String> all = new ArrayList<>(FIXED_IDENTITY);
        all.addAll(List.of(arguments));
        return all.toArray(String[]::new);
    }

    private static GitException failure(List<String> arguments, Result result) {
        return new GitException("git " + String.join(" ", arguments) + " failed with exit code "
                + result.exitCode() + ": " + result.errorOutput().trim());
    }

    private Result execute(Path directory, String input, String... arguments) {
        List<String> command = new ArrayList<>(List.of("git"));
        command.addAll(List.of(arguments));
        Path errorFile = null;
        try {
            errorFile = Files.createTempFile("factory-git", ".err");
            ProcessBuilder builder = new ProcessBuilder(command)
                    .directory(directory.toFile())
                    .redirectError(errorFile.toFile());
            builder.environment().putAll(extraEnvironment);
            builder.environment().putAll(ISOLATION);
            Process process = builder.start();
            try (var stdin = process.getOutputStream()) {
                if (input != null) {
                    stdin.write(input.getBytes(StandardCharsets.UTF_8));
                }
            }
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int exitCode = process.waitFor();
            return new Result(exitCode, output, Files.readString(errorFile));
        } catch (IOException exception) {
            throw new GitException("git " + String.join(" ", arguments) + " cannot run", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new GitException("git " + String.join(" ", arguments) + " was interrupted", exception);
        } finally {
            deleteQuietly(errorFile);
        }
    }

    private static void deleteQuietly(Path file) {
        try {
            if (file != null) {
                Files.deleteIfExists(file);
            }
        } catch (IOException ignored) {
            // The file is in the temporary folder.
        }
    }
}
