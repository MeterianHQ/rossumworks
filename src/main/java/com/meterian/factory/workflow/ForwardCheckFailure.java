package com.meterian.factory.workflow;

import com.meterian.factory.github.GitHubException;
import com.meterian.factory.github.Issue;
import com.meterian.factory.state.IssueRecord;

/** Counts the checks that do not find the pushed commit on GitHub, and blocks the issue after the last try. */
final class ForwardCheckFailure {

    private final StatusPublisher statusPublisher;
    private final int maximumTries;

    ForwardCheckFailure(StatusPublisher statusPublisher, int maximumTries) {
        this.statusPublisher = statusPublisher;
        this.maximumTries = maximumTries;
    }

    /**
     * Before the last try, publishes the counted record and throws a GitHubException. The loop of the issue then
     * ends, and the next poll runs the action again with the count.
     */
    ActionResult count(Issue issue, IssueRecord before, IssueRecord after, String pushedCommit, String pushedTree) {
        IssueRecord counted = after.withFailedForwardCheck(pushedTree);
        if (counted.failedForwardChecks() >= maximumTries) {
            return ActionResult.block(counted, "GitHub does not show the pushed commit " + pushedCommit + " on "
                    + WorkspaceLayout.branch(issue.number()) + " after " + maximumTries + " tries.");
        }
        statusPublisher.publish(issue, before, counted);
        throw new GitHubException("GitHub does not have the pushed commit yet.");
    }
}
