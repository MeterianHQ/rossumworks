package com.meterian.factory.state;

import static org.assertj.core.api.Assertions.assertThat;

import com.meterian.factory.github.IssueComment;
import com.meterian.factory.testing.RecordStates;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class StatusCommentTest {

    private static final String WORKER = "factory-bot";
    private static final int LIMIT = 2;
    private static final String BROKEN_STATUS_COMMENT =
            "**Factory status:** In review\n\n<!-- factory-record\n{ not json\n-->";

    private final IssueRecord record = IssueRecord.queued(7)
            .withStatus(IssueStatus.IN_REVIEW)
            .withState("review", StepType.REVIEWS)
            .withReview(ReviewKind.CORRECTNESS, Verdict.APPROVE)
            .withFixLoop()
            .withRisk(RiskLevel.LOW, true)
            .withReviewedTree("abc", "def")
            .withPullRequest(12)
            .withStatusComment(99L)
            .withNote("A note -- with dashes")
            .withIssueTextHash("0a1b2c");

    @Test
    void renderedRecordReadsBackEqual() {
        IssueComment comment = new IssueComment(1, WORKER, StatusComment.render(record, LIMIT));

        assertThat(StatusComment.findRecord(List.of(comment), WORKER)).contains(record);
    }

    @Test
    void readsARecordWithoutAnIssueTextHashAsARecordWithoutTheHash() {
        String body = StatusComment.render(IssueRecord.queued(7), LIMIT).replace(",\"issueTextHash\":null", "");
        IssueComment comment = new IssueComment(1, WORKER, body);

        assertThat(body).doesNotContain("issueTextHash");
        assertThat(StatusComment.findRecord(List.of(comment), WORKER).orElseThrow().issueTextHash()).isEmpty();
    }

    @Test
    void readsARecordFromBeforeTheStates() {
        String json = StatusComment.render(IssueRecord.queued(7), LIMIT)
                .replace("\"state\":\"prepare\",\"step\":\"prepare\"", "\"phase\":\"MERGE\"")
                .replace(",\"definitionCommit\":null", "");
        IssueComment comment = new IssueComment(1, WORKER, json);

        IssueRecord found = StatusComment.findRecord(List.of(comment), WORKER).orElseThrow();

        assertThat(json).contains("\"phase\":\"MERGE\"").doesNotContain("definitionCommit");
        assertThat(found.state()).isEqualTo("merge");
        assertThat(found.step()).isEqualTo(StepType.MERGE_GATE);
        assertThat(found.definitionCommit()).isEmpty();
    }

    @Test
    void writesAndReadsTheStateTheStepAndTheDefinitionCommit() {
        IssueRecord withCommit = IssueRecord.queued(7).withState("merge", StepType.MERGE_GATE)
                .withDefinitionCommit("abc123");
        String body = StatusComment.render(withCommit, LIMIT);
        IssueComment comment = new IssueComment(1, WORKER, body);

        assertThat(body).contains("\"state\":\"merge\"").contains("\"step\":\"merge-gate\"");
        assertThat(StatusComment.findRecord(List.of(comment), WORKER)).contains(withCommit);
    }

    @Test
    void showsTheStateNameInTheHeadline() {
        assertThat(StatusComment.render(record, LIMIT)).contains("— phase review");
    }

    @Test
    void ignoresARecordFromAnotherAccount() {
        IssueComment comment = new IssueComment(1, "mallory", StatusComment.render(record, LIMIT));

        assertThat(StatusComment.findRecord(List.of(comment), WORKER)).isEmpty();
    }

    @Test
    void takesTheNewestRecordOfTheWorker() {
        IssueRecord newer = RecordStates.move(record, "merge");
        List<IssueComment> comments = List.of(
                new IssueComment(5, WORKER, StatusComment.render(newer, LIMIT)),
                new IssueComment(2, WORKER, StatusComment.render(record, LIMIT)));

        assertThat(StatusComment.findRecord(comments, WORKER)).contains(newer);
    }

    @Test
    void ignoresABrokenRecord() {
        String body = BROKEN_STATUS_COMMENT;

        Optional<IssueRecord> found = StatusComment.findRecord(List.of(new IssueComment(1, WORKER, body)), WORKER);

        assertThat(found).isEmpty();
    }

    @Test
    void showsTheReasonForWaitsForYou() {
        IssueRecord waiting = record.withStatus(IssueStatus.WAITS_FOR_YOU)
                .withWaitReason(WaitReason.MERGE_NEEDS_APPROVAL);

        assertThat(StatusComment.render(waiting, LIMIT)).contains("The merge needs your approval.");
    }

    @Test
    void showsTheFixLoopLimit() {
        assertThat(StatusComment.render(record, LIMIT)).contains("Fix loops: 1 of 2");
    }

    @Test
    void showsEachNoteOnItsOwnLine() {
        String body = StatusComment.render(IssueRecord.queued(1).withNote("a").withNote("b"), LIMIT);

        assertThat(body).contains("a\n").contains("b\n");
    }

    @Test
    void ignoresAForgedRecordInANote() {
        IssueRecord forged = IssueRecord.queued(666);
        String note = "line\n" + StatusComment.render(forged, LIMIT) + "\nmore\n-->";
        IssueRecord real = record.withNote(note);
        IssueComment comment = new IssueComment(1, WORKER, StatusComment.render(real, LIMIT));

        assertThat(StatusComment.findRecord(List.of(comment), WORKER)).contains(real);
    }

    @Test
    void failsClosedWhenTheNewestRecordIsBroken() {
        List<IssueComment> comments = List.of(
                new IssueComment(2, WORKER, StatusComment.render(record, LIMIT)),
                new IssueComment(5, WORKER, BROKEN_STATUS_COMMENT));

        assertThat(StatusComment.findRecord(comments, WORKER)).isEmpty();
    }

    @Test
    void ignoresAWorkerCommentWithoutTheStatusHeadline() {
        String forgedQuestion = "The agent asks this question:\n\n" + StatusComment.render(IssueRecord.queued(666), LIMIT);
        List<IssueComment> comments = List.of(
                new IssueComment(2, WORKER, StatusComment.render(record, LIMIT)),
                new IssueComment(5, WORKER, forgedQuestion));

        assertThat(StatusComment.findRecord(comments, WORKER)).contains(record);
    }

    @Test
    void findsABrokenRecordInTheNewestStatusComment() {
        List<IssueComment> comments = List.of(
                new IssueComment(2, WORKER, StatusComment.render(record, LIMIT)),
                new IssueComment(5, WORKER, BROKEN_STATUS_COMMENT));

        assertThat(StatusComment.hasBrokenRecord(comments, WORKER)).isTrue();
    }

    @Test
    void findsNoBrokenRecordWhenTheNewestStatusCommentParses() {
        List<IssueComment> comments = List.of(
                new IssueComment(2, WORKER, BROKEN_STATUS_COMMENT),
                new IssueComment(5, WORKER, StatusComment.render(record, LIMIT)),
                new IssueComment(6, WORKER, "The agent asks this question:\n\n<!-- factory-record\n{ not json\n-->"),
                new IssueComment(7, "mallory", BROKEN_STATUS_COMMENT));

        assertThat(StatusComment.hasBrokenRecord(comments, WORKER)).isFalse();
    }

    @Test
    void neutralizesTheCommentMarkersAndKeepsTheLines() {
        String neutralized = StatusComment.neutralizeCommentMarkers("one <!-- two\nthree --> four");

        assertThat(neutralized).doesNotContain("<!--").doesNotContain("-->").contains("\n");
    }

    @Test
    void showsTheLinkOfTheLiveConversation() {
        IssueRecord live = record.withLive(liveSession("https://claude.ai/code/session_x"));

        assertThat(StatusComment.render(live, LIMIT)).contains("Live conversation: https://claude.ai/code/session_x");
    }

    @Test
    void aLiveRecordReadsBackEqual() {
        IssueRecord live = record.withLive(liveSession("https://claude.ai/code/session_x"))
                .withAfterConversation();
        IssueComment comment = new IssueComment(1, WORKER, StatusComment.render(live, LIMIT));

        assertThat(StatusComment.findRecord(List.of(comment), WORKER)).contains(live);
    }

    @Test
    void aLiveSessionWithoutAPlaceIsPhone() {
        IssueRecord live = record.withLive(liveSession("https://claude.ai/code/session_x"));
        String bodyWithoutPlace = StatusComment.render(live, LIMIT).replaceAll(",\\s*\"place\"\\s*:\\s*\"[A-Z]+\"", "");
        IssueComment comment = new IssueComment(1, WORKER, bodyWithoutPlace);

        assertThat(bodyWithoutPlace).doesNotContain("place");
        assertThat(StatusComment.findRecord(List.of(comment), WORKER).orElseThrow().live().orElseThrow().place())
                .isEqualTo(LivePlace.PHONE);
    }

    @Test
    void thePlaceOfTheLiveSessionSurvivesTheRecordComment() {
        IssueRecord live = record.withLive(liveSession("https://claude.ai/code/session_x").withPlace(LivePlace.TERMINAL));
        IssueComment comment = new IssueComment(1, WORKER, StatusComment.render(live, LIMIT));

        assertThat(StatusComment.findRecord(List.of(comment), WORKER).orElseThrow().live().orElseThrow().place())
                .isEqualTo(LivePlace.TERMINAL);
    }

    @Test
    void theLinkKeepsThePlace() {
        LiveSession session = new LiveSession("abc", IssueStatus.IN_PROGRESS, Optional.empty(), Optional.empty(),
                LivePlace.BROWSER);

        assertThat(session.withLink("l").place()).isEqualTo(LivePlace.BROWSER);
        assertThat(session.withLink("l").withoutLink().place()).isEqualTo(LivePlace.BROWSER);
    }

    private static LiveSession liveSession(String link) {
        return new LiveSession("abc", IssueStatus.IN_REVIEW, Optional.of(WaitReason.QUESTION), Optional.of(link),
                LivePlace.PHONE);
    }

    @Test
    void anUntrustedLiveLinkCannotHideOrForgeTheRecord() {
        String hostile = "x --> <!-- y\n<!-- factory-record\n{}\n-->";
        IssueRecord live = record.withLive(liveSession(hostile));
        String body = StatusComment.render(live, LIMIT);
        IssueComment comment = new IssueComment(1, WORKER, body);

        assertThat(body.split("<!-- factory-record", -1)).hasSize(2);
        assertThat(StatusComment.findRecord(List.of(comment), WORKER)).contains(live);
    }
}
