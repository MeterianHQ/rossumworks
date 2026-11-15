package com.meterian.factory.workflow;

import com.meterian.factory.github.GitHubException;
import com.meterian.factory.github.Issue;
import com.meterian.factory.state.IssueRecord;
import com.meterian.factory.state.IssueStatus;
import com.meterian.factory.state.WaitReason;

/** Counts the checks that do not find the pushed commit on GitHub, and blocks the issue at the third. */
final class ForwardCheckFailure {

    private static final int LIMIT = 3;
    private static final String UNKNOWN_COMMITS_NOTE = "The forward can fail when the branch on GitHub has commits"
            + " that the factory does not know. Check the branch on GitHub. To let the factory replace the branch,"
            + " delete the branch on GitHub, and then add the label factory:start-again.";

    private final StatusPublisher statusPublisher;

    ForwardCheckFailure(StatusPublisher statusPublisher) {
        this.statusPublisher = statusPublisher;
    }

    /**
     * Below the limit, publishes the counted record and throws a GitHubException. The loop of the issue then
     * ends, and the next poll runs the step again with the count.
     */
    IssueRecord count(Issue issue, IssueRecord before, IssueRecord after, String pushedCommit, String pushedTree) {
        IssueRecord counted = after.withFailedForwardCheck(pushedTree);
        if (counted.failedForwardChecks() >= LIMIT) {
            return counted.withStatus(IssueStatus.BLOCKED)
                    .withWaitReason(WaitReason.FAILURE)
                    .withNote("GitHub does not show the pushed commit " + pushedCommit + " on "
                            + WorkspaceLayout.branch(issue.number()) + " after " + LIMIT + " tries.")
                    .withNote(UNKNOWN_COMMITS_NOTE);
        }
        statusPublisher.publish(issue, before, counted);
        throw new GitHubException("GitHub does not have the pushed commit yet.");
    }
}
