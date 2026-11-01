package com.meterian.factory.merge;

import com.meterian.factory.git.Git;
import com.meterian.factory.git.GitException;
import com.meterian.factory.github.GitHubClient;
import java.nio.file.Path;

/**
 * Checks that GitHub has the pushed commit as the head of the pull request. When it does not, the gatekeeper
 * forwards the commit again: the hook forwards only a push that changes a branch, so a lost forward never comes
 * back alone.
 */
public final class PushedCommitCheck {

    private static final System.Logger LOGGER = System.getLogger(PushedCommitCheck.class.getName());
    private static final String GITHUB_REMOTE = "github";

    private final Git git;
    private final GitHubClient gitHub;
    private final Path gatekeeperDirectory;

    public PushedCommitCheck(Git git, GitHubClient gitHub, Path gatekeeperDirectory) {
        this.git = git;
        this.gitHub = gitHub;
        this.gatekeeperDirectory = gatekeeperDirectory;
    }

    /** Never deletes the branch on GitHub: a deleted branch closes the pull request. */
    public boolean isOnGitHub(int pullRequestNumber, String pushedCommit, String branch) {
        if (isHead(pullRequestNumber, pushedCommit)) {
            return true;
        }
        forwardAgain(pushedCommit, branch);
        return isHead(pullRequestNumber, pushedCommit);
    }

    private boolean isHead(int pullRequestNumber, String pushedCommit) {
        return pushedCommit.equals(gitHub.pullRequestHead(pullRequestNumber));
    }

    /** A failed forward is not a failure of the step: the check after it decides. */
    private void forwardAgain(String pushedCommit, String branch) {
        try {
            git.pushCommit(gatekeeperDirectory, GITHUB_REMOTE, pushedCommit, branch);
        } catch (GitException exception) {
            LOGGER.log(System.Logger.Level.WARNING, "The new forward of {0} failed: {1}", branch,
                    exception.getMessage());
        }
    }
}
