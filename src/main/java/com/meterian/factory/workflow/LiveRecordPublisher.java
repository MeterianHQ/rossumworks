package com.meterian.factory.workflow;

import com.meterian.factory.agent.LiveSessions;
import com.meterian.factory.github.Issue;
import com.meterian.factory.state.IssueRecord;
import com.meterian.factory.state.LiveSession;
import java.util.Optional;

/**
 * Publishes a record and keeps tmux in step with it. A session that the record on GitHub does not know is
 * closed, and a continue request stays until GitHub has the record without the live state.
 */
final class LiveRecordPublisher {

    private final StatusPublisher statusPublisher;
    private final LiveSessions liveSessions;

    LiveRecordPublisher(StatusPublisher statusPublisher, LiveSessions liveSessions) {
        this.statusPublisher = statusPublisher;
        this.liveSessions = liveSessions;
    }

    IssueRecord publish(Issue issue, IssueRecord before, IssueRecord after) {
        IssueRecord published = publishOrCloseTheNewSession(issue, before, after);
        if (before.live().isPresent() && published.live().isEmpty()) {
            liveSessions.clearContinueRequest(published.issueNumber());
        }
        return published;
    }

    private IssueRecord publishOrCloseTheNewSession(Issue issue, IssueRecord before, IssueRecord after) {
        try {
            return statusPublisher.publish(issue, before, after);
        } catch (RuntimeException exception) {
            if (opensASession(before, after)) {
                liveSessions.close(after.issueNumber());
            }
            throw exception;
        }
    }

    /** Only an open of a session gives a live state with a new link. */
    private static boolean opensASession(IssueRecord before, IssueRecord after) {
        Optional<String> newLink = after.live().flatMap(LiveSession::link);
        return newLink.isPresent() && !after.live().equals(before.live());
    }
}
