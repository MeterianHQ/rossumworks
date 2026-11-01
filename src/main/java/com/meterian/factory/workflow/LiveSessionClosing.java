package com.meterian.factory.workflow;

import com.meterian.factory.agent.LiveSessions;

/** Closes a live session after a failure. A failure of the close never hides the first failure. */
final class LiveSessionClosing {

    private LiveSessionClosing() {
    }

    static void closeAfterAFailure(LiveSessions liveSessions, int issueNumber, RuntimeException failure) {
        try {
            liveSessions.close(issueNumber);
        } catch (RuntimeException closeFailure) {
            failure.addSuppressed(closeFailure);
        }
    }
}
