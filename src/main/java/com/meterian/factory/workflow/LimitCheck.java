package com.meterian.factory.workflow;

import com.meterian.factory.definition.FactoryDefinition;
import com.meterian.factory.definition.LimitDefinition;
import com.meterian.factory.definition.StateDefinition;
import com.meterian.factory.state.IssueRecord;
import java.nio.file.Path;
import java.util.Optional;

/** The limit of the entries into a state. An entry counts only when the action of the state starts. */
final class LimitCheck {

    private final MoveRunner moveRunner;

    LimitCheck(MoveRunner moveRunner) {
        this.moveRunner = moveRunner;
    }

    /** Empty when the state takes the entry. Else the record in the then state of the limit, with a note. */
    Optional<IssueRecord> refuseEntry(FactoryDefinition definition, IssueRecord record, Path worktree) {
        StateDefinition state = definition.workflow().state(record.state());
        Optional<LimitDefinition> limit = state.limit();
        if (limit.isEmpty() || record.entriesOf(state.name()) + 1 <= limit.get().entries()) {
            return Optional.empty();
        }
        IssueRecord noted = record.withNewestNote("The state " + state.name() + " reached its limit of "
                + limit.get().entries() + " entries.");
        return Optional.of(moveRunner.moveTo(definition, noted, limit.get().then(), worktree));
    }

    /** Only a state with a limit keeps a count. */
    static IssueRecord countEntry(FactoryDefinition definition, IssueRecord record) {
        StateDefinition state = definition.workflow().state(record.state());
        return state.limit().isPresent() ? record.withEntryCounted(state.name()) : record;
    }
}
