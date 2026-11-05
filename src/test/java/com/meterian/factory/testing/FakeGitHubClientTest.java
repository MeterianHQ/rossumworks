package com.meterian.factory.testing;

import static org.assertj.core.api.Assertions.assertThat;

import com.meterian.factory.github.MergeOutcome;
import com.meterian.factory.github.PullRequest;
import org.junit.jupiter.api.Test;

class FakeGitHubClientTest {

    private final FakeGitHubClient github = new FakeGitHubClient();

    @Test
    void closesThePullRequestAfterAMerge() {
        PullRequest pullRequest = github.createPullRequest("factory/issue-7", "main", "T", "B");

        assertThat(github.squashMerge(pullRequest.number(), "sha")).isEqualTo(MergeOutcome.MERGED);

        assertThat(github.openPullRequest("factory/issue-7")).isEmpty();
        assertThat(github.pullRequests()).containsExactly(pullRequest);
    }

    @Test
    void keepsThePullRequestOpenWhenItIsNotMergeable() {
        PullRequest pullRequest = github.createPullRequest("factory/issue-7", "main", "T", "B");
        github.queueMergeOutcomes(MergeOutcome.NOT_MERGEABLE);

        github.squashMerge(pullRequest.number(), "sha");

        assertThat(github.openPullRequest("factory/issue-7")).contains(pullRequest);
    }

    @Test
    void setsTheColumnAsALabel() {
        github.addIssue(4, "T", "B");
        github.addLabel(4, "factory:is:queued");
        github.addLabel(4, "bug");

        github.setColumn(github.issue(4), "in-review");

        assertThat(github.labelsOf(4)).containsExactlyInAnyOrder("factory:is:in-review", "bug");
        assertThat(github.columnOf(4)).contains("in-review");
    }

    @Test
    void removesTheOldStatusLabelAndCreatesTheLabelOfANewColumnOnce() {
        github.addIssue(4, "T", "B");
        github.addLabel(4, "factory:status:queued");

        github.setColumn(github.issue(4), "needs-me");
        github.setColumn(github.issue(4), "needs-me");

        assertThat(github.labelsOf(4)).containsExactly("factory:is:needs-me");
        assertThat(github.createdLabels()).containsExactly("factory:is:needs-me");
    }

    @Test
    void keepsOnlyOneColumnLabel() {
        github.addIssue(4, "T", "B");
        github.addLabel(4, "factory:is:queued");
        github.addLabel(4, "factory:is:blocked");

        github.setColumn(github.issue(4), "done");

        assertThat(github.columnLabelsOf(4)).containsExactly("factory:is:done");
    }

    @Test
    void addsNothingWhenTheColumnIsAlreadySet() {
        github.addIssue(4, "T", "B");
        github.addLabel(4, "factory:is:done");

        github.setColumn(github.issue(4), "done");

        assertThat(github.columnLabelsOf(4)).containsExactly("factory:is:done");
    }

    @Test
    void givesNoColumnWhenThereIsNoColumnLabel() {
        github.addIssue(4, "T", "B");

        assertThat(github.columnOf(4)).isEmpty();
    }

    @Test
    void givesNoColumnWhenThereAreTwoColumnLabels() {
        github.addIssue(4, "T", "B");
        github.addLabel(4, "factory:is:queued");
        github.addLabel(4, "factory:is:done");

        assertThat(github.columnOf(4)).isEmpty();
        assertThat(github.columnLabelsOf(4)).containsExactlyInAnyOrder("factory:is:queued", "factory:is:done");
    }

    @Test
    void editsTheBodyOfAPullRequest() {
        PullRequest pullRequest = github.createPullRequest("factory/issue-7", "main", "T", "B");

        github.editPullRequestBody(pullRequest.number(), "New");

        assertThat(github.openPullRequest("factory/issue-7").orElseThrow().body()).isEqualTo("New");
        assertThat(github.editedPullRequestBodies()).containsExactly("New");
    }
}
