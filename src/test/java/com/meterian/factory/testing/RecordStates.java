package com.meterian.factory.testing;

import com.meterian.factory.definition.BuiltInDefinition;
import com.meterian.factory.definition.WorkflowDefinition;
import com.meterian.factory.state.IssueRecord;
import com.meterian.factory.state.StepType;

/** Moves a record to a state of the built-in workflow. The step type comes from the definition. */
public final class RecordStates {

    private static final WorkflowDefinition WORKFLOW =
            BuiltInDefinition.load().workflow();

    private RecordStates() {
    }

    public static IssueRecord move(IssueRecord record, String stateName) {
        StepType stepType = WORKFLOW.state(stateName).step().type();
        return record.withState(stateName, stepType);
    }
}
