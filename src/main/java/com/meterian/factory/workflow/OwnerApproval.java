package com.meterian.factory.workflow;

import com.meterian.factory.github.GitHubClient;
import com.meterian.factory.github.Issue;
import com.meterian.factory.state.IssueRecord;

/** The label factory:approve: who gave it, the note when the account is not allowed, and its removal. */
final class OwnerApproval {

    private static final String APPROVE_LABEL = "factory:approve";

    private final GitHubClient gitHub;
    private final RequestReader requests;

    OwnerApproval(GitHubClient gitHub, RequestReader requests) {
        this.gitHub = gitHub;
        this.requests = requests;
    }

    boolean isGiven(Issue issue) {
        return issue.labels().contains(APPROVE_LABEL) && requests.isGivenByAllowedAccount(issue.number(), APPROVE_LABEL);
    }

    /** Call it only when the approval is not given. A label from another account gets a note. */
    IssueRecord noteIgnoredLabel(Issue issue, IssueRecord record) {
        if (!issue.labels().contains(APPROVE_LABEL)) {
            return record;
        }
        String actor = requests.findLabelActor(issue.number(), APPROVE_LABEL).orElse("an unknown account");
        return record.withNote("The label " + APPROVE_LABEL + " from " + actor
                + " is ignored: the account is not in accounts.allowed.");
    }

    /** An approval is for one change. A new change needs a new approval. */
    void withdraw(int issueNumber) {
        gitHub.removeLabel(issueNumber, APPROVE_LABEL);
    }
}
