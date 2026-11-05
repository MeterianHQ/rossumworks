package com.meterian.factory.workflow;

import com.meterian.factory.git.Git;
import com.meterian.factory.github.GitHubClient;
import com.meterian.factory.github.Issue;
import com.meterian.factory.github.PullRequest;
import com.meterian.factory.merge.PushedCommitCheck;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/** Pushes the built commit through the gatekeeper, opens or finds the pull request, and checks GitHub. */
final class PullRequestPublisher {

    private final GitHubClient gitHub;
    private final Git git;
    private final String mergeTargetBranch;
    private final PushedCommitCheck pushedCommitCheck;

    PullRequestPublisher(GitHubClient gitHub, Git git, String mergeTargetBranch, Path gatekeeperDirectory) {
        this.gitHub = gitHub;
        this.git = git;
        this.mergeTargetBranch = mergeTargetBranch;
        this.pushedCommitCheck = new PushedCommitCheck(git, gitHub, gatekeeperDirectory);
    }

    /** The pull request, and if GitHub shows the pushed commit as its head. A lost forward has no new pull request. */
    record PublishedBranch(Optional<PullRequest> pullRequest, boolean isOnGitHub) {
    }

    PublishedBranch publish(Issue issue, Path worktree, String headCommit) {
        String branch = WorkspaceLayout.branch(issue.number());
        git.pushCommit(worktree, IssueWorkspace.GATEKEEPER_REMOTE, headCommit, branch);
        Optional<PullRequest> open = gitHub.openPullRequest(branch);
        if (open.isEmpty() && !pushedCommitCheck.isBranchOnGitHub(headCommit, branch)) {
            return new PublishedBranch(Optional.empty(), false);
        }
        PullRequest pullRequest = open
                .map(existing -> updateBody(existing, issue, worktree))
                .orElseGet(() -> createPullRequest(issue, branch, worktree));
        boolean isOnGitHub = pushedCommitCheck.isOnGitHub(pullRequest.number(), headCommit, branch);
        return new PublishedBranch(Optional.of(pullRequest), isOnGitHub);
    }

    /** Writes the notes of the last review round into the body of the open pull request, when the body changes. */
    void publishReviewNotes(Issue issue, List<ReviewNote> notes) {
        gitHub.openPullRequest(WorkspaceLayout.branch(issue.number())).ifPresent(pullRequest -> {
            String body = PullRequestDescription.withReviewNotes(pullRequest.body(), notes);
            if (!body.equals(pullRequest.body())) {
                gitHub.editPullRequestBody(pullRequest.number(), body);
            }
        });
    }

    private PullRequest createPullRequest(Issue issue, String branch, Path worktree) {
        String body = PullRequestDescription.bodyFor(issue.number(), worktree);
        return gitHub.createPullRequest(branch, mergeTargetBranch, ChangeTitle.describe(issue), body);
    }

    /** A missing or empty description keeps the last description in the body. */
    private PullRequest updateBody(PullRequest pullRequest, Issue issue, Path worktree) {
        String body = PullRequestDescription.bodyIfDescribed(issue.number(), worktree).orElse(pullRequest.body());
        if (pullRequest.body().equals(body)) {
            return pullRequest;
        }
        gitHub.editPullRequestBody(pullRequest.number(), body);
        return new PullRequest(pullRequest.number(), pullRequest.url(), pullRequest.headBranch(), body);
    }
}
