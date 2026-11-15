package com.meterian.factory.workflow;

import com.meterian.factory.state.IssueRecord;

/** Counts the failed review sessions of a record. The reviews action and the risk action use it. */
final class ReviewSessionRetry {

    private final int maximumFailedReviewRounds;

    ReviewSessionRetry(int maximumFailedReviewRounds) {
        this.maximumFailedReviewRounds = maximumFailedReviewRounds;
    }

    /** A failed review session is not a defect of the code, so the round runs again and no fix starts. */
    ActionResult retryReviewRound(IssueRecord record) {
        IssueRecord counted = record.withFailedReviewRound();
        if (counted.failedReviewRounds() >= maximumFailedReviewRounds) {
            return ActionResult.block(counted, "The review sessions failed " + maximumFailedReviewRounds + " times.");
        }
        return ActionResult.stay(counted.withNote("A review session failed; the factory runs the reviews again."));
    }
}
