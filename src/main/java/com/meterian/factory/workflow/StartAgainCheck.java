package com.meterian.factory.workflow;

import com.meterian.factory.state.IssueRecord;
import com.meterian.factory.state.IssueStatus;
import com.meterian.factory.state.Phase;

/** The rule of Start again, for the page and for the worker. */
public final class StartAgainCheck {

    private StartAgainCheck() {
    }

    /** A live conversation still uses the worktree, and a record in phase DONE has a merged change. */
    public static boolean isPossible(IssueRecord record) {
        return record.status() == IssueStatus.BLOCKED && record.live().isEmpty() && record.phase() != Phase.DONE;
    }
}
