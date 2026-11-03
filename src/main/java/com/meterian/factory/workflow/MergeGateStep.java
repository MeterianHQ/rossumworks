package com.meterian.factory.workflow;

import com.meterian.factory.github.Issue;
import com.meterian.factory.merge.MergeDecision;
import com.meterian.factory.merge.MergeGate;
import com.meterian.factory.merge.MergeResult;
import com.meterian.factory.state.IssueRecord;
import java.nio.file.Path;
import java.util.List;

final class MergeGateStep implements Step {

    private final StepServices services;
    private final VersionServices version;

    MergeGateStep(StepServices services, VersionServices version) {
        this.services = services;
        this.version = version;
    }

    @Override
    public StepResult run(Issue issue, IssueRecord record, Path worktree) {
        if (isMergedOnGitHub(record)) {
            return StepResult.endWith(record.withBuiltTree(services.git().treeHash(worktree)), "merged");
        }
        version.reviewedCommit().restoreRebasedHead(worktree, record);
        boolean isApproved = services.ownerApproval().isGiven(issue);
        IssueRecord noted = isApproved ? record : services.ownerApproval().noteIgnoredLabel(issue, record);
        boolean questionOpen = new FactoryFolder(worktree).hasQuestion();
        MergeDecision decision = MergeGate.decide(noted, services.git().treeHash(worktree), questionOpen, isApproved);
        return switch (decision) {
            case MergeDecision.NeedsOwner needsOwner -> StepResult.endWith(noted, "needs-approval");
            case MergeDecision.Refused refused ->
                    StepResult.block(noted, "The merge gate refuses: " + refused.reason() + ".");
            case MergeDecision.Automatic automatic -> mergePullRequest(issue, noted, worktree);
            case MergeDecision.Approved approved -> mergePullRequest(issue, noted, worktree);
        };
    }

    private StepResult mergePullRequest(Issue issue, IssueRecord record, Path worktree) {
        MergeResult result = version.merger().merge(worktree, record);
        return switch (result) {
            case MergeResult.Merged merged -> StepResult.endWith(merged.note().map(record::withNote).orElse(record)
                    .withoutFailedForwardChecks().withBuiltTree(merged.builtTreeHash()), "merged");
            case MergeResult.NeedsNewReviews needsNewReviews ->
                    StepResult.endWith(record.withoutReviews(), "needs-new-reviews");
            case MergeResult.NeedsFix needsFix -> openMergeForTheFix(record, worktree, needsFix);
            case MergeResult.Failed failed -> StepResult.block(record, "The merge failed: " + failed.reason());
            case MergeResult.Postponed postponed -> StepResult.stay(record);
            case MergeResult.NotForwarded notForwarded -> StepResult.stay(services.forwardCheckFailure().count(
                    issue, record, record, notForwarded.pushedCommit(), notForwarded.pushedTree()));
        };
    }

    /** Opens a merge with the target in the worktree, so that the agent solves a conflict in the files. */
    private StepResult openMergeForTheFix(IssueRecord record, Path worktree, MergeResult.NeedsFix needsFix) {
        ConflictResolution.OpenedMerge openedMerge = version.conflictResolution().openMerge(worktree, needsFix);
        IssueRecord withConflict = record.withConflictedPaths(openedMerge.conflictedPaths());
        return StepResult.endWithFindings(withConflict, "conflict", List.of(openedMerge.finding()));
    }

    private boolean isMergedOnGitHub(IssueRecord record) {
        return record.pullRequestNumber().map(services.gitHub()::isMerged).orElse(false);
    }
}
