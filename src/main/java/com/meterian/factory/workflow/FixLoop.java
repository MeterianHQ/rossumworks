package com.meterian.factory.workflow;

import com.meterian.factory.merge.MergeResult;
import com.meterian.factory.prompt.Finding;
import com.meterian.factory.prompt.PromptLibrary;
import com.meterian.factory.state.IssueRecord;
import com.meterian.factory.state.IssueStatus;
import com.meterian.factory.state.Phase;
import com.meterian.factory.state.WaitReason;
import java.nio.file.Path;
import java.util.List;

/** Sends findings back to the agent in charge, up to the fix-loop limit. */
final class FixLoop {

    private final PromptLibrary prompts;
    private final OwnerApproval ownerApproval;
    private final ConflictResolution conflictResolution;
    private final int fixLoopLimit;

    FixLoop(PromptLibrary prompts, OwnerApproval ownerApproval, ConflictResolution conflictResolution,
            int fixLoopLimit) {
        this.prompts = prompts;
        this.ownerApproval = ownerApproval;
        this.conflictResolution = conflictResolution;
        this.fixLoopLimit = fixLoopLimit;
    }

    IssueRecord start(IssueRecord record, Path worktree, List<Finding> findings) {
        IssueRecord counted = record.withFixLoop();
        if (counted.fixLoops() > fixLoopLimit) {
            return counted.withStatus(IssueStatus.BLOCKED).withWaitReason(WaitReason.FIX_LOOP_LIMIT);
        }
        new FactoryFolder(worktree).writeFixPrompt(prompts.fix(findings));
        ownerApproval.withdraw(counted.issueNumber());
        return counted.withStatus(IssueStatus.IN_PROGRESS).withPhase(Phase.IMPLEMENT);
    }

    /** Opens a merge with the target in the worktree, so that the agent solves a conflict in the files. */
    IssueRecord startForTheMerge(IssueRecord record, Path worktree, MergeResult.NeedsFix needsFix) {
        ConflictResolution.OpenedMerge openedMerge = conflictResolution.openMerge(worktree, needsFix);
        IssueRecord withConflict = record.withConflictedPaths(openedMerge.conflictedPaths());
        return start(withConflict, worktree, List.of(openedMerge.finding()));
    }
}
