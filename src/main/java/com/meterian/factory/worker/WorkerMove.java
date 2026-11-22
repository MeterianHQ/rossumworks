package com.meterian.factory.worker;

import com.meterian.factory.definition.StateDefinition;
import com.meterian.factory.definition.WorkflowDefinition;
import com.meterian.factory.state.IssueRecord;
import com.meterian.factory.workflow.MoveReport;

/**
 * Moves a record to a state without the engine, for example to the finish state of a merged issue, and writes the
 * event line of the move. A move to the same state writes no line.
 */
final class WorkerMove {

    private final MoveReport moveReport;

    WorkerMove(MoveReport moveReport) {
        this.moveReport = moveReport;
    }

    IssueRecord moveTo(WorkflowDefinition workflow, IssueRecord record, StateDefinition state) {
        IssueRecord moved = record.withMove(state.name(), state.actionType(), state.column());
        writeMoveOfAnotherState(workflow, record, moved);
        return moved;
    }

    /** A new record, for example the queued record of a start again, replaces the record in its earlier state. */
    void writeMoveOfAnotherState(WorkflowDefinition workflow, IssueRecord earlier, IssueRecord moved) {
        if (!moved.state().equals(earlier.state())) {
            moveReport.writeMove(workflow, earlier.state(), moved);
        }
    }
}
