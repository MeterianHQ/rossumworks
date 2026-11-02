package com.meterian.factory.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.meterian.factory.testing.MutableClock;
import java.net.http.HttpHeaders;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class GitHubRateLimitTest {

    private static final Instant NOW = Instant.parse("2026-11-01T10:00:00Z");

    private final MutableClock clock = new MutableClock(NOW);
    private final GitHubRateLimit rateLimit = new GitHubRateLimit(clock);

    private static HttpHeaders headers(Map<String, String> values) {
        Map<String, List<String>> lists = values.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, entry -> List.of(entry.getValue())));
        return HttpHeaders.of(lists, (name, value) -> true);
    }

    private Instant readRefusal(Map<String, String> values) {
        try {
            rateLimit.readAnswer(429, headers(values));
        } catch (GitHubRateLimitException exception) {
            return exception.resetTime();
        }
        throw new AssertionError("The answer gave no GitHubRateLimitException.");
    }

    @Test
    void keepsTheLaterResetTimeWhenALaterAnswerGivesAnEarlierOne() {
        readRefusal(Map.of("Retry-After", "300"));

        Instant reported = readRefusal(Map.of("Retry-After", "30"));

        assertThat(reported).isEqualTo(NOW.plusSeconds(300));
        clock.set(NOW.plusSeconds(31));
        assertThatThrownBy(rateLimit::verifyRequestIsAllowed).isInstanceOf(GitHubRateLimitException.class);
    }

    @Test
    void keepsTheLaterLowRemainingReset() {
        rateLimit.readAnswer(200, headers(Map.of("X-RateLimit-Remaining", "50",
                "X-RateLimit-Reset", Long.toString(NOW.plusSeconds(600).getEpochSecond()))));
        rateLimit.readAnswer(200, headers(Map.of("X-RateLimit-Remaining", "60",
                "X-RateLimit-Reset", Long.toString(NOW.plusSeconds(60).getEpochSecond()))));

        assertThat(rateLimit.findLowRemainingReset()).contains(NOW.plusSeconds(600));
    }

    @Test
    void waitsAtLeastOneMinuteForARefusalWithoutAResetTime() {
        Instant reported = readRefusal(Map.of("X-RateLimit-Remaining", "0"));

        assertThat(reported).isEqualTo(NOW.plusSeconds(60));
    }

    @Test
    void neverRefusesARequestBecauseOfFewRemainingRequests() {
        rateLimit.readAnswer(200, headers(Map.of("X-RateLimit-Remaining", "50",
                "X-RateLimit-Reset", Long.toString(NOW.plusSeconds(600).getEpochSecond()))));

        rateLimit.verifyRequestIsAllowed();

        assertThat(rateLimit.findLowRemainingReset()).contains(NOW.plusSeconds(600));
    }
}
