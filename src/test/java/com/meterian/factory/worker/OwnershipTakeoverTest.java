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

        IssueRecord record = takeOver();

        assertThat(record.handOver()).contains(HandOver.CONTINUES_PUSHED_BRANCH);
        assertThat(record.pullRequestNumber()).contains(pullRequestNumber);
        assertThat(record.status()).isEqualTo(IssueStatus.QUEUED);
        assertThat(record.phase()).isEqualTo(Phase.PREPARE);
        assertThat(record.notes()).contains("The factory of factory-bot continues the work of an earlier owner.");
    }

    @Test
    void takeOverWithoutBranchStartsAgain() {
        IssueRecord record = takeOver();

        assertThat(record.handOver()).isEmpty();
        assertThat(record.pullRequestNumber()).isEmpty();
        assertThat(record.phase()).isEqualTo(Phase.PREPARE);
    }

    @Test
    void takeOverKeepsABlock() {
        addRecordOfAlice(IssueRecord.queued(ISSUE_NUMBER).withStatus(IssueStatus.BLOCKED)
                .withWaitReason(WaitReason.BLOCKED_BY_OWNER));

        IssueRecord record = takeOver();

        assertThat(record.status()).isEqualTo(IssueStatus.BLOCKED);
        assertThat(record.waitReason()).contains(WaitReason.BLOCKED_BY_OWNER);
    }

    @Test
    void takeOverKeepsAWaitForApproval() {
        addRecordOfAlice(IssueRecord.queued(ISSUE_NUMBER).withStatus(IssueStatus.WAITS_FOR_YOU)
                .withWaitReason(WaitReason.MERGE_NEEDS_APPROVAL));

        IssueRecord record = takeOver();

        assertThat(record.status()).isEqualTo(IssueStatus.WAITS_FOR_YOU);
        assertThat(record.waitReason()).contains(WaitReason.MERGE_NEEDS_APPROVAL);
    }

    @Test
    void takeOverIgnoresThePullRequestNumberOfAnotherAuthor() {
        addRecordOfAlice(IssueRecord.queued(ISSUE_NUMBER).withPullRequest(99));

        IssueRecord record = takeOver();

        assertThat(record.pullRequestNumber()).isEmpty();
    }

    @Test
    void takeOverEditsTheStatusCommentOfTheOwnRecord() {
        long commentId = gitHub.createComment(ISSUE_NUMBER, StatusComment.render(IssueRecord.queued(ISSUE_NUMBER), 3));
        IssueRecord own = IssueRecord.queued(ISSUE_NUMBER).withStatusComment(commentId);

        IssueRecord record = takeover.takeOver(issue, gitHub.commentsOf(ISSUE_NUMBER), Optional.of(own));

        assertThat(record.statusCommentId()).contains(commentId);
        assertThat(gitHub.commentsOf(ISSUE_NUMBER)).hasSize(1);
    }

    private void addRecordOfAlice(IssueRecord record) {
        gitHub.addCommentBy(ISSUE_NUMBER, "alice", StatusComment.render(record, 3));
    }

    private IssueRecord takeOver() {
        return takeover.takeOver(issue, gitHub.commentsOf(ISSUE_NUMBER), Optional.empty());
    }
}
