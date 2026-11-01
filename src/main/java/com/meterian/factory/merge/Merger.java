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

/** Rebases the branch, builds again when the base moved, pushes through the gatekeeper, and squash merges. */
public final class Merger {

    private static final String ORIGIN = "origin";
    private static final String GATEKEEPER = "gatekeeper";
    private static final int MERGE_ATTEMPTS = 3;

    private final Git git;
    private final GitHubClient gitHub;
    private final BuildRunner buildRunner;
    private final TargetRepository target;
    private final String mergeTargetBranch;
    private final Duration retryDelay;

    public Merger(Git git, GitHubClient gitHub, BuildRunner buildRunner, TargetRepository target,
                  String mergeTargetBranch, Duration retryDelay) {
        this.git = git;
        this.gitHub = gitHub;
        this.buildRunner = buildRunner;
        this.target = target;
        this.mergeTargetBranch = mergeTargetBranch;
        this.retryDelay = retryDelay;
    }

    public MergeResult merge(Path worktree, IssueRecord record) {
        if (record.pullRequestNumber().isEmpty()) {
            return new MergeResult.Failed("The issue has no pull request.");
        }
        try {
            return rebaseAndMerge(worktree, record, record.pullRequestNumber().get());
        } catch (StepFailedException exception) {
            return new MergeResult.Failed(exception.getMessage());
        }
    }

    private MergeResult rebaseAndMerge(Path worktree, IssueRecord record, int pullRequestNumber) {
        // The fetch writes refs/remotes/origin of the clone, which all worktrees share.
        perform("The fetch of " + mergeTargetBranch, () -> CloneLock.run(target.cloneDirectory(), () -> {
            git.fetch(worktree, ORIGIN, mergeTargetBranch);
            return null;
        }));
        String upstream = ORIGIN + "/" + mergeTargetBranch;
        RebaseOutcome outcome = perform("The rebase", () -> git.rebase(worktree, upstream));
        return switch (outcome) {
            case CONFLICT -> new MergeResult.NeedsFix("The branch has a conflict with " + mergeTargetBranch
                    + ". Rebase it and solve the conflict.");
            case REBASED -> buildAfterRebase(worktree, record, pullRequestNumber, upstream);
            case UP_TO_DATE -> record.builtTreeHash()
                    .map(builtTreeHash -> mergeUnchangedBranch(worktree, record, pullRequestNumber, builtTreeHash))
                    .orElseGet(() -> new MergeResult.Failed("The record has no built tree hash."));
        };
    }

    private MergeResult buildAfterRebase(Path worktree, IssueRecord record, int pullRequestNumber,
                                         String upstream) {
        if (!patchIdIsUnchanged(worktree, record, upstream)) {
            return new MergeResult.NeedsNewReviews();
        }
        Optional<MergeResult> dirtyBeforeBuild = refuseUncommittedChanges(worktree);
        if (dirtyBeforeBuild.isPresent()) {
            return dirtyBeforeBuild.get();
        }
        String headCommit = perform("The read of the head commit", () -> git.headCommit(worktree));
        String treeHash = perform("The read of the tree hash", () -> git.treeHashOf(worktree, headCommit));
        BuildOutcome build = buildRunner.run(worktree, target);
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
        return pushAndSquashMerge(worktree, record, pullRequestNumber, headCommit, treeHash);
    }

    private MergeResult mergeUnchangedBranch(Path worktree, IssueRecord record, int pullRequestNumber,
                                             String builtTreeHash) {
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
        return pushAndSquashMerge(worktree, record, pullRequestNumber, headCommit, builtTreeHash);
    }

    private Optional<MergeResult> refuseUncommittedChanges(Path worktree) {
        if (perform("The check of the worktree", () -> git.hasUncommittedChanges(worktree))) {
            return Optional.of(new MergeResult.Failed("The worktree has changes that are not committed."));
        }
        return Optional.empty();
    }

    private boolean patchIdIsUnchanged(Path worktree, IssueRecord record, String upstream) {
        String patchId = perform("The read of the patch id", () -> git.patchId(worktree, upstream));
        return !patchId.isEmpty() && record.reviewedPatchId().equals(Optional.of(patchId));
    }

    private MergeResult pushAndSquashMerge(Path worktree, IssueRecord record, int pullRequestNumber,
                                           String headCommit, String builtTreeHash) {
        String branch = "factory/issue-" + record.issueNumber();
        perform("The push to the gatekeeper", () -> {
            git.pushCommit(worktree, GATEKEEPER, headCommit, branch);
            return null;
        });
        for (int attempt = 1; attempt <= MERGE_ATTEMPTS; attempt++) {
            MergeOutcome outcome = perform("The squash merge", () -> gitHub.squashMerge(pullRequestNumber, headCommit));
            if (outcome == MergeOutcome.MERGED) {
                return new MergeResult.Merged(builtTreeHash);
            }
            if (outcome == MergeOutcome.HEAD_CHANGED) {
                return new MergeResult.Failed("GitHub has another head for the pull request #" + pullRequestNumber + ".");
            }
            if (attempt < MERGE_ATTEMPTS && !waitForRetry()) {
                return new MergeResult.Failed("The wait for the merge was interrupted.");
            }
        }
        return new MergeResult.Failed("GitHub refuses the merge of the pull request #" + pullRequestNumber);
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

    private <T> T perform(String stepName, Supplier<T> step) {
        try {
            return step.get();
        } catch (GitException | GitHubException exception) {
            throw new StepFailedException(stepName + " failed: " + exception.getMessage());
        }
    }

    /** A step failed with a Git or a GitHub exception. */
    private static final class StepFailedException extends RuntimeException {
        StepFailedException(String message) {
            super(message);
        }
    }
}
