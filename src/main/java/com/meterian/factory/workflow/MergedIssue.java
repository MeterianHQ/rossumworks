package com.meterian.factory.workflow;

import com.meterian.factory.definition.StateDefinition;
import com.meterian.factory.state.IssueRecord;

/**
 * A merged change is final. A failure after the merge never blocks the issue: the record goes to the finish state
 * with a note, and the next poll runs the finish action again, because the label factory goes away only at its end.
 */
public final class MergedIssue {

    private MergedIssue() {
    }

    /** @param finishState the state with the action finish in the workflow of the issue */
    public static IssueRecord noteFailureAfterTheMerge(IssueRecord merged, StateDefinition finishState,
                                                       Exception exception) {
        return merged.withMove(finishState.name(), finishState.actionType(), finishState.column())
                .withNote(describeFailure(exception));
    }

    /** The definition of the issue does not load, so the record keeps its state. */
    public static IssueRecord noteFailureInTheFinishState(IssueRecord merged, Exception exception) {
        return merged.withNote(describeFailure(exception));
    }

    private static String describeFailure(Exception exception) {
        return "An action after the merge failed: " + exception.getClass().getName() + ". The change is merged.";
    }
}
