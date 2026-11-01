package com.meterian.factory.github;

import java.net.http.HttpHeaders;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

/**
 * Reads the rate-limit headers of each answer and refuses a request until the reset time. It is thread safe,
 * so the poll and the issue threads share one wait.
 */
final class GitHubRateLimit {

    /** The worker stops below this number, so that the other tools of the account keep some requests. */
    static final int MINIMUM_REMAINING_REQUESTS = 100;

    private final Clock clock;
    private Instant resetTime = Instant.MIN;

    GitHubRateLimit(Clock clock) {
        this.clock = clock;
    }

    /** Throws before the request is sent when the wait for the reset time is not over. */
    synchronized void verifyRequestIsAllowed() {
        if (clock.instant().isBefore(resetTime)) {
            throw createException();
        }
    }

    /** A successful answer with few remaining requests starts a wait for the next request. */
    synchronized void readAnswer(int statusCode, HttpHeaders headers) {
        Optional<Integer> remaining = readNumber(headers, "X-RateLimit-Remaining").map(Long::intValue);
        Optional<Instant> retryTime = readNumber(headers, "Retry-After").map(clock.instant()::plusSeconds);
        boolean isRefused = statusCode == 403 || statusCode == 429;
        boolean isRefusedByTheLimit = isRefused && (remaining.equals(Optional.of(0)) || retryTime.isPresent());
        boolean hasFewRemaining = remaining.filter(count -> count < MINIMUM_REMAINING_REQUESTS).isPresent();
        if (!isRefusedByTheLimit && !hasFewRemaining) {
            return;
        }
        Instant windowReset = readNumber(headers, "X-RateLimit-Reset").map(Instant::ofEpochSecond)
                .orElse(clock.instant());
        resetTime = retryTime.filter(retry -> retry.isAfter(windowReset)).orElse(windowReset);
        if (isRefusedByTheLimit) {
            throw createException();
        }
    }

    private GitHubRateLimitException createException() {
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
