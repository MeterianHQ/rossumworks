package com.meterian.factory.usage;

import com.meterian.factory.agent.UsageWindow;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Keeps the newest usage window values and tells when a new phase may start. */
public final class UsageTracker {

    private static final String ALLOWED_STATUS = "allowed";
    private static final Duration UNREADABLE_BLOCK = Duration.ofMinutes(15);
    private static final Duration MAXIMUM_PAST_RESET = Duration.ofDays(1);

    private final double threshold;
    private final Clock clock;
    private final Map<String, UsageWindow> windowsByName = new LinkedHashMap<>();

    public UsageTracker(double threshold, Clock clock) {
        this.threshold = threshold;
        this.clock = clock;
    }

    public synchronized void record(List<UsageWindow> windows) {
        windows.forEach(window -> windowsByName.put(window.name(), withReportTime(window)));
    }

    public synchronized boolean mayStartPhase() {
        return resumesAt().isEmpty();
    }

    public synchronized Optional<Instant> resumesAt() {
        Instant now = clock.instant();
        return windowsByName.values().stream()
                .flatMap(window -> blockEnd(window).stream())
                .filter(end -> end.isAfter(now))
                .max(Instant::compareTo);
    }

    public synchronized List<UsageWindow> windows() {
        return new ArrayList<>(windowsByName.values());
    }

    private UsageWindow withReportTime(UsageWindow window) {
        if (window.reportedAt() != null) {
            return window;
        }
        return new UsageWindow(window.name(), window.utilization(), window.resetsAt(), window.status(),
                clock.instant());
    }

    private Optional<Instant> blockEnd(UsageWindow window) {
        if (isUnreadable(window)) {
            return Optional.of(window.reportedAt().plus(UNREADABLE_BLOCK));
        }
        if (window.utilization() >= threshold || !ALLOWED_STATUS.equals(window.status())) {
            return Optional.of(window.resetsAt());
        }
        return Optional.empty();
    }

    private boolean isUnreadable(UsageWindow window) {
        return window.resetsAt() == null
                || !window.resetsAt().isAfter(window.reportedAt().minus(MAXIMUM_PAST_RESET))
                || Double.isNaN(window.utilization())
                || window.utilization() < 0
                || window.utilization() > 1
                || window.status() == null
                || window.status().isBlank();
    }
}
