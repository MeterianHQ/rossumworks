package com.meterian.factory.setup;

import com.meterian.factory.configuration.RepositoryName;
import com.meterian.factory.git.Gatekeeper;
import com.meterian.factory.git.Git;
import com.meterian.factory.git.GitException;
import com.meterian.factory.github.GitHubClient;
import com.meterian.factory.github.GitHubException;
import com.meterian.factory.github.PullRequest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/** Reads the repository, and publishes the default .factory on the branch factory/setup through the gatekeeper. */
public final class RepositorySetup {

    private static final String SETUP_BRANCH = "factory/setup";
    private static final String FACTORY_FOLDER = ".factory";
    private static final String MAIN_FILE = ".factory/factory.yaml";
    private static final String WORKTREE_NAME = "setup-worktree";
    private static final String GATEKEEPER_REMOTE = "gatekeeper";
    private static final String COMMIT_MESSAGE = "Add the definition of the factory";

    private final Git git;
    private final GitHubClient gitHub;
    private final String githubUrl;

    public RepositorySetup(Git git, GitHubClient gitHub, String githubUrl) {
        this.git = git;
        this.gitHub = gitHub;
        this.githubUrl = githubUrl;
    }

    /** Clones the repository, or fetches the default branch when the clone exists. */
    public RepositoryFacts read(Path clone) throws SetupException {
        try {
            return readFacts(clone);
        } catch (GitException exception) {
            throw new SetupException(exception.getMessage());
        }
    }

    public Optional<PullRequest> findOpenSetup() {
        return gitHub.openPullRequest(SETUP_BRANCH);
    }

    public void verifyNoSetupBranch(RepositoryName repository) throws SetupException {
        if (gitHub.findBranchHead(SETUP_BRANCH).isPresent()) {
            throw new SetupException("The branch " + SETUP_BRANCH + " exists on GitHub without an open PR. "
                    + "The command wrote no worker.yaml. "
                    + "Delete the branch on GitHub with this command: "
                    + "gh api -X DELETE repos/" + repository.fullName() + "/git/refs/heads/" + SETUP_BRANCH + ". "
                    + "Then run create again.");
        }
    }

    /** Commits the files on the branch factory/setup, pushes it through the gatekeeper, and opens the PR. */
    public PullRequest publish(Path clone, Path gatekeeper, RepositoryFacts facts, Map<String, String> files)
            throws SetupException {
        PushResult pushed = pushSetupBranch(clone, gatekeeper, facts, files);
        verifyGitHubHasTheBranch(pushed);
        return gitHub.createPullRequest(SETUP_BRANCH, facts.defaultBranch(), "Set up the factory", describe(files));
    }

    /** Assigns the PR to the owner. A failure gives the problem, because the PR is open and stays useful. */
    public Optional<String> assignPullRequest(PullRequest pullRequest, String owner) {
        try {
            gitHub.assignIssue(pullRequest.number(), owner);
            return Optional.empty();
        } catch (GitHubException exception) {
            return Optional.of("Cannot assign the PR to " + owner + ": " + exception.getMessage()
                    + ". Assign it by hand.");
        }
    }

    private RepositoryFacts readFacts(Path clone) throws SetupException {
        String defaultBranch = obtainDefaultBranch(clone);
        String baseCommit = git.commitOf(clone, "origin/" + defaultBranch);
        boolean hasFactory = git.hasPath(clone, baseCommit, MAIN_FILE);
        if (!hasFactory && git.hasPath(clone, baseCommit, FACTORY_FOLDER)) {
            throw new SetupException("The default branch has a folder .factory without factory.yaml. "
                    + "Correct the folder, and run create again.");
        }
        return new RepositoryFacts(defaultBranch, baseCommit, hasFactory, path -> git.hasPath(clone, baseCommit, path));
    }

    private String obtainDefaultBranch(Path clone) throws SetupException {
        if (Files.exists(clone)) {
            git.fetch(clone, "origin", git.defaultBranch(clone));
        } else {
            createParentDirectory(clone);
            git.cloneRepository(githubUrl, clone);
        }
        return git.defaultBranch(clone);
    }

