package com.meterian.factory.testing;

import com.meterian.factory.definition.BuiltInDefinition;
import com.meterian.factory.definition.StateDefinition;
import com.meterian.factory.definition.WorkflowDefinition;
import com.meterian.factory.state.IssueRecord;
import java.util.List;

/** Makes records in the states of the built-in workflow. The action and the column come from the definition. */
public final class RecordStates {

    /** The names of the four reviews of the built-in workflow, in its order. */
    public static final List<String> REVIEW_NAMES = List.of("correctness", "security", "architecture", "quality");

    private static final WorkflowDefinition WORKFLOW = BuiltInDefinition.load().workflow();

    private RecordStates() {
    }

    /** A new record in the start state of the built-in workflow. */
    public static IssueRecord queued(int issueNumber) {
        return IssueRecord.queued(issueNumber, WORKFLOW.start());
    }

    /** Moves the record as the engine does: a state with an action that does not wait for you becomes previous. */
    public static IssueRecord move(IssueRecord record, String stateName) {
        StateDefinition state = WORKFLOW.state(stateName);
        IssueRecord moved = record.withMove(state.name(), state.actionType(), state.column());
        return state.waitsForYou() ? moved : moved.withPreviousState(state.name());
    }
}
