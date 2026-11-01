package com.meterian.factory.agent;

import java.time.Instant;

public record UsageWindow(String name, double utilization, Instant resetsAt, String status, Instant reportedAt) {
}
