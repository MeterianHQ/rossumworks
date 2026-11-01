package com.meterian.factory.worker;

import com.meterian.factory.state.IssueRecord;
import com.meterian.factory.state.Phase;

/** Tells if the result of a step is final: a stop during the step keeps it and does not restore the record before. */
final class FinalStepResult {

    private FinalStepResult() {
    }

    /**
     * A merge cannot go back. A live state that the step opened or closed matches tmux, so the record on GitHub
     * must keep it: otherwise a restart resumes the agent next to the session of the owner.
     */
    static boolean isFinal(IssueRecord before, IssueRecord result) {
        boolean isMerged = result.phase() == Phase.DONE;
        boolean isLive = result.live().isPresent();
        boolean closedTheSession = before.live().isPresent() && result.live().isEmpty();
        return isMerged || isLive || closedTheSession;
    }
}
