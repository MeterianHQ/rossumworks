package com.meterian.factory.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class StreamEventParserTest {

    private static final Instant NOW = Instant.parse("2026-11-01T13:30:00Z");

    @Test
    void readsTheResultOfASuccessfulRun() throws IOException {
        SessionReport report = parse("success.jsonl").report();

        assertTrue(report.success());
        assertEquals("ok", report.resultText());
    }

    @Test
    void readsTheApiKeySource() throws IOException {
        assertEquals(Optional.of("none"), parse("success.jsonl").apiKeySource());
        assertEquals(Optional.of("ANTHROPIC_API_KEY"), parse("api-key.jsonl").report().apiKeySource());
    }

    @Test
    void computesTheContextInUse() throws IOException {
        ContextUsage usage = parse("success.jsonl").report().contextUsage().orElseThrow();

        assertEquals(26262, usage.tokensInUse());
        assertEquals(1000000, usage.contextWindow());
    }

    @Test
    void readsTheUsageWindows() throws IOException {
        List<UsageWindow> windows = parse("success.jsonl").report().usageWindows();
        UsageWindow window = windows.get(0);

        assertEquals(1, windows.size());
        assertEquals("seven_day", window.name());
        assertEquals(0.37, window.utilization());
        assertEquals(Instant.ofEpochSecond(1793815200), window.resetsAt());
        assertEquals("allowed", window.status());
        assertEquals(NOW, window.reportedAt());
    }

    @Test
    void reportsAnErrorResultAsFailure() throws IOException {
        assertFalse(parse("error.jsonl").report().success());
    }

    @Test
    void reportsAMissingResultAsFailure() throws IOException {
        SessionReport report = parse("garbage.jsonl").report();

        assertFalse(report.success());
        assertTrue(report.resultText().contains("no result"));
    }

    private StreamEventParser parse(String resourceName) throws IOException {
        StreamEventParser parser = new StreamEventParser(Clock.fixed(NOW, ZoneOffset.UTC));
        try (InputStream stream = getClass().getResourceAsStream("/stream/" + resourceName)) {
            new String(stream.readAllBytes(), StandardCharsets.UTF_8).lines().forEach(parser::accept);
        }
        return parser;
    }
}
