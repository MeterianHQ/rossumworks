package com.meterian.factory.github;

import java.net.http.HttpHeaders;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Reads the rate-limit headers of each answer. After a refusal by the limit, it refuses each request until the
 * reset time. It is thread safe, so the poll and the issue threads share one wait.
 */
final class GitHubRateLimit {

    /** Below this number the poll starts no work, so that the other tools of the account keep some requests. */
    static final int MINIMUM_REMAINING_REQUESTS = 100;
    /** GitHub tells to wait at least one minute when a refusal gives no time. */
    static final Duration MINIMUM_REFUSAL_WAIT = Duration.ofMinutes(1);

    private final Clock clock;
    private Instant refusalEnd = Instant.MIN;
    private Instant lowRemainingEnd = Instant.MIN;

    GitHubRateLimit(Clock clock) {
        this.clock = clock;
    }

    /** Throws before the request is sent when the wait after a refusal is not over. */
    synchronized void verifyRequestIsAllowed() {
        if (clock.instant().isBefore(refusalEnd)) {
            throw createException(refusalEnd);
        }
    }

    /** The reset time of the window when few requests remain in it. Only the poll uses it, never an action. */
    synchronized Optional<Instant> findLowRemainingReset() {
        return clock.instant().isBefore(lowRemainingEnd) ? Optional.of(lowRemainingEnd) : Optional.empty();
    }

    /** A refusal by the limit throws. A concurrent answer can never make a wait shorter. */
    synchronized void readAnswer(int statusCode, HttpHeaders headers) {
        Optional<Long> remaining = readNumber(headers, "X-RateLimit-Remaining");
        Optional<Instant> windowReset = readNumber(headers, "X-RateLimit-Reset").map(Instant::ofEpochSecond);
        Optional<Instant> retryTime = readNumber(headers, "Retry-After").map(clock.instant()::plusSeconds);
        if (remaining.filter(count -> count < MINIMUM_REMAINING_REQUESTS).isPresent() && windowReset.isPresent()) {
            lowRemainingEnd = later(lowRemainingEnd, windowReset.get());
        }
        boolean isRefused = statusCode == 403 || statusCode == 429;
        if (isRefused && (remaining.equals(Optional.of(0L)) || retryTime.isPresent())) {
            refusalEnd = later(refusalEnd, findRefusalEnd(windowReset, retryTime));
            throw createException(refusalEnd);
        }
    }

    private Instant findRefusalEnd(Optional<Instant> windowReset, Optional<Instant> retryTime) {
        if (windowReset.isEmpty() && retryTime.isEmpty()) {
            return clock.instant().plus(MINIMUM_REFUSAL_WAIT);
        }
        return later(windowReset.orElse(Instant.MIN), retryTime.orElse(Instant.MIN));
    }

    private static Instant later(Instant first, Instant second) {
        return first.isAfter(second) ? first : second;
    }

    private static GitHubRateLimitException createException(Instant resetTime) {
        return new GitHubRateLimitException("The GitHub rate limit is reached until " + resetTime + ".", resetTime);
    }

    private static Optional<Long> readNumber(HttpHeaders headers, String name) {
        try {
            return headers.firstValue(name).map(String::strip).map(Long::parseLong);
        } catch (NumberFormatException exception) {
            return Optional.empty();
        }
    }
}
