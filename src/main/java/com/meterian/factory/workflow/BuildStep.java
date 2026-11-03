package com.meterian.factory.workflow;

import com.meterian.factory.build.BuildOutcome;
import com.meterian.factory.github.Issue;
import com.meterian.factory.prompt.Finding;
import com.meterian.factory.state.IssueRecord;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

final class BuildStep implements Step {

    private final StepServices services;
    private final VersionServices version;

    BuildStep(StepServices services, VersionServices version) {
        this.services = services;
        this.version = version;
    }

    @Override
    public StepResult run(Issue issue, IssueRecord record, Path worktree) {
        if (services.git().hasUncommittedChanges(worktree)) {
            return StepResult.block(record, "The worktree has changes that are not committed.");
        }
        String headCommit = services.git().headCommit(worktree);
        String treeHash = services.git().treeHashOf(worktree, headCommit);
        if (!isBuiltWithALostForward(record, treeHash)) {
            BuildOutcome outcome = services.buildRunner().run(worktree, version.build());
            if (outcome.stopped()) {
                return StepResult.stay(record);
            }
            if (services.git().hasUncommittedChanges(worktree)
                    || !services.git().headCommit(worktree).equals(headCommit)) {
                return StepResult.block(record, "The build changed the files.");
            }
            if (!outcome.passed()) {
                Finding failure = new Finding("The build failed", outcome.outputTail());
                return StepResult.endWithFindings(record, "failed", List.of(failure));
            }
        }
        services.ownerApproval().withdraw(record.issueNumber());
        PullRequestPublisher.PublishedBranch published =
                version.pullRequestPublisher().publish(issue, worktree, headCommit);
        IssueRecord built = record.withBuiltTree(treeHash).withPullRequest(published.pullRequest().number());
        if (!published.isOnGitHub()) {
            return StepResult.stay(
                    services.forwardCheckFailure().count(issue, record, built, headCommit, treeHash));
        }
        return StepResult.endWith(built.withoutFailedForwardChecks(), "passed");
    }

    /** The build passed for this tree and only the forward failed, so the build does not run again. */
    private static boolean isBuiltWithALostForward(IssueRecord record, String treeHash) {
        return record.failedForwardTree().equals(Optional.of(treeHash))
                && record.builtTreeHash().equals(Optional.of(treeHash));
    }
}
