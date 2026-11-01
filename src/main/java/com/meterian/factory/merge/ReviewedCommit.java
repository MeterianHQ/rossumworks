package com.meterian.factory.merge;

import com.meterian.factory.git.CloneLock;
import com.meterian.factory.git.Git;
import com.meterian.factory.state.IssueRecord;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Brings the worktree back to the commit that the reviews approved. A merge step that rebased and did not end
 * must not leave a tree that the merge gate refuses at the next poll.
 */
public final class ReviewedCommit {

    private final Git git;
    private final Path clone;
    private final String mergeTargetReference;

    public ReviewedCommit(Git git, Path clone, String mergeTargetBranch) {
        this.git = git;
        this.clone = clone;
        this.mergeTargetReference = "origin/" + mergeTargetBranch;
    }

    /** A record without the reviewed commit goes back to the commit before the merge step. */
    void restore(Path worktree, IssueRecord record, String commitBeforeTheMerge) {
        resetTo(worktree, record.reviewedHeadCommit().orElse(commitBeforeTheMerge));
    }

    /**
     * The second guard, at the start of the merge phase: a clean HEAD with another tree but the reviewed patch
     * id is a rebase of the reviewed commit, so it goes back to that commit. Any other state stays as it is.
     */
    public void restoreRebasedHead(Path worktree, IssueRecord record) {
        if (record.reviewedHeadCommit().isEmpty() || git.hasUncommittedChanges(worktree)) {
            return;
        }
        if (record.reviewedTreeHash().equals(Optional.of(git.treeHash(worktree)))) {
            return;
        }
        String patchId = git.patchId(worktree, mergeTargetReference);
        if (!patchId.isEmpty() && record.reviewedPatchId().equals(Optional.of(patchId))) {
            resetTo(worktree, record.reviewedHeadCommit().get());
        }
    }

    private void resetTo(Path worktree, String commit) {
        if (git.headCommit(worktree).equals(commit)) {
            return;
        }
        CloneLock.run(clone, () -> git.resetHard(worktree, commit));
    }
}
