package com.meterian.factory.workflow;

import com.meterian.factory.agent.LiveSessions;
import com.meterian.factory.state.IssueRecord;
import java.util.Optional;

/**
 * Closes a live session that the record does not know. A shutdown, a crash, or a kill during an open can leave
 * one, and its agent must not run next to the next phase.
 */
final class UnknownSessionCloser {

    static final String CLOSED_NOTE = "The factory closed a live session that the record did not know.";

    private static final System.Logger LOGGER = System.getLogger(UnknownSessionCloser.class.getName());

    private final LiveSessions liveSessions;

    UnknownSessionCloser(LiveSessions liveSessions) {
        this.liveSessions = liveSessions;
    }

    /**
     * A live record knows its session, so it stays as it is.
     *
     * @return the record, with a note when a session closed; empty when a session can still run
     */
    Optional<IssueRecord> closeUnknownSession(IssueRecord record) {
        if (record.live().isPresent()) {
            return Optional.of(record);
        }
        int issueNumber = record.issueNumber();
        try {
            if (!liveSessions.isOpen(issueNumber)) {
                return Optional.of(record);
            }
            liveSessions.close(issueNumber);
            return Optional.of(record.withNote(CLOSED_NOTE));
        } catch (RuntimeException exception) {
            LOGGER.log(System.Logger.Level.WARNING, "The live session of the issue #{0} did not close: {1}",
                    issueNumber, exception.getMessage());
            return Optional.empty();
        }
    }
}
