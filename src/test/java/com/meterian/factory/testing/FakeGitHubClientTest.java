package com.meterian.factory.testing;

import static org.assertj.core.api.Assertions.assertThat;

import com.meterian.factory.github.MergeOutcome;
import com.meterian.factory.github.PullRequest;
import com.meterian.factory.state.IssueStatus;
import java.util.Set;
import org.junit.jupiter.api.Test;

class FakeGitHubClientTest {

    private final FakeGitHubClient github = new FakeGitHubClient();

    @Test
    void assignByRecordsTheAssigneeAndTheEvent() {
        github.addIssue(1, "T", "B");

        github.assignBy(1, "alice");

        assertThat(github.issue(1).assignees()).isEqualTo(Set.of("alice"));
        assertThat(github.assignmentEvents(1)).hasSize(1);
        assertThat(github.assignmentEvents(1).get(0).assignee()).isEqualTo("alice");
    }

    @Test
    void ignoredAssigneeLeavesTheIssueUnassigned() {
        github.addIssue(1, "T", "B");
        github.ignoreTheAssignees();

        github.addAssignee(1, "worker");

        assertThat(github.assigneesOf(1)).isEmpty();
    }

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
    void setsTheStatusAsALabel() {
        github.addIssue(4, "T", "B");
        github.addLabel(4, "factory:status:queued");
        github.addLabel(4, "bug");

        github.setStatus(github.issue(4), IssueStatus.IN_REVIEW);

        assertThat(github.labelsOf(4)).containsExactlyInAnyOrder("factory:status:in-review", "bug");
        assertThat(github.statusOf(4)).contains(IssueStatus.IN_REVIEW);
    }

    @Test
    void keepsOnlyOneStatusLabel() {
        github.addIssue(4, "T", "B");
        github.addLabel(4, "factory:status:queued");
        github.addLabel(4, "factory:status:blocked");

        github.setStatus(github.issue(4), IssueStatus.DONE);

        assertThat(github.statusLabelsOf(4)).containsExactly("factory:status:done");
    }

    @Test
    void addsNothingWhenTheStatusIsAlreadySet() {
        github.addIssue(4, "T", "B");
        github.addLabel(4, "factory:status:done");

        github.setStatus(github.issue(4), IssueStatus.DONE);

        assertThat(github.statusLabelsOf(4)).containsExactly("factory:status:done");
    }

    @Test
    void givesNoStatusWhenThereIsNoStatusLabel() {
        github.addIssue(4, "T", "B");

        assertThat(github.statusOf(4)).isEmpty();
    }

    @Test
    void givesNoStatusWhenThereAreTwoStatusLabels() {
        github.addIssue(4, "T", "B");
        github.addLabel(4, "factory:status:queued");
        github.addLabel(4, "factory:status:done");

        assertThat(github.statusOf(4)).isEmpty();
        assertThat(github.statusLabelsOf(4)).containsExactlyInAnyOrder("factory:status:queued", "factory:status:done");
    }

    @Test
    void editsTheBodyOfAPullRequest() {
        PullRequest pullRequest = github.createPullRequest("factory/issue-7", "main", "T", "B");

        github.editPullRequestBody(pullRequest.number(), "New");

        assertThat(github.openPullRequest("factory/issue-7").orElseThrow().body()).isEqualTo("New");
        assertThat(github.editedPullRequestBodies()).containsExactly("New");
    }
}
