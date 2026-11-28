package com.meterian.factory.state;

import java.time.Duration;
import java.time.Instant;

/**
 * The merge waits for the required check runs of one commit, since a time.
 *
 * @param commit               the commit that the squash merge takes
 * @param startedAtEpochSecond the start of the wait, in seconds since 1970-01-01T00:00:00Z
 */
public record CheckRunWait(String commit, long startedAtEpochSecond) {

    public static CheckRunWait startNow(String commit, Instant now) {
        return new CheckRunWait(commit, now.getEpochSecond());
    }

    public boolean isLongerThan(Duration limit, Instant now) {
        return Duration.between(Instant.ofEpochSecond(startedAtEpochSecond), now).compareTo(limit) > 0;
    }
}
