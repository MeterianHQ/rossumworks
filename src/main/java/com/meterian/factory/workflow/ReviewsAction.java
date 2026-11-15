package com.meterian.factory.workflow;

import com.meterian.factory.agent.AgentException;
import com.meterian.factory.agent.ApiKeyDetectedException;
import com.meterian.factory.github.Issue;
import com.meterian.factory.state.IssueRecord;
import java.nio.file.Path;

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
            return ActionResult.stay(record);
        }
        if (outcome.sessionFailed()) {
            return reviewSessionRetry.retryReviewRound(record);
        }
        services.reviewNotesToPublish().put(issue.number(), outcome.notes());
        IssueRecord reviewed = outcome.record().withoutFailedReviewRounds();
        if (!outcome.allApprove()) {
            return ActionResult.endWithFindings(reviewed, "rejected", outcome.findings());
        }
        String patchId = services.git().patchId(worktree, version.mergeTargetReference());
        return ActionResult.endWith(reviewed.withReviewedTree(treeHash, patchId).withReviewedHeadCommit(headCommit),
                "approved");
    }
}
