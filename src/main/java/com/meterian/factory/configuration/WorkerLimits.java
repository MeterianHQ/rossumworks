package com.meterian.factory.configuration;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Optional;

/** @param dailyBudgetUsd the daily budget in US dollars, necessary only in API key mode */
public record WorkerLimits(int parallelIssues, Duration pollInterval, double usageThreshold,
                           Optional<BigDecimal> dailyBudgetUsd) {

    public WorkerLimits(int parallelIssues, Duration pollInterval, double usageThreshold) {
        this(parallelIssues, pollInterval, usageThreshold, Optional.empty());
    }

    public static WorkerLimits createDefaults() {
        return new WorkerLimits(2, Duration.ofSeconds(60), 0.90);
    }
}
