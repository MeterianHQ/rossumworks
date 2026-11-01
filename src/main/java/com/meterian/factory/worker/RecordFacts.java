package com.meterian.factory.worker;

import com.meterian.factory.state.IssueRecord;
import java.util.Optional;

/** The facts of a later record that an older record must not lose when the worker writes it back. */
final class RecordFacts {

    private RecordFacts() {
    }

    /**
     * Keeps the start of the agent session (the next run must resume it) and the pull request that the step
     * opened.
     */
    static IssueRecord keep(IssueRecord kept, Optional<IssueRecord> laterRecord) {
        if (laterRecord.isEmpty()) {
            return kept;
        }
        IssueRecord later = laterRecord.get();
        IssueRecord result = kept;
        boolean isSameSession = later.sessionId().equals(kept.sessionId());
        if (isSameSession && later.agentSessionStarted() && !kept.agentSessionStarted()) {
            result = result.withAgentSessionStarted();
        }
        if (kept.pullRequestNumber().isEmpty() && later.pullRequestNumber().isPresent()) {
            result = result.withPullRequest(later.pullRequestNumber().get());
        }
        return result;
    }
}