    private static void createParentDirectory(Path clone) throws SetupException {
        try {
            Files.createDirectories(clone.toAbsolutePath().getParent());
        } catch (IOException exception) {
            throw new SetupException("Cannot create the folder of the clone: " + exception.getMessage());
        }
    }

    private PushResult pushSetupBranch(Path clone, Path gatekeeper, RepositoryFacts facts,
                                       Map<String, String> files) throws SetupException {
        Path worktree = clone.toAbsolutePath().getParent().resolve(WORKTREE_NAME);
        try {
            Gatekeeper.ensure(gatekeeper, githubUrl);
            git.setRemote(clone, GATEKEEPER_REMOTE, gatekeeper.toString());
            removeLeftoverWorktree(clone, worktree);
            git.addWorktree(clone, worktree, SETUP_BRANCH, facts.baseCommit());
            return commitAndPush(clone, worktree, files);
        } catch (GitException exception) {
            throw new SetupException("Cannot push the branch " + SETUP_BRANCH + ": " + exception.getMessage());
        }
    }

    private PushResult commitAndPush(Path clone, Path worktree, Map<String, String> files) throws SetupException {
        PushResult result;
        try {
            writeFiles(worktree, files);
            git.commitAll(worktree, COMMIT_MESSAGE);
            String output = git.pushAndReadOutput(worktree, GATEKEEPER_REMOTE, SETUP_BRANCH);
            result = new PushResult(git.headCommit(worktree), output);
        } catch (GitException | SetupException failure) {
            removeWorktree(clone, worktree, failure);
            throw failure;
        }
        removeWorktree(clone, worktree, null);
        return result;
    }

    private void removeLeftoverWorktree(Path clone, Path worktree) {
        if (Files.exists(worktree)) {
            deleteFolder(worktree);
        }
        git.pruneWorktrees(clone);
    }

    /** A failure of the cleanup never hides the failure of the push. A leftover folder goes at the next start. */
    private void removeWorktree(Path clone, Path worktree, Exception primaryFailure) {
        try {
            try {
                git.removeWorktree(clone, worktree);
            } catch (GitException exception) {
                deleteFolder(worktree);
                git.pruneWorktrees(clone);
            }
        } catch (GitException cleanupFailure) {
            if (primaryFailure != null) {
                primaryFailure.addSuppressed(cleanupFailure);
            }
        }
    }

    private static void deleteFolder(Path folder) {
        try (Stream<Path> paths = Files.walk(folder)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
        } catch (IOException exception) {
            throw new GitException("Cannot delete the folder " + folder, exception);
        }
    }

    private static void writeFiles(Path worktree, Map<String, String> files) throws SetupException {
        try {
            for (Map.Entry<String, String> file : files.entrySet()) {
                Path target = worktree.resolve(file.getKey());
                Files.createDirectories(target.getParent());
                Files.writeString(target, file.getValue());
            }
        } catch (IOException exception) {
            throw new SetupException("Cannot write the files of .factory: " + exception.getMessage());
        }
    }

    private void verifyGitHubHasTheBranch(PushResult pushed) throws SetupException {
        Optional<String> head = gitHub.findBranchHead(SETUP_BRANCH);
        if (head.isEmpty() || !head.get().equals(pushed.commit())) {
            String reason = pushed.output().isEmpty() ? "git gave no message" : pushed.output();
            throw new SetupException("GitHub does not have the branch " + SETUP_BRANCH
                    + ". The gatekeeper did not forward it: " + reason);
        }
    }

    private record PushResult(String commit, String output) {
    }

    private static String describe(Map<String, String> files) {
        StringBuilder body = new StringBuilder("This PR adds the definition of the factory. It adds these files:\n\n");
        files.keySet().forEach(path -> body.append("- ").append(path).append('\n'));
        body.append("\nThe worker takes issues after the merge of this PR.\n")
                .append("The file docs/factory-definition.md of MeterianHQ/claude-ai-factory describes the files.\n");
        return body.toString();
    }
}
