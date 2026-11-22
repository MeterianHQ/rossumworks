package com.meterian.factory.events;

import static org.assertj.core.api.Assertions.assertThat;

import com.meterian.factory.testing.CollectedEvents;
import com.meterian.factory.testing.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class WorkerEventsTest {

    private static final Instant NOW = Instant.parse("2026-11-22T17:20:05.123Z");

    private final MutableClock clock = new MutableClock(NOW);
    private final CollectedEvents collected = new CollectedEvents(clock);
    private final WorkerEvents events = collected.workerEvents();

    @Test
    void writesTheStartAndTheStopOfTheWorker() {
        events.workerStarted("0.4.0", "MeterianHQ/oss-detect");
        events.workerStopped("0.4.0", "MeterianHQ/oss-detect");

        assertThat(collected.lines()).containsExactly(
                "{\"time\":\"2026-11-22T17:20:05Z\",\"type\":\"worker\",\"event\":\"start\",\"version\":\"0.4.0\","
                        + "\"repository\":\"MeterianHQ/oss-detect\"}",
                "{\"time\":\"2026-11-22T17:20:05Z\",\"type\":\"worker\",\"event\":\"stop\",\"version\":\"0.4.0\","
                        + "\"repository\":\"MeterianHQ/oss-detect\"}");
    }

    @Test
    void writesAMoveWithItsEventAndItsEntries() {
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("implement", "1/3");
        entries.put("review", "1/10");

        events.issueMoved(39, "build", Optional.of("passed"), "review", "in-review", entries);

        assertThat(collected.lines()).containsExactly("{\"time\":\"2026-11-22T17:20:05Z\",\"type\":\"move\","
                + "\"issue\":39,\"from\":\"build\",\"event\":\"passed\",\"to\":\"review\",\"column\":\"in-review\","
                + "\"entries\":{\"implement\":\"1/3\",\"review\":\"1/10\"}}");
    }

    @Test
    void writesAMoveWithoutAnEventWithoutTheFieldEvent() {
        events.issueMoved(39, "implement", Optional.empty(), "blocked", "blocked", Map.of());

        assertThat(collected.ofType("move").getFirst().has("event")).isFalse();
    }

    @Test
    void writesTheEndOfAnActionWithItsDurationAndItsFields() {
        events.actionEnded(39, "build", "build", "passed", Duration.ofMillis(12_900), Map.of("exit_code", 0));

        assertThat(collected.lines()).containsExactly("{\"time\":\"2026-11-22T17:20:05Z\",\"type\":\"action-end\","
                + "\"issue\":39,\"state\":\"build\",\"action\":\"build\",\"result\":\"passed\",\"duration_s\":12,"
                + "\"exit_code\":0}");
    }

    @Test
    void writesTheOpenAndTheMergeOfAPullRequest() {
        events.pullRequestOpened(39, "https://github.com/MeterianHQ/oss-detect/pull/43");
        events.pullRequestMerged(39, "https://github.com/MeterianHQ/oss-detect/pull/43", Optional.of("abc123"));

        assertThat(collected.lines()).containsExactly(
                "{\"time\":\"2026-11-22T17:20:05Z\",\"type\":\"pr\",\"issue\":39,\"event\":\"opened\","
                        + "\"url\":\"https://github.com/MeterianHQ/oss-detect/pull/43\"}",
                "{\"time\":\"2026-11-22T17:20:05Z\",\"type\":\"pr\",\"issue\":39,\"event\":\"merged\","
                        + "\"url\":\"https://github.com/MeterianHQ/oss-detect/pull/43\",\"commit\":\"abc123\"}");
    }

    @Test
    void writesAMergeWithoutAKnownCommitWithoutTheFieldCommit() {
        events.pullRequestMerged(39, "https://github.com/MeterianHQ/oss-detect/pull/43", Optional.empty());

        assertThat(collected.ofType("pr").getFirst().has("commit")).isFalse();
    }

    @Test
    void writesOnlyTheFirstLineOfTheBlockReason() {
        events.issueBlocked(39, "The agent session failed: the tests do not pass.\nThe details follow.");

        assertThat(collected.lines()).containsExactly("{\"time\":\"2026-11-22T17:20:05Z\",\"type\":\"blocked\","
                + "\"issue\":39,\"reason\":\"The agent session failed: the tests do not pass.\"}");
    }

    @Test
    void writesOnlyTheFirstLineOfTheQuestion() {
        events.questionAsked(39, "Which greeting do you want?\n\nThe issue names two greetings.");

        assertThat(collected.lines()).containsExactly("{\"time\":\"2026-11-22T17:20:05Z\",\"type\":\"question\","
                + "\"issue\":39,\"first_line\":\"Which greeting do you want?\"}");
    }

    @Test
    void writesAWaitWithItsReasonAndItsEnd() {
        events.waitStarted("usage limit", Instant.parse("2026-11-22T19:00:00.500Z"));

        assertThat(collected.lines()).containsExactly("{\"time\":\"2026-11-22T17:20:05Z\",\"type\":\"wait\","
                + "\"reason\":\"usage limit\",\"until\":\"2026-11-22T19:00:00Z\"}");
    }

    @Test
    void writesTheSameWaitOnlyOnce() {
        Instant reset = Instant.parse("2026-11-22T19:00:00Z");

        events.waitStarted("usage limit", reset);
        events.waitStarted("usage limit", reset);
        events.waitStarted("github rate limit", reset);
        events.waitStarted("usage limit", reset.plusSeconds(60));

        assertThat(collected.ofType("wait")).extracting(line -> line.path("reason").asText())
                .containsExactly("usage limit", "github rate limit", "usage limit");
    }
}
