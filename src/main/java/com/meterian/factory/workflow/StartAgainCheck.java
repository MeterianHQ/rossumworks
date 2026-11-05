package com.meterian.factory.workflow;

import com.meterian.factory.state.ActionType;
import com.meterian.factory.state.IssueRecord;
import java.util.Optional;

/** The rule of Start again, for the page and for the worker (spec 6.7). */
public final class StartAgainCheck {

    private StartAgainCheck() {
    }

    /**
     * Only an issue in the block target starts again. A live conversation still uses the worktree, and a record in
     * the finish state has a merged change.
     *
     * @param inBlockTarget true when the state of the record is the block target, see {@link IssueDefinitionFacts}
     */
    public static boolean isPossible(IssueRecord record, boolean inBlockTarget) {
        return inBlockTarget && record.live().isEmpty()
                && !record.action().equals(Optional.of(ActionType.FINISH));
    }
}
