package com.meterian.factory.workflow;

import com.meterian.factory.build.BuildOutcome;
import com.meterian.factory.github.Issue;
import com.meterian.factory.prompt.Finding;
import com.meterian.factory.state.IssueRecord;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

final class BuildAction implements Action {

    private final ActionServices services;
    private final VersionServices version;

    BuildAction(ActionServices services, VersionServices version) {
        this.services = services;
        this.version = version;
    }

    @Override
    public ActionResult run(Issue issue, IssueRecord record, Path worktree) {
        if (services.git().hasUncommittedChanges(worktree)) {
            return ActionResult.block(record, "The worktree has changes that are not committed.");
        }
        String headCommit = services.git().headCommit(worktree);
        String treeHash = services.git().treeHashOf(worktree, headCommit);
        if (!isBuiltWithALostForward(record, treeHash)) {
            BuildOutcome outcome = services.buildRunner().run(worktree, version.build());
            if (outcome.stopped()) {
                return ActionResult.stay(record);
            }
            if (services.git().hasUncommittedChanges(worktree)
                    || !services.git().headCommit(worktree).equals(headCommit)) {
                return ActionResult.block(record, "The build changed the files.");
            }
            if (!outcome.passed()) {
                Finding failure = new Finding("The build failed", outcome.outputTail());
                return ActionResult.endWithFindings(record, "failed", List.of(failure));
            }
        }
        withdrawTheApprovalAsTheOwner(record.issueNumber());
        PullRequestPublisher.PublishedBranch published =
                version.pullRequestPublisher().publish(issue, worktree, headCommit);
        IssueRecord withTree = record.withBuiltTree(treeHash).withoutApprovedTree();
        IssueRecord built = published.pullRequest()
                .map(pullRequest -> withTree.withPullRequest(pullRequest.number()))
                .orElse(withTree);
        if (!published.isOnGitHub()) {
            return services.forwardCheckFailure().count(issue, record, built, headCommit, treeHash);
        }
        return ActionResult.endWith(built.withoutFailedForwardChecks(), "passed");
    }

    /** The approval label can be for a new owner, so a factory that lost the issue does not remove it. */
    private void withdrawTheApprovalAsTheOwner(int issueNumber) {
        services.ownershipGuard().verifyStillOwner(issueNumber);
        services.ownerApproval().withdraw(issueNumber);
    }

    /** The build passed for this tree and only the forward failed, so the build does not run again. */
    private static boolean isBuiltWithALostForward(IssueRecord record, String treeHash) {
        return record.failedForwardTree().equals(Optional.of(treeHash))
                && record.builtTreeHash().equals(Optional.of(treeHash));
    }
}
