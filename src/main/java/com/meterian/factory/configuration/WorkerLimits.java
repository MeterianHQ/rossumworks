package com.meterian.factory.configuration;

import java.time.Duration;

public record WorkerLimits(int parallelIssues, Duration pollInterval, double usageThreshold) {

    public static WorkerLimits createDefaults() {
        return new WorkerLimits(2, Duration.ofSeconds(60), 0.90);
    }
}
