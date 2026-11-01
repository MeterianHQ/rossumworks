package com.meterian.factory.workflow;

import com.meterian.factory.git.Git;
import com.meterian.factory.github.GitHubClient;
import com.meterian.factory.github.Issue;
import com.meterian.factory.github.PullRequest;
import com.meterian.factory.merge.PushedCommitCheck;
import java.nio.file.Path;

/** Pushes the built commit through the gatekeeper and opens the pull request, or finds the open one. */
final class PullRequestPublisher {

    private final GitHubClient gitHub;
    private final Git git;
    private final String mergeTargetBranch;

    PullRequestPublisher(GitHubClient gitHub, Git git, String mergeTargetBranch) {
        this.gitHub = gitHub;
        this.git = git;
        this.mergeTargetBranch = mergeTargetBranch;
    }

    /** Throws a GitHubException when GitHub does not have the pushed commit, so the step runs again. */
    PullRequest publish(Issue issue, Path worktree, String headCommit) {
        String branch = WorkspaceLayout.branch(issue.number());
        git.pushCommit(worktree, IssueWorkspace.GATEKEEPER_REMOTE, headCommit, branch);
        PullRequest pullRequest = gitHub.openPullRequest(branch).orElseGet(() -> createPullRequest(issue, branch));
        PushedCommitCheck.verifyGitHubHas(gitHub, pullRequest.number(), headCommit);
        return pullRequest;
    }

    private PullRequest createPullRequest(Issue issue, String branch) {
        String body = "Fixes #" + issue.number()
                + "\n\nThe factory made this change for the issue #" + issue.number() + ".";
        return gitHub.createPullRequest(branch, mergeTargetBranch, ChangeTitle.describe(issue), body);
    }
}
