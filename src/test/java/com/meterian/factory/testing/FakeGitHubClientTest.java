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
    void addsNoLabeledEventForALabelThatIsAlreadyOnTheIssue() {
        github.addIssue(4, "T", "B");
        github.addLabelBy(4, "factory:approve", "owner");

        github.addLabelBy(4, "factory:approve", "someone-else");
        github.addLabel(4, "factory:approve");

        assertThat(github.lastLabelActor(4, "factory:approve")).contains("owner");
    }
}
