package com.meterian.factory.usage;

import static org.assertj.core.api.Assertions.assertThat;

import com.meterian.factory.agent.SessionReport;
import com.meterian.factory.events.EventsFile;
import com.meterian.factory.events.WorkerEvents;
import com.meterian.factory.testing.MutableClock;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BudgetTrackerTest {

    private static final Instant MORNING = Instant.parse("2026-11-23T09:00:00Z");
    private static final Instant MIDNIGHT = Instant.parse("2026-11-24T00:00:00Z");

    @TempDir
    Path directory;

    private final MutableClock clock = new MutableClock(MORNING);

    private CostEvents createCostEvents() {
        Path file = directory.resolve(EventsFile.FILE_NAME);
        return new CostEvents(file, new WorkerEvents(new EventsFile(file), clock));
    }

    private static SessionReport reportWithCost(String cost) {
        return new SessionReport(true, "ok", Optional.of("ANTHROPIC_API_KEY"), Optional.empty(), List.of(), false,
                Optional.of(new BigDecimal(cost)));
    }

    @Test
    void permitsNewPhasesBelowTheBudget() throws Exception {
        BudgetTracker tracker = new BudgetTracker(new BigDecimal("10"), createCostEvents(), clock);

        tracker.recordSession(7, reportWithCost("9.99"));

        assertThat(tracker.resumesAt()).isEmpty();
        assertThat(tracker.findBudgetStatus()).contains(
                new BudgetStatus(new BigDecimal("9.99"), new BigDecimal("10")));
    }

    @Test
    void stopsNewPhasesUntilMidnightAtTheBudget() throws Exception {
        BudgetTracker tracker = new BudgetTracker(new BigDecimal("10"), createCostEvents(), clock);

        tracker.recordSession(7, reportWithCost("6"));
        tracker.recordSession(8, reportWithCost("4"));

        assertThat(tracker.resumesAt()).contains(MIDNIGHT);
        assertThat(tracker.waitReason()).isEqualTo("daily budget");
    }

    @Test
    void startsAgainAtZeroOnTheNextDay() throws Exception {
        BudgetTracker tracker = new BudgetTracker(new BigDecimal("10"), createCostEvents(), clock);
        tracker.recordSession(7, reportWithCost("12"));

        clock.set(MIDNIGHT.plus(Duration.ofMinutes(1)));

        assertThat(tracker.resumesAt()).isEmpty();
        assertThat(tracker.findBudgetStatus().orElseThrow().costToday()).isZero();
    }

    @Test
    void readsTheCostOfTodayAfterARestart() throws Exception {
        new BudgetTracker(new BigDecimal("10"), createCostEvents(), clock).recordSession(7, reportWithCost("10"));

        BudgetTracker restarted = new BudgetTracker(new BigDecimal("10"), createCostEvents(), clock);

        assertThat(restarted.resumesAt()).contains(MIDNIGHT);
    }

    @Test
    void aSessionWithoutCostAddsNothing() throws Exception {
        BudgetTracker tracker = new BudgetTracker(new BigDecimal("10"), createCostEvents(), clock);

        tracker.recordSession(7, new SessionReport(false, "The stream has no result line.", Optional.empty(),
                Optional.empty(), List.of()));

        assertThat(tracker.findBudgetStatus().orElseThrow().costToday()).isZero();
    }
}
