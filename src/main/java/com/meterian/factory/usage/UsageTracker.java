package com.meterian.factory.usage;

import com.meterian.factory.agent.UsageWindow;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Keeps the newest usage window values and tells when a new phase may start. */
public final class UsageTracker {

    private static final String ALLOWED_STATUS = "allowed";

    private final double threshold;
    private final Clock clock;
    private final Map<String, UsageWindow> windowsByName = new LinkedHashMap<>();

    public UsageTracker(double threshold, Clock clock) {
        this.threshold = threshold;
        this.clock = clock;
    }

    public synchronized void record(List<UsageWindow> windows) {
        windows.forEach(window -> windowsByName.put(window.name(), window));
    }

    public synchronized boolean mayStartPhase() {
        return resumesAt().isEmpty();
    }

    public synchronized Optional<Instant> resumesAt() {
        Instant now = clock.instant();
        return windowsByName.values().stream()
                .filter(this::isBlocking)
                .map(UsageWindow::resetsAt)
                .filter(resetsAt -> resetsAt.isAfter(now))
                .max(Instant::compareTo);
    }

    public synchronized List<UsageWindow> windows() {
        return new ArrayList<>(windowsByName.values());
    }

    private boolean isBlocking(UsageWindow window) {
        return window.utilization() >= threshold || !ALLOWED_STATUS.equals(window.status());
    }
}
