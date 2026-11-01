package com.meterian.factory.merge;

import com.meterian.factory.github.GitHubClient;
import com.meterian.factory.github.GitHubException;

/** Checks that GitHub has the commit that the gatekeeper forwarded, as the head of the pull request. */
public final class PushedCommitCheck {

    private PushedCommitCheck() {
    }

    /** A head that differs is a transient failure: the next poll repeats the step. */
    public static void verifyGitHubHas(GitHubClient gitHub, int pullRequestNumber, String pushedCommit) {
        if (!pushedCommit.equals(gitHub.pullRequestHead(pullRequestNumber))) {
            throw new GitHubException("GitHub does not have the pushed commit yet.");
        }
    }
}
