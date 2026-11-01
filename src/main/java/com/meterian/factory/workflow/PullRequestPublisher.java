package com.meterian.factory.workflow;

import com.meterian.factory.git.Git;
import com.meterian.factory.github.GitHubClient;
import com.meterian.factory.github.Issue;
import com.meterian.factory.github.PullRequest;
import com.meterian.factory.merge.PushedCommitCheck;
import java.nio.file.Path;

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

    /** The pull request, and if GitHub shows the pushed commit as its head. */
    record PublishedBranch(PullRequest pullRequest, boolean isOnGitHub) {
    }

    PublishedBranch publish(Issue issue, Path worktree, String headCommit) {
        String branch = WorkspaceLayout.branch(issue.number());
        git.pushCommit(worktree, IssueWorkspace.GATEKEEPER_REMOTE, headCommit, branch);
        String body = PullRequestDescription.bodyFor(issue.number(), worktree);
        PullRequest pullRequest = gitHub.openPullRequest(branch)
                .map(existing -> updateBody(existing, body))
                .orElseGet(() -> createPullRequest(issue, branch, body));
        return new PublishedBranch(pullRequest, pushedCommitCheck.isOnGitHub(pullRequest.number(), headCommit, branch));
    }

    private PullRequest createPullRequest(Issue issue, String branch, String body) {
        return gitHub.createPullRequest(branch, mergeTargetBranch, ChangeTitle.describe(issue), body);
    }

    private PullRequest updateBody(PullRequest pullRequest, String body) {
        if (pullRequest.body().equals(body)) {
            return pullRequest;
        }
        gitHub.editPullRequestBody(pullRequest.number(), body);
        return new PullRequest(pullRequest.number(), pullRequest.url(), pullRequest.headBranch(), body);
    }
}
