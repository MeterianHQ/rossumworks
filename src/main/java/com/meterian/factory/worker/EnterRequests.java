package com.meterian.factory.worker;

import com.meterian.factory.github.GitHubClient;
import com.meterian.factory.github.Issue;
import com.meterian.factory.state.IssueRecord;
import com.meterian.factory.workflow.IssueWorkflow;
import com.meterian.factory.workflow.RequestReader;

/** The label factory:enter: who gave it, and the enter that removes it. */
final class EnterRequests {

    static final String ENTER_LABEL = "factory:enter";

    private final GitHubClient gitHub;
    private final RequestReader requests;
    private final IgnoredLabelNote ignoredLabelNote;
    private final IssueWorkflow workflow;

    EnterRequests(GitHubClient gitHub, RequestReader requests, IgnoredLabelNote ignoredLabelNote,
                  IssueWorkflow workflow) {
        this.gitHub = gitHub;
        this.requests = requests;
        this.ignoredLabelNote = ignoredLabelNote;
        this.workflow = workflow;
    }

    /** Tells if the issue has the label from an allowed account. A label from another account gets a note once. */
    boolean isRequested(Issue issue) {
        int issueNumber = issue.number();
        if (!issue.labels().contains(ENTER_LABEL)) {
            return false;
        }
        if (requests.isGivenByAllowedAccount(issueNumber, ENTER_LABEL)) {
            return true;
        }
        ignoredLabelNote.write(issueNumber, ENTER_LABEL, gitHub.comments(issueNumber));
        return false;
    }

    /** The label goes away only after the record is on GitHub: a failure keeps it for the next poll. */
    IssueRecord enter(IssueRecord record) {
        int issueNumber = record.issueNumber();
        IssueRecord entered = workflow.enterLiveMode(record);
        gitHub.removeLabel(issueNumber, ENTER_LABEL);
        return entered;
    }
}
