package com.meterian.factory.testing;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** A clock that a test moves by hand. It is thread safe. */
public final class MutableClock extends Clock {

    private volatile Instant instant;

    public MutableClock(Instant instant) {
        this.instant = instant;
    }

    public void set(Instant newInstant) {
        instant = newInstant;
    }

    public void advance(Duration duration) {
        instant = instant.plus(duration);
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }

    @Override
    public Instant instant() {
        return instant;
    }
}
