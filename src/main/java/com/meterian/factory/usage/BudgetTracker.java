package com.meterian.factory.usage;

import com.meterian.factory.agent.SessionReport;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;

/**
 * The guard of API key mode. It adds up the cost of the sessions of today. At the daily budget, no new phase
 * starts until midnight, in the time zone of the clock.
 */
public final class BudgetTracker implements PhaseGuard {

    private static final System.Logger LOGGER = System.getLogger(BudgetTracker.class.getName());
    private static final String WAIT_REASON = "daily budget";

    private final BigDecimal dailyBudget;
    private final CostEvents costEvents;
    private final Clock clock;
    private LocalDate day;
    private BigDecimal costOfDay;

    /** @throws IOException when events.jsonl cannot be read: then the cost of today is not known */
    public BudgetTracker(BigDecimal dailyBudget, CostEvents costEvents, Clock clock) throws IOException {
        this.dailyBudget = dailyBudget;
        this.costEvents = costEvents;
        this.clock = clock;
        this.day = readToday();
        this.costOfDay = costEvents.readCostOf(day, clock.getZone());
    }

    @Override
    public synchronized void recordSession(int issueNumber, SessionReport report) {
        if (report.costUsd().isEmpty()) {
            LOGGER.log(System.Logger.Level.WARNING,
                    "The session of the issue #{0} gave no cost. The daily budget does not include it.", issueNumber);
            return;
        }
        startNewDayIfNecessary();
        BigDecimal cost = report.costUsd().get();
        costOfDay = costOfDay.add(cost);
        costEvents.write(issueNumber, cost);
    }

    @Override
    public synchronized Optional<Instant> resumesAt() {
        startNewDayIfNecessary();
        if (costOfDay.compareTo(dailyBudget) < 0) {
            return Optional.empty();
        }
        return Optional.of(day.plusDays(1).atStartOfDay(clock.getZone()).toInstant());
    }

    @Override
    public String waitReason() {
        return WAIT_REASON;
    }

    @Override
    public synchronized Optional<BudgetStatus> findBudgetStatus() {
        startNewDayIfNecessary();
        return Optional.of(new BudgetStatus(costOfDay, dailyBudget));
    }

    private void startNewDayIfNecessary() {
        LocalDate today = readToday();
        if (!today.equals(day)) {
            day = today;
            costOfDay = BigDecimal.ZERO;
        }
    }

    private LocalDate readToday() {
        return LocalDate.ofInstant(clock.instant(), clock.getZone());
    }
}
