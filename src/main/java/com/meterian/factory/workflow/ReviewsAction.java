package com.meterian.factory.workflow;

import com.meterian.factory.agent.AgentException;
import com.meterian.factory.agent.ApiKeyDetectedException;
import com.meterian.factory.github.Issue;
import com.meterian.factory.state.IssueRecord;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

final class ReviewsAction implements Action {

    private final ActionServices services;
    private final VersionServices version;
    private final ReviewSessionRetry reviewSessionRetry;

    ReviewsAction(ActionServices services, VersionServices version, ReviewSessionRetry reviewSessionRetry) {
        this.services = services;
        this.version = version;
        this.reviewSessionRetry = reviewSessionRetry;
    }

    @Override
    public ActionResult run(Issue issue, IssueRecord record, Path worktree)
            throws AgentException, ApiKeyDetectedException {
        if (!services.phaseRunner().mayStartPhase()) {
            return ActionResult.stay(record);
        }
        String headCommit = services.git().headCommit(worktree);
        String treeHash = services.git().treeHashOf(worktree, headCommit);
        ReviewRound.Outcome outcome = version.reviewRound().review(issue, record, worktree, treeHash);
        if (services.git().hasUncommittedChanges(worktree)
                || !services.git().headCommit(worktree).equals(headCommit)) {
            return ActionResult.block(outcome.record(), "A review changed the files.");
        }
        if (outcome.sessionStopped()) {
            return ActionResult.stay(outcome.record());
        }
        if (outcome.sessionFailed()) {
            return reviewSessionRetry.retryReviewRound(outcome.record());
        }
        services.reviewNotesToPublish().put(issue.number(), outcome.notes());
        IssueRecord reviewed = outcome.record().withoutFailedReviewRounds();
        Map<String, Object> reviewFields = Map.of("reviews", describeReviews(outcome));
        if (!outcome.allApprove()) {
            return ActionResult.endWithFindings(reviewed, "rejected", outcome.findings())
                    .withActionFields(reviewFields);
        }
        String patchId = services.git().patchId(worktree, version.mergeTargetReference());
        return ActionResult.endWith(reviewed.withReviewedTree(treeHash, patchId).withReviewedHeadCommit(headCommit),
                "approved").withActionFields(reviewFields);
    }

    /** The verdict and the count of the findings of each review, for the event line. */
    private static Map<String, Object> describeReviews(ReviewRound.Outcome outcome) {
        Map<String, Object> reviews = new LinkedHashMap<>();
        outcome.findingCounts().forEach((reviewName, count) -> reviews.put(reviewName, Map.of(
                "verdict", outcome.record().reviews().get(reviewName).name().toLowerCase(),
                "findings", count)));
        return reviews;
    }
}
