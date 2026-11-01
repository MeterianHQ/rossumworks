package com.meterian.factory.merge;

import com.meterian.factory.build.BuildOutcome;
import com.meterian.factory.build.BuildRunner;
import com.meterian.factory.configuration.TargetRepository;
import com.meterian.factory.git.CloneLock;
import com.meterian.factory.git.Git;
import com.meterian.factory.git.GitException;
import com.meterian.factory.git.RebaseOutcome;
import com.meterian.factory.github.GitHubClient;
import com.meterian.factory.github.GitHubException;
import com.meterian.factory.github.MergeOutcome;
import com.meterian.factory.state.IssueRecord;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Rebases the branch, builds again when the base moved, pushes through the gatekeeper, and squash merges. Only
 * one issue of a target merges at a time, and the base must not move between the rebase and the squash merge.
 * A failure of GitHub is transient: it leaves as a GitHubException, and the next poll runs the merge again.
 */
public final class Merger {

    private static final String ORIGIN = "origin";
    private static final String GATEKEEPER = "gatekeeper";
    private static final int MERGE_ATTEMPTS = 3;
    private static final int BASE_MOVE_LIMIT = 3;

    private final Git git;
    private final GitHubClient gitHub;
    private final BuildRunner buildRunner;
    private final TargetRepository target;
    private final String mergeTargetBranch;
    private final Duration retryDelay;
    private final PushedCommitCheck pushedCommitCheck;
    private final ReviewedCommit reviewedCommit;

    public Merger(Git git, GitHubClient gitHub, BuildRunner buildRunner, TargetRepository target,
                  String mergeTargetBranch, Path gatekeeperDirectory, Duration retryDelay) {
        this.git = git;
        this.gitHub = gitHub;
        this.buildRunner = buildRunner;
        this.target = target;
        this.mergeTargetBranch = mergeTargetBranch;
        this.retryDelay = retryDelay;
        this.pushedCommitCheck = new PushedCommitCheck(git, gitHub, gatekeeperDirectory);
        this.reviewedCommit = new ReviewedCommit(git, target.cloneDirectory(), mergeTargetBranch);
    }

    public MergeResult merge(Path worktree, IssueRecord record) {
        if (record.pullRequestNumber().isEmpty()) {
            return new MergeResult.Failed("The issue has no pull request.");
        }
        int pullRequestNumber = record.pullRequestNumber().get();
        return MergeLock.run(target.cloneDirectory(), () -> mergeOrRestore(worktree, record, pullRequestNumber));
    }

    /** A result that is not final, or a transient failure, leaves the worktree on the reviewed commit. */
    private MergeResult mergeOrRestore(Path worktree, IssueRecord record, int pullRequestNumber) {
        String commitBeforeTheMerge = git.headCommit(worktree);
        try {
            MergeResult result = mergeOnAStableBase(worktree, record, pullRequestNumber);
            if (result instanceof MergeResult.Postponed || result instanceof MergeResult.NotForwarded) {
                reviewedCommit.restore(worktree, record, commitBeforeTheMerge);
            }
            return result;
        } catch (GitHubException exception) {
            reviewedCommit.restore(worktree, record, commitBeforeTheMerge);
            throw exception;
        }
    }

    /** A base that moves before the squash merge starts the merge again from the rebase. */
    private MergeResult mergeOnAStableBase(Path worktree, IssueRecord record, int pullRequestNumber) {
        for (int attempt = 1; attempt <= BASE_MOVE_LIMIT; attempt++) {
            try {
                return rebaseAndMerge(worktree, record, pullRequestNumber);
            } catch (StepFailedException exception) {
                return new MergeResult.Failed(exception.getMessage());
            } catch (BaseMovedException moved) {
                // The next attempt fetches the new base and rebases on it.
            }
        }
        return new MergeResult.Postponed("The branch " + mergeTargetBranch + " moved " + BASE_MOVE_LIMIT
                + " times before the merge. The next poll tries again.");
    }

    private MergeResult rebaseAndMerge(Path worktree, IssueRecord record, int pullRequestNumber) {
        String upstreamCommit = fetchUpstreamCommit(worktree);
        RebaseOutcome outcome = perform("The rebase", () -> git.rebase(worktree, upstreamCommit));
        return switch (outcome) {
            case CONFLICT -> new MergeResult.NeedsFix("The branch has a conflict with " + mergeTargetBranch
                    + ". Rebase it and solve the conflict.");
            case REBASED -> buildAfterRebase(worktree, record, pullRequestNumber, upstreamCommit);
            case UP_TO_DATE -> record.builtTreeHash()
                    .map(builtTreeHash -> mergeUnchangedBranch(worktree, record, pullRequestNumber, builtTreeHash,
                            upstreamCommit))
                    .orElseGet(() -> new MergeResult.Failed("The record has no built tree hash."));
        };
    }

    /** The fetch writes refs/remotes/origin of the clone, which all worktrees share. */
    private String fetchUpstreamCommit(Path worktree) {
        return perform("The fetch of " + mergeTargetBranch, () -> CloneLock.run(target.cloneDirectory(), () -> {
            git.fetch(worktree, ORIGIN, mergeTargetBranch);
            return git.commitOf(worktree, ORIGIN + "/" + mergeTargetBranch);
        }));
    }

