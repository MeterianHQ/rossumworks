package com.meterian.factory.worker;

import static org.assertj.core.api.Assertions.assertThat;

import com.meterian.factory.state.IssueRecord;
import com.meterian.factory.state.StatusComment;
import com.meterian.factory.testing.FakeGitHubClient;
import com.meterian.factory.testing.RecordStates;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class EarlierOwnerRecordTest {

    private static final String WORKER_LOGIN = "factory-bot";
    private static final int ISSUE_NUMBER = 7;

    private final FakeGitHubClient gitHub = new FakeGitHubClient();
    private final EarlierOwnerRecord earlierOwnerRecord = new EarlierOwnerRecord(gitHub, WORKER_LOGIN);
    private final IssueRecord record = RecordStates.queued(ISSUE_NUMBER);

    @BeforeEach
    void createIssue() {
        gitHub.setAuthenticatedLogin(WORKER_LOGIN);
        gitHub.addIssue(ISSUE_NUMBER, "Title", "Body");
    }

    @Test
    void findsTheNewestRecordOfAnAssignedAuthor() {
        assignEarlier("alice");
        assignEarlier("bob");
        addRecordBy("alice", RecordStates.move(record, "build"));
        addRecordBy("bob", RecordStates.move(record, "merge"));

        assertThat(findRecord()).contains(RecordStates.move(record, "merge"));
    }

    @Test
    void ignoresTheRecordOfAnAuthorWithoutAssignment() {
        assignEarlier("alice");
        addRecordBy("alice", RecordStates.move(record, "build"));
        addRecordBy("mallory", RecordStates.move(record, "blocked"));

        assertThat(findRecord()).contains(RecordStates.move(record, "build"));
    }

    @Test
    void comparesTheAuthorWithTheAssigneeWithoutCase() {
        assignEarlier("Alice");
        addRecordBy("alice", RecordStates.move(record, "build"));

        assertThat(findRecord()).contains(RecordStates.move(record, "build"));
    }

    @Test
    void ignoresARecordOfAnotherAuthorThatIsOlderThanTheOwnRecord() {
        assignEarlier("alice");
        addRecordBy("alice", RecordStates.move(record, "build"));
        addRecordBy(WORKER_LOGIN, record);

        assertThat(findRecord()).isEmpty();
    }

    @Test
    void findsARecordOfAnotherAuthorThatIsNewerThanTheOwnRecord() {
        assignEarlier("alice");
        addRecordBy(WORKER_LOGIN, record);
        addRecordBy("alice", RecordStates.move(record, "build"));

        assertThat(findRecord()).contains(RecordStates.move(record, "build"));
    }

    @Test
    void readsNoEventsWithoutARecordOfAnotherAuthor() {
        addRecordBy(WORKER_LOGIN, record);

        assertThat(findRecord()).isEmpty();
        assertThat(gitHub.assignmentEventReads()).isZero();
    }

    /** The account was an assignee of the issue once, and is no longer. */
    private void assignEarlier(String login) {
        gitHub.assignBy(ISSUE_NUMBER, login);
        gitHub.unassign(ISSUE_NUMBER, login);
    }

    private void addRecordBy(String author, IssueRecord authoredRecord) {
        gitHub.addCommentBy(ISSUE_NUMBER, author, StatusComment.render(authoredRecord, Map.of(), List.of()));
    }

    private Optional<IssueRecord> findRecord() {
        return earlierOwnerRecord.findNewerThanOwnRecord(ISSUE_NUMBER, gitHub.commentsOf(ISSUE_NUMBER));
    }
}
