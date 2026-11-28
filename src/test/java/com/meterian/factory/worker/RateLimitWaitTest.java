package com.meterian.factory.worker;

import static org.assertj.core.api.Assertions.assertThat;

import com.meterian.factory.testing.CollectedEvents;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class RateLimitWaitTest {

    private static final Instant NOW = Instant.parse("2026-11-22T17:20:05Z");
    private static final Instant RESET = Instant.parse("2026-11-22T18:00:00Z");

    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final CollectedEvents events = new CollectedEvents(clock);
    private final RateLimitWait rateLimitWait = new RateLimitWait(clock, events.workerEvents());

    @Test
    void writesOneWaitLineForTheGitHubRateLimit() {
        rateLimitWait.waitUntil(RESET);
        rateLimitWait.waitUntil(RESET);

        assertThat(rateLimitWait.isWaiting()).isTrue();
        assertThat(events.lines()).containsExactly("{\"time\":\"2026-11-22T17:20:05Z\",\"type\":\"wait\","
                + "\"reason\":\"github rate limit\",\"until\":\"2026-11-22T18:00:00Z\"}");
    }
}
