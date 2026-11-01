package com.meterian.factory.state;

import static org.assertj.core.api.Assertions.assertThat;

import com.meterian.factory.github.IssueComment;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class StatusCommentTest {

    private static final String WORKER = "factory-bot";

    private final IssueRecord record = IssueRecord.queued(7)
            .withStatus(IssueStatus.IN_REVIEW)
            .withPhase(Phase.REVIEW)
            .withReview(ReviewKind.CORRECTNESS, Verdict.APPROVE)
            .withFixLoop()
            .withRisk(RiskLevel.LOW, true)
            .withReviewedTree("abc", "def")
            .withPullRequest(12)
            .withStatusComment(99L)
            .withNote("A note -- with dashes");

    @Test
    void renderedRecordReadsBackEqual() {
        IssueComment comment = new IssueComment(1, WORKER, StatusComment.render(record));

        assertThat(StatusComment.findRecord(List.of(comment), WORKER)).contains(record);
    }

    @Test
    void ignoresARecordFromAnotherAccount() {
        IssueComment comment = new IssueComment(1, "mallory", StatusComment.render(record));

        assertThat(StatusComment.findRecord(List.of(comment), WORKER)).isEmpty();
    }

    @Test
    void takesTheNewestRecordOfTheWorker() {
        IssueRecord newer = record.withPhase(Phase.MERGE);
        List<IssueComment> comments = List.of(
                new IssueComment(5, WORKER, StatusComment.render(newer)),
                new IssueComment(2, WORKER, StatusComment.render(record)));

        assertThat(StatusComment.findRecord(comments, WORKER)).contains(newer);
    }

    @Test
    void ignoresABrokenRecord() {
        String body = "text\n\n<!-- factory-record\n{ not json\n-->";

        Optional<IssueRecord> found = StatusComment.findRecord(List.of(new IssueComment(1, WORKER, body)), WORKER);

        assertThat(found).isEmpty();
    }

    @Test
    void showsTheReasonForWaitsForYou() {
        IssueRecord waiting = record.withStatus(IssueStatus.WAITS_FOR_YOU)
                .withWaitReason(WaitReason.MERGE_NEEDS_APPROVAL);

        assertThat(StatusComment.render(waiting)).contains("The merge needs your approval.");
    }

    @Test
    void showsEachNoteOnItsOwnLine() {
        String body = StatusComment.render(IssueRecord.queued(1).withNote("a").withNote("b"));

        assertThat(body).contains("a\n").contains("b\n");
    }
}
