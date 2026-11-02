package com.meterian.factory.worker;

import java.time.Clock;
import java.time.Instant;

/** The wait of the worker for the reset of the GitHub rate limit. One wait gets one log line. */
final class RateLimitWait {

    private static final System.Logger LOGGER = System.getLogger(Worker.class.getName());

    private final Clock clock;
    private Instant resetTime = Instant.MIN;

    RateLimitWait(Clock clock) {
        this.clock = clock;
    }

    synchronized boolean isWaiting() {
        return clock.instant().isBefore(resetTime);
    }

    /** The poll and the issue threads can report the same reset time. Only a later reset time is a new wait. */
    synchronized void waitUntil(Instant newResetTime) {
        if (!newResetTime.isAfter(resetTime)) {
            return;
        }
        resetTime = newResetTime;
        LOGGER.log(System.Logger.Level.WARNING, "The GitHub rate limit is reached. The factory waits until {0}.",
                newResetTime.toString());
    }
}
