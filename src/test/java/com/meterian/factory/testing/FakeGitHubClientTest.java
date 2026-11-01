package com.meterian.factory.testing;

import static org.assertj.core.api.Assertions.assertThat;

import com.meterian.factory.github.MergeOutcome;
import com.meterian.factory.github.PullRequest;
import com.meterian.factory.state.IssueStatus;
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
    void addsNoLabeledEventForALabelThatIsAlreadyOnTheIssue() {
        github.addIssue(4, "T", "B");
        github.addLabelBy(4, "factory:approve", "owner");

        github.addLabelBy(4, "factory:approve", "someone-else");
        github.addLabel(4, "factory:approve");

        assertThat(github.lastLabelActor(4, "factory:approve")).contains("owner");
    }

    @Test
    void setsTheStatusAsALabel() {
        github.addIssue(4, "T", "B");
        github.addLabelBy(4, "factory:status:queued", "factory-bot");
        github.addLabelBy(4, "bug", "owner");

        github.setStatus(github.issue(4), IssueStatus.IN_REVIEW);

        assertThat(github.labelsOf(4)).containsExactlyInAnyOrder("factory:status:in-review", "bug");
        assertThat(github.statusOf(4)).contains(IssueStatus.IN_REVIEW);
    }

    @Test
    void keepsOnlyOneStatusLabel() {
        github.addIssue(4, "T", "B");
        github.addLabelBy(4, "factory:status:queued", "factory-bot");
        github.addLabelBy(4, "factory:status:blocked", "factory-bot");

        github.setStatus(github.issue(4), IssueStatus.DONE);

        assertThat(github.statusLabelsOf(4)).containsExactly("factory:status:done");
    }

    @Test
    void addsNothingWhenTheStatusIsAlreadySet() {
        github.addIssue(4, "T", "B");
        github.addLabelBy(4, "factory:status:done", "owner");

        github.setStatus(github.issue(4), IssueStatus.DONE);

        assertThat(github.lastLabelActor(4, "factory:status:done")).contains("owner");
    }

    @Test
    void givesNoStatusWhenThereIsNoStatusLabel() {
        github.addIssue(4, "T", "B");

        assertThat(github.statusOf(4)).isEmpty();
    }

    @Test
    void givesNoStatusWhenThereAreTwoStatusLabels() {
        github.addIssue(4, "T", "B");
        github.addLabelBy(4, "factory:status:queued", "owner");
        github.addLabelBy(4, "factory:status:done", "owner");

        assertThat(github.statusOf(4)).isEmpty();
        assertThat(github.statusLabelsOf(4)).containsExactlyInAnyOrder("factory:status:queued", "factory:status:done");
    }
}
