package com.meterian.factory.workflow;

import com.meterian.factory.definition.ActionDefinition;
import com.meterian.factory.definition.FactoryDefinition;
import com.meterian.factory.definition.MergeMode;
import com.meterian.factory.definition.StateDefinition;
import com.meterian.factory.github.Issue;
import com.meterian.factory.merge.MergeChecks;
import com.meterian.factory.merge.MergeDecision;
import com.meterian.factory.merge.MergeGate;
import com.meterian.factory.merge.MergeResult;
import com.meterian.factory.state.ActionType;
import com.meterian.factory.state.IssueRecord;
import java.nio.file.Path;
import java.util.List;

final class MergeGateAction implements Action {

    static final String NEEDS_APPROVAL_NOTE = "The merge needs your approval.";
    static final String FACTORY_CHANGE_NOTE =
            "The pull request changes the folder .factory. The merge needs your approval.";
    private static final String DEFINITION_FOLDER = ".factory";

    private final ActionServices services;
    private final VersionServices version;
    private final FactoryDefinition definition;

    MergeGateAction(ActionServices services, VersionServices version, FactoryDefinition definition) {
        this.services = services;
        this.version = version;
        this.definition = definition;
    }

    @Override
    public ActionResult run(Issue issue, IssueRecord record, Path worktree) {
        if (isMergedOnGitHub(record)) {
            return ActionResult.endWith(record.withBuiltTree(services.git().treeHash(worktree)), "merged");
        }
        version.reviewedCommit().restoreRebasedHead(worktree, record);
        String currentTreeHash = services.git().treeHash(worktree);
        boolean isApproved = services.ownerApproval().isGiven(issue) || isApprovedForTree(record, currentTreeHash);
        boolean questionOpen = new FactoryFolder(worktree).hasQuestion();
        if (!isApproved && changesDefinitionFolder(worktree)) {
            return ActionResult.endWithReason(record, "factory-changed", FACTORY_CHANGE_NOTE);
        }
        MergeDecision decision = MergeGate.decide(record, currentTreeHash, questionOpen, isApproved,
                findMergeMode(), findChecks());
        return switch (decision) {
            case MergeDecision.NeedsOwner needsOwner ->
                    ActionResult.endWithReason(record, "needs-approval", NEEDS_APPROVAL_NOTE);
            case MergeDecision.Refused refused ->
                    ActionResult.block(record, "The merge gate refuses: " + refused.reason() + ".");
            case MergeDecision.Automatic automatic -> mergePullRequest(issue, record, worktree);
            case MergeDecision.Approved approved -> mergePullRequest(issue, record, worktree);
        };
    }

    private boolean isApprovedForTree(IssueRecord record, String currentTreeHash) {
        return !currentTreeHash.isBlank() && record.approvedTree().filter(currentTreeHash::equals).isPresent();
    }

    private MergeMode findMergeMode() {
        return definition.workflow().findStateWithAction(ActionType.MERGE_GATE)
                .flatMap(StateDefinition::action)
                .map(ActionDefinition::mergeMode)
                .orElse(MergeMode.RISK);
    }

    /** A workflow without a reviews state has no review checks. The loader refuses a merge gate without a build state. */
    private MergeChecks findChecks() {
        boolean hasReviews = definition.workflow().findStateWithAction(ActionType.REVIEWS).isPresent();
        boolean hasBuild = definition.workflow().findStateWithAction(ActionType.BUILD).isPresent();
        return new MergeChecks(hasReviews ? version.prompts().reviewNames() : List.of(), hasBuild);
    }

    private ActionResult mergePullRequest(Issue issue, IssueRecord record, Path worktree) {
        MergeResult result = version.merger().merge(worktree, record);
        return switch (result) {
            case MergeResult.Merged merged -> ActionResult.endWith(merged.note().map(record::withNote).orElse(record)
                    .withoutFailedForwardChecks().withBuiltTree(merged.builtTreeHash()), "merged");
            case MergeResult.NeedsNewReviews needsNewReviews ->
                    ActionResult.endWith(record.withoutReviews(), "needs-new-reviews");
            case MergeResult.NeedsFix needsFix -> openMergeForTheFix(record, worktree, needsFix);
            case MergeResult.Failed failed -> ActionResult.block(record, "The merge failed: " + failed.reason());
            case MergeResult.Postponed postponed -> ActionResult.stay(record);
            case MergeResult.NotForwarded notForwarded -> services.forwardCheckFailure().count(
                    issue, record, record, notForwarded.pushedCommit(), notForwarded.pushedTree());
        };
    }

    /** Opens a merge with the target in the worktree, so that the agent solves a conflict in the files. */
    private ActionResult openMergeForTheFix(IssueRecord record, Path worktree, MergeResult.NeedsFix needsFix) {
        ConflictResolution.OpenedMerge openedMerge = version.conflictResolution().openMerge(worktree, needsFix);
        IssueRecord withConflict = record.withConflictedPaths(openedMerge.conflictedPaths());
        return ActionResult.endWithFindings(withConflict, "conflict", List.of(openedMerge.finding()));
    }

    private boolean changesDefinitionFolder(Path worktree) {
        return services.git().changesPath(worktree, version.mergeTargetReference(), DEFINITION_FOLDER);
    }

    private boolean isMergedOnGitHub(IssueRecord record) {
        return record.pullRequestNumber().map(services.gitHub()::isMerged).orElse(false);
    }
}
