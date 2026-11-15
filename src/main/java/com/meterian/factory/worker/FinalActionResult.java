package com.meterian.factory.worker;

import com.meterian.factory.state.IssueRecord;
import com.meterian.factory.state.ActionType;
import java.util.Optional;

/** Tells if the result of an action is final: a stop during the action keeps the result. */
final class FinalActionResult {

    private FinalActionResult() {
    }

    /**
     * A merge cannot go back. A live state that the action opened or closed matches tmux, so the record on GitHub
     * must keep it: otherwise a restart resumes the agent next to the session of the owner.
     */
    static boolean isFinal(IssueRecord before, IssueRecord result) {
        boolean isMerged = result.action().equals(Optional.of(ActionType.FINISH));
        boolean isLive = result.live().isPresent();
        boolean closedTheSession = before.live().isPresent() && result.live().isEmpty();
        return isMerged || isLive || closedTheSession;
    }
}
