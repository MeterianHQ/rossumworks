package com.meterian.factory.workflow;

import com.meterian.factory.state.IssueRecord;
import com.meterian.factory.state.IssueStatus;
import com.meterian.factory.state.Phase;

/**
 * A merged change is final. A failure after the merge never blocks the issue: the record stays DONE with a
 * note, and the next poll runs the finish step again, because the label factory goes away only at its end.
 */
public final class MergedIssue {

    private MergedIssue() {
    }

    public static IssueRecord noteFailureAfterTheMerge(IssueRecord merged, Exception exception) {
        return merged.withPhase(Phase.DONE)
                .withStatus(IssueStatus.DONE)
                .withNote("A step after the merge failed: " + exception.getClass().getName()
                        + ". The change is merged.");
    }
}
