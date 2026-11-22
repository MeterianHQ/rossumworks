package com.meterian.factory.workflow;

import com.meterian.factory.definition.FactoryDefinition;
import com.meterian.factory.definition.OnAnyState;
import com.meterian.factory.definition.StateDefinition;
import com.meterian.factory.definition.Target;
import com.meterian.factory.definition.WorkflowDefinition;
import com.meterian.factory.events.WorkerEvents;
import com.meterian.factory.prompt.Finding;
import com.meterian.factory.state.ActionType;
import com.meterian.factory.state.IssueRecord;
import com.meterian.factory.state.PendingPrompt;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Moves an issue to the next state of its workflow, with the rules of the engine for each move (spec 6.6). Each
 * move writes an event, and a move to the block target also writes the reason of the block.
 */
final class MoveRunner {

    private static final String CONTINUE_EVENT = "continue";

    private final MoveReport moveReport;

    MoveRunner(WorkerEvents events) {
        this.moveReport = new MoveReport(events);
    }

    /** Moves the issue by the on: entry of the event. The reason of the event becomes a note. */
    IssueRecord move(FactoryDefinition definition, ActionResult.Event event, Path worktree) {
        WorkflowDefinition workflow = definition.workflow();
        StateDefinition source = workflow.state(event.record().state());
        Target target = source.on().get(event.event());
        if (target == null) {
            throw new IllegalStateException("The state " + source.name() + " has no next state for the event "
                    + event.event() + ".");
        }
        IssueRecord noted = event.reason().map(event.record()::withNewestNote).orElse(event.record());
        boolean leavesTheBlockTarget = source.name().equals(workflow.onAnyState().block())
                && event.event().equals(CONTINUE_EVENT);
        IssueRecord counted = leavesTheBlockTarget ? noted.withoutEntries() : noted;
        return enter(workflow, counted, source, Optional.of(event), target, worktree);
    }

    /** Moves the issue without an event: a limit, a move of on-any-state, or a move of the worker. */
    IssueRecord moveTo(FactoryDefinition definition, IssueRecord record, Target target, Path worktree) {
        WorkflowDefinition workflow = definition.workflow();
        return enter(workflow, record, workflow.state(record.state()), Optional.empty(), target, worktree);
    }

    private IssueRecord enter(WorkflowDefinition workflow, IssueRecord record, StateDefinition source,
            Optional<ActionResult.Event> event, Target target, Path worktree) {
        StateDefinition destination = resolve(workflow, record, target);
        IssueRecord prompted = destination.actionType().equals(Optional.of(ActionType.CODING))
                ? choosePendingPrompt(workflow, record, source, event, target, worktree) : record;
        IssueRecord moved = prompted.withMove(destination.name(), destination.actionType(), destination.column())
                .withoutFailedReviewRounds().withoutFailedForwardChecks();
        IssueRecord withPrevious = destination.waitsForYou() ? moved : moved.withPreviousState(destination.name());
        IssueRecord entered = isReturnFromConversation(source, event, destination)
                ? withPrevious.withAfterConversation() : withPrevious.withoutAfterConversation();
        reportMove(workflow, entered, source, event);
        return entered;
    }

    private void reportMove(WorkflowDefinition workflow, IssueRecord entered, StateDefinition source,
            Optional<ActionResult.Event> event) {
        if (event.isPresent()) {
            moveReport.writeMoveByEvent(workflow, event.get(), entered);
        } else {
            moveReport.writeMove(workflow, source.name(), entered);
        }
    }

    private static StateDefinition resolve(WorkflowDefinition workflow, IssueRecord record, Target target) {
        return switch (target) {
            case Target.ToState toState -> workflow.state(toState.name());
            case Target.ToPrevious toPrevious -> workflow.state(record.previousState().orElseThrow(
                    () -> new IllegalStateException("The issue has no previous state.")));
        };
    }

    /**
     * A return with continue from the block target or the enter target, and a return to previous from a state that
     * waits for you, keep the pending prompt. Each other entry gets a new pending prompt.
     */
    private static IssueRecord choosePendingPrompt(WorkflowDefinition workflow, IssueRecord record,
            StateDefinition source, Optional<ActionResult.Event> event, Target target, Path worktree) {
        if (keepsThePendingPrompt(workflow.onAnyState(), source, target)) {
            return record;
        }
        Optional<ActionType> sourceType = source.actionType();
        if (sourceType.isPresent() && event.filter(found -> sourceType.get().carriesFindings(found.event())).isPresent()) {
            return withPendingFindings(record, PendingPrompt.FIX, event.get().findings(), worktree);
        }
        if (sourceType.equals(Optional.of(ActionType.PREPARE))) {
            return record.withPendingPrompt(PendingPrompt.IMPLEMENT);
        }
        List<Finding> findings = event.map(ActionResult.Event::findings).orElse(List.of());
        return withPendingFindings(record, PendingPrompt.RESUME, findings, worktree);
    }

    private static boolean keepsThePendingPrompt(OnAnyState onAnyState, StateDefinition source, Target target) {
        boolean isReturnWithContinue = source.name().equals(onAnyState.block())
                || source.name().equals(onAnyState.enter());
        return isReturnWithContinue || (source.waitsForYou() && target instanceof Target.ToPrevious);
    }

    private static IssueRecord withPendingFindings(IssueRecord record, PendingPrompt prompt, List<Finding> findings,
            Path worktree) {
        new FactoryFolder(worktree).writePendingFindings(findings);
        return record.withPendingPrompt(prompt);
    }

    /** Only the next coding action after the conversation gets the prompt continue. */
    private static boolean isReturnFromConversation(StateDefinition source, Optional<ActionResult.Event> event,
            StateDefinition destination) {
        return source.actionType().equals(Optional.of(ActionType.CONVERSATION))
                && event.filter(found -> found.event().equals(CONTINUE_EVENT)).isPresent()
                && destination.actionType().equals(Optional.of(ActionType.CODING));
    }
}
