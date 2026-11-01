package com.meterian.factory.configuration;

import java.time.Duration;

public record Limits(int parallelIssues, int fixLoops, Duration pollInterval, double usageThreshold) {

    public static Limits defaults() {
        return new Limits(2, 2, Duration.ofSeconds(60), 0.90);
    }
}
