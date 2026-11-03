package com.meterian.factory.git;

import com.meterian.factory.workflow.ScratchFolder;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class ProcessGit implements Git {

    private static final String FACTORY_FOLDER_PATTERN = ScratchFolder.NAME + "/";

    private static final String FACTORY_BRANCH_PREFIX = "factory/";
    private static final String ORIGIN_HEAD = "refs/remotes/origin/HEAD";
    private static final List<String> FIXED_IDENTITY = List.of(
            "-c", "user.name=claude-ai-factory",
            "-c", "user.email=claude-ai-factory@users.noreply.github.com");
    /** A fetch or a push that does not end in this time ends, so that a lost network never stops the worker. */
    static final Duration NETWORK_TIMEOUT = Duration.ofMinutes(5);

    private final GitProcess process;
    private final Duration networkTimeout;

    public ProcessGit() {
        this(Map.of());
    }

    /** The isolation entries always win over the extra entries. */
    ProcessGit(Map<String, String> extraEnvironment) {
        this(extraEnvironment, NETWORK_TIMEOUT);
    }

    ProcessGit(Map<String, String> extraEnvironment, Duration networkTimeout) {
        this.process = new GitProcess(extraEnvironment);
        this.networkTimeout = networkTimeout;
    }

    @Override
    public void fetch(Path repository, String remote, String branch) {
        runOverTheNetwork(repository, "fetch", remote, branch);
    }

    @Override
    public void cloneRepository(String url, Path directory) {
        Path absoluteDirectory = directory.toAbsolutePath();
        runOverTheNetwork(absoluteDirectory.getParent(), "clone", url, absoluteDirectory.toString());
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
        // ISO-8859-1 keeps each byte as one character, so a byte that is not UTF-8 still changes the hash.
        String difference = new String(runForBytes(worktree, "diff", "--binary", "--full-index", "--no-textconv",
                "--no-ext-diff", "--no-color", mergeBase + "..HEAD"), StandardCharsets.ISO_8859_1);
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
                    .digest(stable.toString().getBytes(StandardCharsets.ISO_8859_1));
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
    public String commitOf(Path repository, String reference) {
        return run(repository, "rev-parse", "--verify", reference + "^{commit}").trim();
    }

    @Override
    public String resolveCommit(Path repository, String reference) {
        return run(repository, "rev-parse", "--verify", reference + "^{commit}").trim();
    }

    @Override
    public List<String> commitsOfPath(Path repository, String reference, String path, int limit) {
        String output = run(repository, "log", "--format=%H", "-n", String.valueOf(limit), reference, "--", path);
        return output.lines().map(String::strip).filter(line -> !line.isEmpty()).toList();
    }

    /** The blob is read raw with cat-file: a textconv or a filter of the repository never runs. */
    @Override
    public Optional<String> showFile(Path repository, String commit, String path) {
        String object = resolveCommit(repository, commit) + ":" + path;
        if (execute(repository, "cat-file", "-e", object).exitCode() != 0) {
            return Optional.empty();
        }
        return Optional.of(new String(runForBytes(repository, "cat-file", "blob", object), StandardCharsets.UTF_8));
    }

    /** A clone without origin/HEAD asks the remote once, and keeps the answer in the clone. */
    @Override
    public String defaultBranch(Path clone) {
        if (execute(clone, "symbolic-ref", "-q", ORIGIN_HEAD).exitCode() != 0) {
            runOverTheNetwork(clone, "remote", "set-head", "origin", "--auto");
        }
        String reference = run(clone, "symbolic-ref", "--short", ORIGIN_HEAD).trim();
        return reference.substring(reference.indexOf('/') + 1);
    }

    @Override
    public String headCommit(Path worktree) {
        return run(worktree, "rev-parse", "HEAD").trim();
    }

    @Override
    public boolean changesPath(Path worktree, String baseReference, String path) {
        GitProcess.Result result = execute(worktree, "diff", "--quiet", baseReference + "...HEAD", "--", path);
        if (result.exitCode() > 1) {
            throw createFailure(List.of("diff", "--quiet", baseReference + "...HEAD", "--", path), result);
        }
        return result.exitCode() == 1;
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
        // A merge commit can contain the upstream. A rebase then replays old commits, so it must not run.
        if (isAncestor(worktree, upstream, "HEAD")) {
            return RebaseOutcome.UP_TO_DATE;
        }
        String headBefore = run(worktree, "rev-parse", "HEAD").trim();
        GitProcess.Result result = execute(worktree, withIdentity("rebase", upstream));
        if (result.exitCode() == 0) {
            String headAfter = run(worktree, "rev-parse", "HEAD").trim();
            return headBefore.equals(headAfter) ? RebaseOutcome.UP_TO_DATE : RebaseOutcome.REBASED;
        }
        if (isRebaseInProgress(worktree)) {
            run(worktree, "rebase", "--abort");
            return RebaseOutcome.CONFLICT;
        }
        throw createFailure(List.of("rebase", upstream), result);
    }

    @Override
    public List<String> mergeWithoutCommit(Path worktree, String upstream) {
        GitProcess.Result result = execute(worktree, withIdentity("merge", "--no-commit", "--no-ff", upstream));
        List<String> conflictedPaths = conflictedPaths(worktree);
        if (result.exitCode() != 0 && conflictedPaths.isEmpty()) {
            throw createFailure(List.of("merge", "--no-commit", "--no-ff", upstream), result);
        }
        return conflictedPaths;
    }

    @Override
    public boolean hasOpenMerge(Path worktree) {
        return isGitPathPresent(worktree, "MERGE_HEAD");
    }

    @Override
    public List<String> conflictedPaths(Path worktree) {
        String output = run(worktree, "-c", "core.quotePath=false", "diff", "--name-only", "-z", "--diff-filter=U");
        return Arrays.stream(output.split("\0")).filter(path -> !path.isEmpty()).toList();
    }

    @Override
    public List<String> parentsOf(Path worktree, String commit) {
        String[] hashes = run(worktree, "rev-list", "--parents", "-n", "1", commit).trim().split(" ");
        return Arrays.asList(hashes).subList(1, hashes.length);
    }

    @Override
    public boolean isAncestor(Path worktree, String ancestor, String commit) {
        return execute(worktree, "merge-base", "--is-ancestor", ancestor, commit).exitCode() == 0;
    }

    @Override
    public void resetSoft(Path worktree, String commit) {
        run(worktree, "reset", "-q", "--soft", commit);
    }

    @Override
    public void resetHard(Path worktree, String commit) {
        run(worktree, "reset", "-q", "--hard", commit);
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
        runOverTheNetwork(worktree, "push", "--force", remote, commit + ":refs/heads/" + branch);
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
        boolean exists = execute(repository, "remote", "get-url", name).exitCode() == 0;
        run(repository, "remote", exists ? "set-url" : "add", name, url);
    }

    String run(Path directory, String... arguments) {
        return requireSuccess(arguments, execute(directory, arguments)).outputText();
    }

    private byte[] runForBytes(Path directory, String... arguments) {
        return requireSuccess(arguments, execute(directory, arguments)).output();
    }

    private void runOverTheNetwork(Path directory, String... arguments) {
        requireSuccess(arguments, process.execute(directory, Optional.of(networkTimeout), List.of(arguments)));
    }

    private GitProcess.Result execute(Path directory, String... arguments) {
        return process.execute(directory, Optional.empty(), List.of(arguments));
    }

    private static GitProcess.Result requireSuccess(String[] arguments, GitProcess.Result result) {
        if (result.exitCode() != 0) {
            throw createFailure(List.of(arguments), result);
        }
        return result;
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

    private static GitException createFailure(List<String> arguments, GitProcess.Result result) {
        return new GitException("git " + String.join(" ", arguments) + " failed with exit code "
                + result.exitCode() + ": " + result.errorOutput().trim());
    }
}
