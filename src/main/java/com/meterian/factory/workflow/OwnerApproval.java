package com.meterian.factory.workflow;

import com.meterian.factory.github.GitHubClient;
import com.meterian.factory.github.Issue;

/** The label factory:approve and its removal. */
final class OwnerApproval {

    private static final String APPROVE_LABEL = "factory:approve";

    private final GitHubClient gitHub;

    OwnerApproval(GitHubClient gitHub) {
        this.gitHub = gitHub;
    }

    boolean isGiven(Issue issue) {
        return issue.labels().contains(APPROVE_LABEL);
    }

    /** An approval is for one change. A new change needs a new approval. */
    void withdraw(int issueNumber) {
        gitHub.removeLabel(issueNumber, APPROVE_LABEL);
    }
}
