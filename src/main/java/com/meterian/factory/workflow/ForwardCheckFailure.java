package com.meterian.factory.workflow;

import com.meterian.factory.github.GitHubException;
import com.meterian.factory.github.Issue;
import com.meterian.factory.ownership.OwnershipGuard;
import com.meterian.factory.state.IssueRecord;

/** Counts the checks that do not find the pushed commit on GitHub, and blocks the issue after the last try. */
final class ForwardCheckFailure {

    private static final String UNKNOWN_COMMITS_NOTE = "The forward can fail when the branch on GitHub has commits"
            + " that the factory does not know. Check the branch on GitHub. To let the factory replace the branch,"
            + " delete the branch on GitHub, and then add the label factory:start-again.";

    private final StatusPublisher statusPublisher;
    private final int maximumTries;
    private final OwnershipGuard ownershipGuard;

    ForwardCheckFailure(StatusPublisher statusPublisher, int maximumTries, OwnershipGuard ownershipGuard) {
        this.statusPublisher = statusPublisher;
        this.maximumTries = maximumTries;
        this.ownershipGuard = ownershipGuard;
    }

    /**
     * Before the last try, publishes the counted record and throws a GitHubException. The loop of the issue then
     * ends, and the next poll runs the action again with the count. The gatekeeper refuses a forward over a commit
     * of another factory: when another account owns the issue now, the refused forward is not a failure, so the
     * issue gets no count and no block.
     */
    ActionResult count(Issue issue, IssueRecord before, IssueRecord after, String pushedCommit, String pushedTree) {
        ownershipGuard.verifyStillOwner(issue.number());
        IssueRecord counted = after.withFailedForwardCheck(pushedTree);
        if (counted.failedForwardChecks() >= maximumTries) {
            IssueRecord noted = counted.withNote("GitHub does not show the pushed commit " + pushedCommit + " on "
                    + WorkspaceLayout.branch(issue.number()) + " after " + maximumTries + " tries.");
            return ActionResult.block(noted, UNKNOWN_COMMITS_NOTE);
        }
        statusPublisher.publish(issue, before, counted);
        throw new GitHubException("GitHub does not have the pushed commit yet.");
    }
}
