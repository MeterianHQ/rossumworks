package com.meterian.factory.worker;

import com.meterian.factory.github.GitHubClient;
import com.meterian.factory.github.Issue;
import com.meterian.factory.github.RequestLabels;
import com.meterian.factory.state.IssueRecord;
import com.meterian.factory.workflow.IssueWorkflow;

/** The label factory:enter, and the enter that removes it. */
final class EnterRequests {

    static final String ENTER_LABEL = RequestLabels.ENTER;

    private final GitHubClient gitHub;
    private final IssueWorkflow workflow;

    EnterRequests(GitHubClient gitHub, IssueWorkflow workflow) {
        this.gitHub = gitHub;
        this.workflow = workflow;
    }

    static boolean isRequested(Issue issue) {
        return issue.labels().contains(ENTER_LABEL);
    }

    /**
     * Moves the issue to the enter target and opens the conversation in the same call. The label goes away only
     * after the record is on GitHub: a failure keeps it for the next poll.
     */
    IssueRecord enter(IssueRecord record) {
        int issueNumber = record.issueNumber();
        IssueRecord entered = workflow.enter(record);
        gitHub.removeLabel(issueNumber, ENTER_LABEL);
        return entered;
    }
}
