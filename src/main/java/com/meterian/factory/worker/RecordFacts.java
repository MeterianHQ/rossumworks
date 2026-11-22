package com.meterian.factory.worker;

import com.meterian.factory.state.ActionType;
import com.meterian.factory.state.IssueRecord;
import com.meterian.factory.state.Verdict;
import java.util.Map;
import java.util.Optional;

/** The facts of a later record that an older record must not lose when the worker writes it back. */
final class RecordFacts {

    private RecordFacts() {
    }

    /**
     * Keeps the start of the agent session (the next run must resume it) and the pull request that the action
     * opened. A review round that did not end keeps only its own verdicts, so the card never shows the verdicts of
     * the earlier round again.
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
        if (isTheSameReviewRound(kept, later)) {
            result = withTheVerdictsOf(result, later);
        }
        return result;
    }

    private static boolean isTheSameReviewRound(IssueRecord kept, IssueRecord later) {
        return kept.action().equals(Optional.of(ActionType.REVIEWS)) && later.state().equals(kept.state());
    }

    private static IssueRecord withTheVerdictsOf(IssueRecord record, IssueRecord later) {
        IssueRecord result = record.withoutReviews();
        for (Map.Entry<String, Verdict> review : later.reviews().entrySet()) {
            result = result.withReview(review.getKey(), review.getValue());
        }
        return result;
    }
}
