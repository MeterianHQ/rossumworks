package com.meterian.factory.usage;

import com.meterian.factory.agent.SessionReport;
import java.time.Instant;
import java.util.Optional;

/** Tells when a new phase may start. The subscription uses the usage windows, an API key uses a budget. */
public interface PhaseGuard {

    void recordSession(int issueNumber, SessionReport report);

    /** The time when new phases may start again, or empty when they may start now. */
    Optional<Instant> resumesAt();

    /** The reason of the wait, for the wait event of events.jsonl. */
    String waitReason();

    /** The cost of today and the budget, only in API key mode. */
    Optional<BudgetStatus> findBudgetStatus();
}
