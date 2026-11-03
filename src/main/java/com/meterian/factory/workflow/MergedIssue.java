package com.meterian.factory.workflow;

import com.meterian.factory.state.IssueRecord;
import com.meterian.factory.state.IssueStatus;
import com.meterian.factory.state.StepType;

/**
 * A merged change is final. A failure after the merge never blocks the issue: the record stays DONE with a
 * note, and the next poll runs the finish step again, because the label factory goes away only at its end.
 */
public final class MergedIssue {

    private MergedIssue() {
    }

    /** @param finishStateName the name of the state with the step finish in the workflow of the issue */
    public static IssueRecord noteFailureAfterTheMerge(IssueRecord merged, String finishStateName,
                                                       Exception exception) {
        return merged.withState(finishStateName, StepType.FINISH)
                .withStatus(IssueStatus.DONE)
                .withNote("A step after the merge failed: " + exception.getClass().getName()
                        + ". The change is merged.");
    }
}
