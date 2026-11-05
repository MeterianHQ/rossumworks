package com.meterian.factory.worker;

import com.meterian.factory.agent.LiveSessions;
import com.meterian.factory.github.Issue;
import com.meterian.factory.state.IssueRecord;
import com.meterian.factory.state.LiveSession;
import com.meterian.factory.state.ActionType;
import com.meterian.factory.workflow.StatusPublisher;
import java.util.Optional;

/** Reads the live session of a live record at a poll: the continue request and a session that ended. */
final class LiveRecordCheck {

    private static final String ENDED_NOTE = "The live conversation ended without /factory:continue."
            + " Add the label factory:enter to open a new one.";

    private final LiveSessions liveSessions;
    private final StatusPublisher statusPublisher;

    LiveRecordCheck(LiveSessions liveSessions, StatusPublisher statusPublisher) {
        this.liveSessions = liveSessions;
        this.statusPublisher = statusPublisher;
    }

    private boolean isContinueRequested(IssueRecord record) {
        return liveSessions.isContinueRequested(record.issueNumber());
    }

    /**
     * The record without the link tells the owner to enter again. The issue still waits in the conversation
     * state.
     */
    IssueRecord forgetEndedSession(Issue issue, IssueRecord record) {
        if (!hasEndedSession(record)) {
            return record;
        }
        return statusPublisher.publish(issue, record, record.withoutLiveLink().withNote(ENDED_NOTE));
    }

    /** Only a record that waits in the conversation counts: a block closes the session on purpose. */
    private boolean hasEndedSession(IssueRecord record) {
        boolean hasLink = record.live().flatMap(LiveSession::link).isPresent();
        boolean waitsInTheConversation = record.action().equals(Optional.of(ActionType.CONVERSATION));
        return hasLink && waitsInTheConversation && !isContinueRequested(record)
                && !liveSessions.isOpen(record.issueNumber());
    }
}
