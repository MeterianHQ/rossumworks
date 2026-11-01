package com.meterian.factory.usage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.meterian.factory.agent.UsageWindow;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class UsageTrackerTest {

    private static final Instant NOW = Instant.parse("2026-11-01T10:00:00Z");

    private final MutableClock clock = new MutableClock(NOW);
    private final UsageTracker tracker = new UsageTracker(0.90, clock);

    @Test
    void allowsAPhaseWithNoData() {
        assertTrue(tracker.mayStartPhase());
        assertEquals(Optional.empty(), tracker.resumesAt());
    }

    @Test
    void refusesAPhaseAtTheThreshold() {
        Instant reset = NOW.plus(1, ChronoUnit.HOURS);
        tracker.record(List.of(window("seven_day", 0.90, reset, "allowed")));

        assertFalse(tracker.mayStartPhase());
        assertEquals(Optional.of(reset), tracker.resumesAt());
    }

    @Test
    void allowsAPhaseAfterTheReset() {
        Instant reset = NOW.plus(1, ChronoUnit.HOURS);
        tracker.record(List.of(window("seven_day", 0.95, reset, "allowed")));

        clock.set(reset.plusSeconds(1));

        assertTrue(tracker.mayStartPhase());
        assertEquals(Optional.empty(), tracker.resumesAt());
    }

    @Test
    void refusesAPhaseWhenTheStatusIsNotAllowed() {
        Instant reset = NOW.plus(2, ChronoUnit.HOURS);
        tracker.record(List.of(window("five_hour", 0.10, reset, "rejected")));

        assertFalse(tracker.mayStartPhase());
        assertEquals(Optional.of(reset), tracker.resumesAt());
    }

    @Test
    void givesTheLatestResetOfTheBlockingWindows() {
        Instant early = NOW.plus(1, ChronoUnit.HOURS);
        Instant late = NOW.plus(5, ChronoUnit.HOURS);
        tracker.record(List.of(window("five_hour", 0.95, early, "allowed"),
                window("seven_day", 0.99, late, "allowed"),
                window("opus", 0.10, NOW.plus(9, ChronoUnit.HOURS), "allowed")));

        assertEquals(Optional.of(late), tracker.resumesAt());
    }

    @Test
    void keepsTheNewestValueOfEachWindow() {
        Instant reset = NOW.plus(1, ChronoUnit.HOURS);
        tracker.record(List.of(window("seven_day", 0.95, reset, "allowed")));
        tracker.record(List.of(window("seven_day", 0.20, reset, "allowed")));

        assertTrue(tracker.mayStartPhase());
        assertEquals(1, tracker.windows().size());
        assertEquals(0.20, tracker.windows().get(0).utilization());
    }

    private static UsageWindow window(String name, double utilization, Instant resetsAt, String status) {
        return new UsageWindow(name, utilization, resetsAt, status, NOW);
    }

    private static final class MutableClock extends java.time.Clock {

        private volatile Instant instant;

        MutableClock(Instant instant) {
            this.instant = instant;
        }

        void set(Instant newInstant) {
            instant = newInstant;
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public java.time.Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
