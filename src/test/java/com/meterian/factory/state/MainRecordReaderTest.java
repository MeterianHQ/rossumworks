package com.meterian.factory.state;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meterian.factory.github.IssueComment;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MainRecordReaderTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SESSION = "7c1f3b0e-1111-4222-8333-944455556666";

    /** The JSON that main writes: Optional as value or null, enums by name, fields in the order of main. */
    private static String mainJson(String status, String phase, String reviews, int fixLoops, String waitReason,
            String live) {
        return """
                {"issueNumber":7,"sessionId":"%s","status":"%s","phase":"%s","reviews":%s,"fixLoops":%d,
                "risk":"LOW","small":true,"reviewedTreeHash":"abc","reviewedPatchId":"def","builtTreeHash":"ghi",
                "waitReason":%s,"pullRequestNumber":12,"statusCommentId":99,"notes":["A note"],
                "agentSessionStarted":true,"failedReviewRounds":1,"conflictedPaths":[],"issueTextHash":"0a1b",
                "reviewedHeadCommit":"cafe","failedForwardChecks":0,"failedForwardTree":null,"live":%s,
                "afterConversation":false}
                """.formatted(SESSION, status, phase, reviews, fixLoops, waitReason, live);
    }

    private static Optional<IssueRecord> read(String json) throws IOException {
        JsonNode node = MAPPER.readTree(json);
        return MainRecordReader.read(node);
    }

    @Test
    void readsARunningImplementRecordOfMain() throws IOException {
        String json = mainJson("IN_PROGRESS", "IMPLEMENT", "{}", 2, "null", "null");

        IssueRecord record = read(json).orElseThrow();

        assertThat(record.state()).isEqualTo("implement");
        assertThat(record.action()).contains(ActionType.CODING);
        assertThat(record.column()).isEqualTo("in-progress");
        assertThat(record.previousState()).contains("implement");
        assertThat(record.entries()).isEqualTo(Map.of("implement", 3));
        assertThat(record.pendingPrompt()).contains(PendingPrompt.IMPLEMENT);
        assertThat(record.actionStarted()).isFalse();
        assertThat(record.sessionId()).isEqualTo(UUID.fromString(SESSION));
        assertThat(record.issueNumber()).isEqualTo(7);
        assertThat(record.risk()).contains(RiskLevel.LOW);
        assertThat(record.small()).isTrue();
        assertThat(record.pullRequestNumber()).contains(12);
        assertThat(record.statusCommentId()).contains(99L);
        assertThat(record.notes()).containsExactly("A note");
        assertThat(record.agentSessionStarted()).isTrue();
        assertThat(record.failedReviewRounds()).isEqualTo(1);
        assertThat(record.issueTextHash()).contains("0a1b");
        assertThat(record.reviewedHeadCommit()).contains("cafe");
        assertThat(record.definitionVersion()).isEmpty();
    }

    /** Main keeps the fix prompt in .factory/fix-prompt.md, not in the findings file, so the fix starts again. */
    @Test
    void readsAFixRecordOfMainWithTheImplementPrompt() throws IOException {
        IssueRecord record = read(mainJson("IN_PROGRESS", "IMPLEMENT", "{}", 1, "null", "null")).orElseThrow();

        assertThat(record.pendingPrompt()).contains(PendingPrompt.IMPLEMENT);
        assertThat(record.entries()).isEqualTo(Map.of("implement", 2));
    }

    @Test
    void startsTheFirstImplementWithoutEntryOrPendingPrompt() throws IOException {
        IssueRecord record = read(mainJson("IN_PROGRESS", "IMPLEMENT", "{}", 0, "null", "null")).orElseThrow();

        assertThat(record.entries()).isEmpty();
        assertThat(record.pendingPrompt()).isEmpty();
    }

    @Test
    void readsAWaitingMergeRecordOfMain() throws IOException {
        String reviews = "{\"CORRECTNESS\":\"APPROVE\",\"SECURITY\":\"APPROVE\",\"ARCHITECTURE\":\"REJECT\","
                + "\"QUALITY\":\"APPROVE\"}";
        String json = mainJson("WAITS_FOR_YOU", "MERGE", reviews, 1, "\"MERGE_NEEDS_APPROVAL\"", "null");

        IssueRecord record = read(json).orElseThrow();

        assertThat(record.state()).isEqualTo("waits-for-approval");
        assertThat(record.action()).contains(ActionType.APPROVAL);
        assertThat(record.column()).isEqualTo("waits-for-you");
        assertThat(record.previousState()).contains("merge");
        assertThat(record.entries()).isEqualTo(Map.of("implement", 2));
        assertThat(record.reviews()).containsEntry("correctness", Verdict.APPROVE)
                .containsEntry("architecture", Verdict.REJECT).hasSize(4);
    }

    @Test
    void readsABlockedRecordOfMain() throws IOException {
        String json = mainJson("BLOCKED", "REVIEW", "{}", 3, "\"FIX_LOOP_LIMIT\"", "null");

        IssueRecord record = read(json).orElseThrow();

        assertThat(record.state()).isEqualTo("blocked");
        assertThat(record.action()).isEmpty();
        assertThat(record.column()).isEqualTo("blocked");
        assertThat(record.previousState()).contains("review");
        assertThat(record.entries()).isEqualTo(Map.of("implement", 4));
    }

    @Test
    void readsALiveRecordOfMain() throws IOException {
        String live = "{\"startHeadCommit\":\"beef\",\"statusBefore\":\"IN_PROGRESS\",\"waitBefore\":null,"
                + "\"link\":\"https://claude.ai/code/x\",\"place\":\"BROWSER\"}";
        String json = mainJson("WAITS_FOR_YOU", "BUILD", "{}", 0, "\"LIVE_CONVERSATION\"", live);

        IssueRecord record = read(json).orElseThrow();

        assertThat(record.state()).isEqualTo("live");
        assertThat(record.action()).contains(ActionType.CONVERSATION);
        assertThat(record.previousState()).contains("build");
        assertThat(record.live()).contains(
                new LiveSession("beef", Optional.of("https://claude.ai/code/x"), LivePlace.BROWSER));
    }

    @Test
    void readsAQueuedRecordOfMain() throws IOException {
        String json = mainJson("QUEUED", "PREPARE", "{}", 0, "null", "null");

        IssueRecord record = read(json).orElseThrow();

        assertThat(record.state()).isEqualTo("prepare");
        assertThat(record.action()).contains(ActionType.PREPARE);
        assertThat(record.column()).isEqualTo("queued");
        assertThat(record.entries()).isEmpty();
    }

    @Test
    void keepsABrokenRecordBroken() throws IOException {
        assertThat(read("{\"phase\":\"IMPLEMENT\"}")).isEmpty();
        assertThat(read(mainJson("IN_PROGRESS", "NOWHERE", "{}", 0, "null", "null"))).isEmpty();
        assertThat(read(mainJson("BLOCKED", "BUILD", "{}", 0, "null", "null"))).isEmpty();
        assertThat(read(mainJson("BLOCKED", "BUILD", "{}", 0, "\"UNKNOWN\"", "null"))).isEmpty();
        assertThat(read("[]")).isEmpty();
    }

    @Test
    void readsARealBlockedRecordOfMain() throws IOException {
        String json;
        try (var stream = getClass().getResourceAsStream("/state/main-record-blocked.json")) {
            json = new String(stream.readAllBytes(), StandardCharsets.UTF_8).strip();
        }
        String body = "**Factory status:** Blocked\nThe factory stopped.\n\n<!-- factory-record\n" + json
                + "\n-->";

        IssueRecord record = StatusComment.findRecord(List.of(new IssueComment(1, "factory-bot", body)),
                "factory-bot").orElseThrow();

        assertThat(record.state()).isEqualTo("blocked");
        assertThat(record.previousState()).contains("review");
        assertThat(record.column()).isEqualTo("blocked");
        assertThat(record.action()).isEmpty();
        assertThat(record.entries()).isEqualTo(Map.of("implement", 4));
        assertThat(record.pendingPrompt()).isEmpty();
        assertThat(record.reviews()).containsOnly(Map.entry("quality", Verdict.REJECT),
                Map.entry("correctness", Verdict.APPROVE), Map.entry("architecture", Verdict.REJECT),
                Map.entry("security", Verdict.APPROVE));
        assertThat(record.pullRequestNumber()).contains(457);
        assertThat(record.notes()).containsExactly("The owner started the issue again. The earlier work is discarded.");
    }

    @Test
    void readsAQuestionRecordWithoutALiveSessionAsLive() throws IOException {
        String json = mainJson("WAITS_FOR_YOU", "IMPLEMENT", "{}", 0, "\"QUESTION\"", "null");

        IssueRecord record = read(json).orElseThrow();

        assertThat(record.state()).isEqualTo("live");
        assertThat(record.live()).isEmpty();
    }
}
