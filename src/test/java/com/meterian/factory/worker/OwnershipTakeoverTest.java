package com.meterian.factory.worker;

import static org.assertj.core.api.Assertions.assertThat;

import com.meterian.factory.board.BoardUpdates;
import com.meterian.factory.github.Issue;
import com.meterian.factory.state.HandOver;
import com.meterian.factory.state.IssueRecord;
import com.meterian.factory.state.IssueStatus;
import com.meterian.factory.state.Phase;
import com.meterian.factory.state.StatusComment;
import com.meterian.factory.state.WaitReason;
import com.meterian.factory.testing.FakeGitHubClient;
import com.meterian.factory.workflow.StatusPublisher;
import com.meterian.factory.workflow.WorkspaceLayout;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OwnershipTakeoverTest {

    private static final String WORKER_LOGIN = "factory-bot";
    private static final int ISSUE_NUMBER = 7;
    private static final String BRANCH = WorkspaceLayout.branch(ISSUE_NUMBER);
    private static final String OPEN_QUESTION_NOTE =
            "The earlier owner had an open question. The question stays on the machine of the earlier owner.";

    private FakeGitHubClient gitHub;
    private OwnershipTakeover takeover;
    private Issue issue;

    @BeforeEach
    void createTakeover() {
        gitHub = new FakeGitHubClient();
        gitHub.setAuthenticatedLogin(WORKER_LOGIN);
        gitHub.addIssue(ISSUE_NUMBER, "Title", "Body");
        issue = gitHub.issue(ISSUE_NUMBER);
        StatusPublisher statusPublisher = new StatusPublisher(gitHub, WORKER_LOGIN, 3, BoardUpdates.IGNORED);
        takeover = new OwnershipTakeover(gitHub, statusPublisher, WORKER_LOGIN);
    }

    @Test
    void takeOverContinuesThePushedBranch() {
        gitHub.addBranchHead(BRANCH, "abc123");
        int pullRequestNumber = gitHub.createPullRequest(BRANCH, "main", "Title", "Body").number();

        IssueRecord record = takeOverWithoutEarlierRecord();

        assertThat(record.handOver()).contains(HandOver.CONTINUES_PUSHED_BRANCH);
        assertThat(record.pullRequestNumber()).contains(pullRequestNumber);
        assertThat(record.status()).isEqualTo(IssueStatus.QUEUED);
        assertThat(record.phase()).isEqualTo(Phase.PREPARE);
        assertThat(record.notes()).contains("The factory of factory-bot continues the work of an earlier owner.");
    }

    @Test
    void takeOverWithoutBranchStartsAgain() {
        IssueRecord record = takeOverWithoutEarlierRecord();

        assertThat(record.handOver()).isEmpty();
        assertThat(record.pullRequestNumber()).isEmpty();
        assertThat(record.phase()).isEqualTo(Phase.PREPARE);
    }

    @Test
    void takeOverKeepsABlock() {
        IssueRecord record = takeOverFrom(IssueRecord.queued(ISSUE_NUMBER).withStatus(IssueStatus.BLOCKED)
                .withWaitReason(WaitReason.BLOCKED_BY_OWNER));

        assertThat(record.status()).isEqualTo(IssueStatus.BLOCKED);
        assertThat(record.waitReason()).contains(WaitReason.BLOCKED_BY_OWNER);
    }

    @Test
    void takeOverKeepsAWaitForApprovalWithThePushedBranch() {
        gitHub.addBranchHead(BRANCH, "abc123");

        IssueRecord record = takeOverFrom(IssueRecord.queued(ISSUE_NUMBER).withStatus(IssueStatus.WAITS_FOR_YOU)
                .withWaitReason(WaitReason.MERGE_NEEDS_APPROVAL));

        assertThat(record.handOver()).contains(HandOver.CONTINUES_PUSHED_BRANCH);
        assertThat(record.status()).isEqualTo(IssueStatus.WAITS_FOR_YOU);
        assertThat(record.waitReason()).contains(WaitReason.MERGE_NEEDS_APPROVAL);
    }

    @Test
    void takeOverDropsAWaitForApprovalWithoutBranch() {
        IssueRecord record = takeOverFrom(IssueRecord.queued(ISSUE_NUMBER).withStatus(IssueStatus.WAITS_FOR_YOU)
                .withWaitReason(WaitReason.MERGE_NEEDS_APPROVAL));

        assertThat(record.status()).isEqualTo(IssueStatus.QUEUED);
        assertThat(record.phase()).isEqualTo(Phase.PREPARE);
        assertThat(record.waitReason()).isEmpty();
    }

    @Test
    void takeOverKeepsAWaitForAChangedIssueText() {
        IssueRecord record = takeOverFrom(IssueRecord.queued(ISSUE_NUMBER).withStatus(IssueStatus.WAITS_FOR_YOU)
                .withWaitReason(WaitReason.ISSUE_TEXT_CHANGED));

        assertThat(record.status()).isEqualTo(IssueStatus.WAITS_FOR_YOU);
        assertThat(record.waitReason()).contains(WaitReason.ISSUE_TEXT_CHANGED);
    }

    @Test
    void takeOverDoesNotKeepAQuestion() {
        gitHub.addBranchHead(BRANCH, "abc123");

        IssueRecord record = takeOverFrom(IssueRecord.queued(ISSUE_NUMBER).withStatus(IssueStatus.WAITS_FOR_YOU)
                .withWaitReason(WaitReason.QUESTION));

        assertThat(record.status()).isEqualTo(IssueStatus.QUEUED);
        assertThat(record.waitReason()).isEmpty();
        assertThat(record.handOver()).contains(HandOver.CONTINUES_PUSHED_BRANCH);
        assertThat(record.notes()).contains(OPEN_QUESTION_NOTE);
    }

    @Test
    void takeOverDoesNotKeepALiveConversation() {
        IssueRecord record = takeOverFrom(IssueRecord.queued(ISSUE_NUMBER).withStatus(IssueStatus.WAITS_FOR_YOU)
                .withWaitReason(WaitReason.LIVE_CONVERSATION));

        assertThat(record.status()).isEqualTo(IssueStatus.QUEUED);
        assertThat(record.phase()).isEqualTo(Phase.PREPARE);
        assertThat(record.waitReason()).isEmpty();
        assertThat(record.notes()).contains(OPEN_QUESTION_NOTE);
    }

    @Test
    void takeOverKeepsTheBlockOfAnOwnReleasedRecord() {
        IssueRecord record = takeOverFrom(IssueRecord.queued(ISSUE_NUMBER).withStatus(IssueStatus.BLOCKED)
                .withWaitReason(WaitReason.FAILURE).withHandOver(HandOver.RELEASED));

        assertThat(record.status()).isEqualTo(IssueStatus.BLOCKED);
        assertThat(record.waitReason()).contains(WaitReason.FAILURE);
    }

    @Test
    void takeOverIgnoresThePullRequestNumberOfTheEarlierRecord() {
        IssueRecord record = takeOverFrom(IssueRecord.queued(ISSUE_NUMBER).withPullRequest(99));

        assertThat(record.pullRequestNumber()).isEmpty();
    }

    @Test
    void takeOverWritesANewStatusComment() {
        IssueRecord own = IssueRecord.queued(ISSUE_NUMBER);
        long ownCommentId = gitHub.createComment(ISSUE_NUMBER, StatusComment.render(own, 3));

        IssueRecord record = takeOverFrom(own.withStatusComment(ownCommentId));

        assertThat(record.statusCommentId()).isPresent().get().isNotEqualTo(ownCommentId);
        assertThat(gitHub.commentsOf(ISSUE_NUMBER)).hasSize(2);
        assertThat(StatusComment.findRecord(gitHub.commentsOf(ISSUE_NUMBER), WORKER_LOGIN)).contains(record);
    }

    private IssueRecord takeOverFrom(IssueRecord earlierRecord) {
        return takeover.takeOver(issue, Optional.of(earlierRecord));
    }

    private IssueRecord takeOverWithoutEarlierRecord() {
        return takeover.takeOver(issue, Optional.empty());
    }
}
