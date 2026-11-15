package com.meterian.factory.ownership;

import com.meterian.factory.github.GitHubClient;

/** Reads the assignees of the issue from GitHub at each check. */
public final class GitHubOwnershipGuard implements OwnershipGuard {

    private final GitHubClient gitHub;
    private final String workerLogin;

    public GitHubOwnershipGuard(GitHubClient gitHub, String workerLogin) {
        this.gitHub = gitHub;
        this.workerLogin = workerLogin;
    }

    /** An issue without assignee is not a loss: the next poll claims it again. */
    @Override
    public void verifyStillOwner(int issueNumber) {
        Ownership ownership = OwnershipRule.decide(gitHub.issue(issueNumber), workerLogin,
                () -> gitHub.assignmentEvents(issueNumber));
        if (ownership == Ownership.OWNED_BY_ANOTHER) {
            throw new OwnershipLostException(issueNumber);
        }
    }
}
