package com.meterian.factory.workflow;

import com.meterian.factory.agent.AgentException;
import com.meterian.factory.agent.ApiKeyDetectedException;
import com.meterian.factory.github.Issue;
import com.meterian.factory.state.IssueRecord;
import java.nio.file.Path;

final class ReviewsStep implements Step {

    private final StepServices services;
    private final ReviewSessionRetry reviewSessionRetry;

    ReviewsStep(StepServices services, ReviewSessionRetry reviewSessionRetry) {
        this.services = services;
        this.reviewSessionRetry = reviewSessionRetry;
    }

    @Override
    public StepResult run(Issue issue, IssueRecord record, Path worktree)
            throws AgentException, ApiKeyDetectedException {
        if (!services.phaseRunner().mayStartPhase()) {
            return StepResult.stay(record);
        }
        String headCommit = services.git().headCommit(worktree);
        String treeHash = services.git().treeHashOf(worktree, headCommit);
        ReviewRound.Outcome outcome = services.reviewRound().review(issue, record, worktree, treeHash);
        if (services.git().hasUncommittedChanges(worktree)
                || !services.git().headCommit(worktree).equals(headCommit)) {
            return StepResult.block(outcome.record(), "A review changed the files.");
        }
        if (outcome.sessionFailed()) {
            return reviewSessionRetry.retryReviewRound(record);
        }
        services.reviewNotesToPublish().put(issue.number(), outcome.notes());
        IssueRecord reviewed = outcome.record().withoutFailedReviewRounds();
        if (!outcome.allApprove()) {
            return StepResult.endWithFindings(reviewed, "rejected", outcome.findings());
        }
        String patchId = services.git().patchId(worktree, services.mergeTargetReference());
        return StepResult.endWith(reviewed.withReviewedTree(treeHash, patchId).withReviewedHeadCommit(headCommit),
                "approved");
    }
}
