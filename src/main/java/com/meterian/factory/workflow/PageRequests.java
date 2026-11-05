package com.meterian.factory.workflow;

import com.meterian.factory.state.LivePlace;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** The requests that the web page made for the owner. They are in memory only, so a restart forgets them. */
public final class PageRequests {

    static final Duration REQUEST_LIFETIME = Duration.ofHours(24);
    private static final String ENTER_LABEL = "factory:enter";

    private final Clock clock;
    private final Map<String, Instant> expiryByRequest = new ConcurrentHashMap<>();
    private final Map<Integer, PlaceRequest> placeByIssue = new ConcurrentHashMap<>();

    private record PlaceRequest(LivePlace place, Instant expiry) {
    }

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

    /**
     * Remembers a request label that the worker accepted, so that the page shows its waiting state also for a label
     * from the GitHub app.
     *
     * @return true when the page did not know this request before
     */
    public boolean rememberAcceptedRequest(int issueNumber, String label) {
        boolean isNew = !isRemembered(issueNumber, label);
        if (isNew) {
            remember(issueNumber, label);
        }
        if (ENTER_LABEL.equals(label)) {
            rememberPhoneUnlessAPlaceIsRemembered(issueNumber);
        }
        return isNew;
    }

    /** The page calls this method before it adds the label that enters the live conversation. */
    public void rememberEnterPlace(int issueNumber, LivePlace place) {
        placeByIssue.put(issueNumber, new PlaceRequest(place, clock.instant().plus(REQUEST_LIFETIME)));
    }

    /** A label from the GitHub app has no place. Without a place of the page, the conversation opens on the phone. */
    public void rememberPhoneUnlessAPlaceIsRemembered(int issueNumber) {
        if (findEnterPlace(issueNumber).isEmpty()) {
            rememberEnterPlace(issueNumber, LivePlace.PHONE);
        }
    }

    /** Gives the remembered place and keeps it. Gives nothing when no place is remembered or it expired. */
    public Optional<LivePlace> findEnterPlace(int issueNumber) {
        PlaceRequest request = placeByIssue.get(issueNumber);
        if (request == null || !clock.instant().isBefore(request.expiry())) {
            return Optional.empty();
        }
        return Optional.of(request.place());
    }

    /** Gives the remembered place and forgets it. Gives PHONE when no place is remembered or it expired. */
    public LivePlace takeEnterPlace(int issueNumber) {
        PlaceRequest request = placeByIssue.remove(issueNumber);
        if (request == null || !clock.instant().isBefore(request.expiry())) {
            return LivePlace.PHONE;
        }
        return request.place();
    }

    private void removeExpiredRequests() {
        Instant now = clock.instant();
        expiryByRequest.values().removeIf(expiry -> !now.isBefore(expiry));
    }

    private static String createKey(int issueNumber, String label) {
        return issueNumber + " " + label;
    }
}
