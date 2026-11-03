package com.meterian.factory.workflow;

import com.meterian.factory.state.IssueRecord;

/** Counts the failed review sessions of a record. The reviews step and the risk step use it. */
final class ReviewSessionRetry {

    private static final int MAXIMUM_FAILED_REVIEW_ROUNDS = 2;

    /** A failed review session is not a defect of the code, so the round runs again and no fix loop starts. */
    StepResult retryReviewRound(IssueRecord record) {
        IssueRecord counted = record.withFailedReviewRound();
        if (counted.failedReviewRounds() >= MAXIMUM_FAILED_REVIEW_ROUNDS) {
            return StepResult.block(counted, "The review sessions failed two times.");
        }
        return StepResult.stay(counted.withNote("A review session failed; the factory runs the reviews again."));
    }
}
