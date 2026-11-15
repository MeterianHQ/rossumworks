package com.meterian.factory.workflow;

import com.meterian.factory.agent.LiveSessions;
import com.meterian.factory.github.Issue;
import com.meterian.factory.state.IssueRecord;
import java.nio.file.Path;

/** The action of a state without an action: the issue waits for /factory:continue or the button Continue. */
final class WaitAction implements Action {

    private static final String CONTINUE_EVENT = "continue";

    private final LiveSessions liveSessions;

    WaitAction(LiveSessions liveSessions) {
        this.liveSessions = liveSessions;
    }

    /** The publisher clears the continue request after it writes the record of the next state. */
    @Override
    public ActionResult run(Issue issue, IssueRecord record, Path worktree) {
        if (liveSessions.isContinueRequested(record.issueNumber())) {
            return ActionResult.endWith(record, CONTINUE_EVENT);
        }
        return ActionResult.stay(record);
    }
}
