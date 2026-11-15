package com.meterian.factory.worker;

import static org.assertj.core.api.Assertions.assertThat;

import com.meterian.factory.board.BoardUpdates;
import com.meterian.factory.github.Issue;
import com.meterian.factory.github.IssueComment;
import com.meterian.factory.state.HandOver;
import com.meterian.factory.state.IssueRecord;
import com.meterian.factory.state.Phase;
import com.meterian.factory.state.StatusComment;
import com.meterian.factory.testing.FakeGitHubClient;
import com.meterian.factory.workflow.StatusPublisher;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OwnershipReleaseTest {

    private static final String WORKER_LOGIN = "factory-bot";
    private static final int ISSUE_NUMBER = 7;
    private static final String STOP_COMMENT = "The factory of `factory-bot` stops work on this issue,"
            + " because the issue has a new assignee. The branch and the pull request stay."
            + " The factory of the new assignee continues the work.";

    private FakeGitHubClient gitHub;
    private OwnershipRelease release;
    private Issue issue;

    @BeforeEach
    void createRelease() {
        gitHub = new FakeGitHubClient();
        gitHub.setAuthenticatedLogin(WORKER_LOGIN);
        gitHub.addIssue(ISSUE_NUMBER, "Title", "Body");
        issue = gitHub.issue(ISSUE_NUMBER);
        StatusPublisher statusPublisher = new StatusPublisher(gitHub, WORKER_LOGIN, 3, BoardUpdates.IGNORED);
        release = new OwnershipRelease(gitHub, statusPublisher, WORKER_LOGIN);
    }

    @Test
    void releaseMarksTheRecordAndWritesTheStopComment() {
        gitHub.createComment(ISSUE_NUMBER, StatusComment.render(IssueRecord.queued(ISSUE_NUMBER), 3));

        release.release(issue);

        assertThat(findOwnRecord().orElseThrow().handOver()).contains(HandOver.RELEASED);
        assertThat(findOwnRecord().orElseThrow().notes())
                .contains("The factory of factory-bot stops work: the issue has a new assignee.");
        assertThat(findStopComments()).hasSize(1);
    }

    @Test
    void releaseWritesTheStopCommentOnce() {
        gitHub.createComment(ISSUE_NUMBER, StatusComment.render(IssueRecord.queued(ISSUE_NUMBER), 3));

        release.release(issue);
        release.release(issue);

        assertThat(findStopComments()).hasSize(1);
    }

    @Test
    void releaseWithoutOwnRecordDoesNothing() {
        release.release(issue);

        assertThat(gitHub.commentsOf(ISSUE_NUMBER)).isEmpty();
    }

    @Test
    void releaseLeavesADoneRecord() {
        IssueRecord done = IssueRecord.queued(ISSUE_NUMBER).withPhase(Phase.DONE);
        gitHub.createComment(ISSUE_NUMBER, StatusComment.render(done, 3));

        release.release(issue);

        assertThat(findOwnRecord().orElseThrow().handOver()).isEmpty();
        assertThat(findStopComments()).isEmpty();
    }

    private Optional<IssueRecord> findOwnRecord() {
        return StatusComment.findRecord(gitHub.commentsOf(ISSUE_NUMBER), WORKER_LOGIN);
    }

    private List<IssueComment> findStopComments() {
        return gitHub.commentsOf(ISSUE_NUMBER).stream()
                .filter(comment -> comment.body().equals(STOP_COMMENT))
                .toList();
    }
}
