package com.meterian.factory.workflow;

import com.meterian.factory.definition.LimitDefinition;
import com.meterian.factory.definition.StateDefinition;
import com.meterian.factory.definition.WorkflowDefinition;
import com.meterian.factory.events.WorkerEvents;
import com.meterian.factory.state.IssueRecord;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** Writes the event line of a move, and the line of the block when the move enters the block target. */
public final class MoveReport {

    private final WorkerEvents events;

    public MoveReport(WorkerEvents events) {
        this.events = events;
    }

    /**
     * Writes a move without an event: a limit, a move of on-any-state, or a move of the worker. The newest note of
     * the record gives the reason of a block.
     *
     * @param entered the record in its new state
     */
    public void writeMove(WorkflowDefinition workflow, String fromState, IssueRecord entered) {
        writeLines(workflow, fromState, Optional.empty(), entered, entered.newestNote().orElse(""));
    }

    /**
     * Writes a move by the event of an action. An event without a reason does not change the notes, so the reason
     * of a block names the event and its state.
     */
    void writeMoveByEvent(WorkflowDefinition workflow, ActionResult.Event event, IssueRecord entered) {
        String fromState = event.record().state();
        String blockReason = event.reason()
                .orElse("The event " + event.event() + " of the state " + fromState + ".");
        writeLines(workflow, fromState, Optional.of(event.event()), entered, blockReason);
    }

    private void writeLines(WorkflowDefinition workflow, String fromState, Optional<String> event,
            IssueRecord entered, String blockReason) {
        events.writeMove(entered.issueNumber(), fromState, event, entered.state(), entered.column(),
                describeEntries(workflow, entered));
        String blockTarget = workflow.onAnyState().block();
        if (entered.state().equals(blockTarget) && !fromState.equals(blockTarget)) {
            events.writeBlock(entered.issueNumber(), blockReason);
        }
    }

    /** The count and the limit of each state with a limit, in the order of the workflow, for example "1/3". */
    private static Map<String, String> describeEntries(WorkflowDefinition workflow, IssueRecord record) {
        Map<String, String> entries = new LinkedHashMap<>();
        for (StateDefinition state : workflow.states().values()) {
            Optional<LimitDefinition> limit = state.limit();
            limit.ifPresent(found -> entries.put(state.name(), record.entriesOf(state.name()) + "/" + found.entries()));
        }
        return entries;
    }
}