    private MergeResult buildAfterRebase(Path worktree, IssueRecord record, int pullRequestNumber,
                                         String upstreamCommit) {
        if (!isPatchIdUnchanged(worktree, record, upstreamCommit)) {
            return new MergeResult.NeedsNewReviews();
        }
        Optional<MergeResult> dirtyBeforeBuild = refuseUncommittedChanges(worktree);
        if (dirtyBeforeBuild.isPresent()) {
            return dirtyBeforeBuild.get();
        }
        String headCommit = perform("The read of the head commit", () -> git.headCommit(worktree));
        String treeHash = perform("The read of the tree hash", () -> git.treeHashOf(worktree, headCommit));
        BuildOutcome build = buildRunner.run(worktree, target);
        if (build.stopped()) {
            return new MergeResult.Postponed(build.outputTail());
        }
        if (!build.passed()) {
            return new MergeResult.NeedsFix(build.outputTail());
        }
        Optional<MergeResult> dirtyAfterBuild = refuseUncommittedChanges(worktree);
        if (dirtyAfterBuild.isPresent()) {
            return dirtyAfterBuild.get();
        }
        if (!perform("The read of the head commit", () -> git.headCommit(worktree)).equals(headCommit)) {
            return new MergeResult.Failed("The build changed the files.");
        }
        return pushAndSquashMerge(worktree, record, pullRequestNumber, headCommit, treeHash, upstreamCommit);
    }

    private MergeResult mergeUnchangedBranch(Path worktree, IssueRecord record, int pullRequestNumber,
                                             String builtTreeHash, String upstreamCommit) {
        Optional<MergeResult> dirty = refuseUncommittedChanges(worktree);
        if (dirty.isPresent()) {
            return dirty.get();
        }
        String headCommit = perform("The read of the head commit", () -> git.headCommit(worktree));
        String treeHash = perform("The read of the tree hash", () -> git.treeHashOf(worktree, headCommit));
        boolean isReviewed = record.reviewedTreeHash().filter(treeHash::equals).isPresent();
        if (!treeHash.equals(builtTreeHash) || !isReviewed) {
            return new MergeResult.Failed("The files differ from the built and reviewed tree.");
        }
        return pushAndSquashMerge(worktree, record, pullRequestNumber, headCommit, builtTreeHash, upstreamCommit);
    }

    private Optional<MergeResult> refuseUncommittedChanges(Path worktree) {
        if (perform("The check of the worktree", () -> git.hasUncommittedChanges(worktree))) {
            return Optional.of(new MergeResult.Failed("The worktree has changes that are not committed."));
        }
        return Optional.empty();
    }

    private boolean isPatchIdUnchanged(Path worktree, IssueRecord record, String upstreamCommit) {
        String patchId = perform("The read of the patch id", () -> git.patchId(worktree, upstreamCommit));
        return !patchId.isEmpty() && record.reviewedPatchId().equals(Optional.of(patchId));
    }

    private MergeResult pushAndSquashMerge(Path worktree, IssueRecord record, int pullRequestNumber,
                                           String headCommit, String builtTreeHash, String upstreamCommit) {
        String branch = "factory/issue-" + record.issueNumber();
        perform("The push to the gatekeeper", () -> {
            git.pushCommit(worktree, GATEKEEPER, headCommit, branch);
            return null;
        });
        if (!pushedCommitCheck.isOnGitHub(pullRequestNumber, headCommit, branch)) {
            return new MergeResult.NotForwarded(headCommit);
        }
        for (int attempt = 1; attempt <= MERGE_ATTEMPTS; attempt++) {
            verifyBaseDidNotMove(worktree, upstreamCommit);
            MergeOutcome outcome = gitHub.squashMerge(pullRequestNumber, headCommit);
            if (outcome == MergeOutcome.MERGED) {
                return new MergeResult.Merged(builtTreeHash, describeMergedTree(pullRequestNumber, builtTreeHash));
            }
            if (outcome == MergeOutcome.HEAD_CHANGED) {
                throw new GitHubException("GitHub has another head for the pull request #" + pullRequestNumber + ".");
            }
            if (attempt < MERGE_ATTEMPTS && !waitForRetry()) {
                return new MergeResult.Failed("The wait for the merge was interrupted.");
            }
        }
        throw new GitHubException("GitHub refuses the merge of the pull request #" + pullRequestNumber + ".");
    }

    private void verifyBaseDidNotMove(Path worktree, String upstreamCommit) {
        if (!fetchUpstreamCommit(worktree).equals(upstreamCommit)) {
            throw new BaseMovedException();
        }
    }

    /** The merge is done, so a failure here must not become a failed merge. */
    private Optional<String> describeMergedTree(int pullRequestNumber, String builtTreeHash) {
        return gitHub.mergeCommitTree(pullRequestNumber)
                .filter(mergedTreeHash -> !mergedTreeHash.equals(builtTreeHash))
                .map(mergedTreeHash -> "The tree on " + mergeTargetBranch
                        + " after the merge differs from the built tree.");
    }

    private boolean waitForRetry() {
        try {
            Thread.sleep(retryDelay);
            return true;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** A GitHubException is transient and leaves: the next poll runs the merge step again. */
    private <T> T perform(String stepName, Supplier<T> step) {
        try {
            return step.get();
        } catch (GitException exception) {
            throw new StepFailedException(stepName + " failed: " + exception.getMessage());
        }
    }

    /** A step failed with a Git exception. */
    private static final class StepFailedException extends RuntimeException {
        StepFailedException(String message) {
            super(message);
        }
    }

    /** The merge target moved after the rebase. The merge must start again from the rebase. */
    private static final class BaseMovedException extends RuntimeException {
        BaseMovedException() {
            super(null, null, false, false);
        }
    }
}
