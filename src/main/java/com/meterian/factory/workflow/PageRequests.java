package com.meterian.factory.workflow;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** The requests that the web page made for the owner. They are in memory only, so a restart forgets them. */
public final class PageRequests {

    static final Duration REQUEST_LIFETIME = Duration.ofHours(24);

    private final Clock clock;
    private final Map<String, Instant> expiryByRequest = new ConcurrentHashMap<>();

    public PageRequests(Clock clock) {
        this.clock = clock;
    }

    /** The page calls this method before it adds the label. */
    public void remember(int issueNumber, String label) {
        removeExpiredRequests();
        expiryByRequest.put(createKey(issueNumber, label), clock.instant().plus(REQUEST_LIFETIME));
    }

    public boolean isRemembered(int issueNumber, String label) {
        Instant expiry = expiryByRequest.get(createKey(issueNumber, label));
        return expiry != null && clock.instant().isBefore(expiry);
    }

    private void removeExpiredRequests() {
        Instant now = clock.instant();
        expiryByRequest.values().removeIf(expiry -> !now.isBefore(expiry));
    }

    private static String createKey(int issueNumber, String label) {
        return issueNumber + " " + label;
    }
}
