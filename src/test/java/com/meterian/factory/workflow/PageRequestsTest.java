package com.meterian.factory.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import com.meterian.factory.state.LivePlace;
import com.meterian.factory.testing.MutableClock;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class PageRequestsTest {

    private static final int ISSUE = 7;

    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-01T10:00:00Z"));
    private final PageRequests pageRequests = new PageRequests(clock);

    @Test
    void takesTheEnterPlaceOnce() {
        pageRequests.rememberEnterPlace(ISSUE, LivePlace.TERMINAL);

        assertThat(pageRequests.takeEnterPlace(ISSUE)).isEqualTo(LivePlace.TERMINAL);
        assertThat(pageRequests.takeEnterPlace(ISSUE)).isEqualTo(LivePlace.PHONE);
    }

    @Test
    void anExpiredEnterPlaceIsPhone() {
        pageRequests.rememberEnterPlace(ISSUE, LivePlace.BROWSER);
        clock.advance(Duration.ofHours(24).plusSeconds(1));

        assertThat(pageRequests.takeEnterPlace(ISSUE)).isEqualTo(LivePlace.PHONE);
    }
}
