package com.meterian.factory.workflow;

import com.meterian.factory.definition.DefinitionLookup;
import com.meterian.factory.definition.FactoryDefinition;
import com.meterian.factory.definition.OnAnyState;
import com.meterian.factory.definition.Target;
import com.meterian.factory.events.WorkerEvents;
import com.meterian.factory.state.IssueRecord;
import java.util.Optional;
import java.util.function.Function;

/**
 * The three moves of on-any-state: the block, Enter, and a change of the issue text. Each move does nothing when
 * the issue is in the target state already (spec 4.4).
 */
public final class OnAnyStateMoves {

    private static final String BLOCKED_COLUMN = "blocked";

    private final DefinitionLookup definitions;
    private final WorkspaceLayout layout;
    private final WorkerEvents events;
    private final MoveRunner moveRunner;

    public OnAnyStateMoves(DefinitionLookup definitions, WorkspaceLayout layout, WorkerEvents events) {
        this.definitions = definitions;
        this.layout = layout;
        this.events = events;
        this.moveRunner = new MoveRunner(events);
    }

    /**
     * The note gives the reason of the block. Fail closed: when the definition of the record does not load, the
     * issue keeps its state, goes to the column blocked, and has no action: it waits, and no poll starts it.
     */
    public IssueRecord moveToBlockTarget(IssueRecord record, String note) {
        FactoryDefinition definition;
        try {
            definition = definitions.definitionOf(record);
        } catch (IllegalStateException exception) {
            events.writeBlock(record.issueNumber(), note);
            return record.withNewestNote(note).withMove(record.state(), Optional.empty(), BLOCKED_COLUMN);
        }
        if (record.state().equals(definition.workflow().onAnyState().block())) {
            return record;
        }
        return moveTo(definition, record.withNewestNote(note), OnAnyState::block);
    }

    /**
     * Enter from the block target resets the counts of all the limits, the same as continue (spec 4.5).
     *
     * @throws IllegalStateException when the definition of the record does not load
     */
    public IssueRecord moveToEnterTarget(IssueRecord record) {
        FactoryDefinition definition = definitions.definitionOf(record);
        boolean leavesTheBlockTarget = record.state().equals(definition.workflow().onAnyState().block());
        IssueRecord counted = leavesTheBlockTarget ? record.withoutEntries() : record;
        return moveTo(definition, counted, OnAnyState::enter);
    }

    /** @throws IllegalStateException when the definition of the record does not load */
    public IssueRecord moveToTextChangedTarget(IssueRecord record) {
        return moveTo(definitions.definitionOf(record), record.withNewestNote(IssueTextCheck.CHANGED_NOTE),
                OnAnyState::issueTextChanged);
    }

    private IssueRecord moveTo(FactoryDefinition definition, IssueRecord record,
            Function<OnAnyState, String> targetOf) {
        String target = targetOf.apply(definition.workflow().onAnyState());
        if (record.state().equals(target)) {
            return record;
        }
        return moveRunner.moveTo(definition, record, new Target.ToState(target), layout.worktree(record.issueNumber()));
    }
}
