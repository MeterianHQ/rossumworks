package com.meterian.factory.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import com.meterian.factory.state.LivePlace;
import com.meterian.factory.testing.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class PageRequestsTest {

    private static final int ISSUE = 7;
    private static final String ENTER_LABEL = "factory:enter";
    private static final String BLOCK_LABEL = "factory:block";

    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-01T10:00:00Z"));
    private final PageRequests pageRequests = new PageRequests(clock);

    @Test
    void takesTheEnterPlaceOnce() {
        pageRequests.rememberEnterPlace(ISSUE, LivePlace.TERMINAL);

        assertThat(pageRequests.takeEnterPlace(ISSUE)).isEqualTo(LivePlace.TERMINAL);
        assertThat(pageRequests.takeEnterPlace(ISSUE)).isEqualTo(LivePlace.GITHUB_COMMENT);
    }

    @Test
    void anEnterLabelFromTheGitHubAppShowsTheGitHubComment() {
        pageRequests.rememberAcceptedRequest(ISSUE, ENTER_LABEL);

        assertThat(pageRequests.findEnterPlace(ISSUE)).contains(LivePlace.GITHUB_COMMENT);
    }

    @Test
    void anEnterLabelKeepsThePlaceOfThePage() {
        pageRequests.rememberEnterPlace(ISSUE, LivePlace.TERMINAL);

        pageRequests.rememberAcceptedRequest(ISSUE, ENTER_LABEL);

        assertThat(pageRequests.findEnterPlace(ISSUE)).contains(LivePlace.TERMINAL);
    }

    @Test
    void anotherLabelRemembersNoEnterPlace() {
        pageRequests.rememberAcceptedRequest(ISSUE, BLOCK_LABEL);

        assertThat(pageRequests.findEnterPlace(ISSUE)).isEmpty();
    }

    @Test
    void rememberingAnAcceptedRequestTellsIfItIsNew() {
        assertThat(pageRequests.rememberAcceptedRequest(ISSUE, BLOCK_LABEL)).isTrue();
        assertThat(pageRequests.rememberAcceptedRequest(ISSUE, BLOCK_LABEL)).isFalse();
        assertThat(pageRequests.isRemembered(ISSUE, BLOCK_LABEL)).isTrue();
    }

    @Test
    void aPageRequestOfThePageIsNotNewForTheWorker() {
        pageRequests.remember(ISSUE, BLOCK_LABEL);

        assertThat(pageRequests.rememberAcceptedRequest(ISSUE, BLOCK_LABEL)).isFalse();
    }

    @Test
    void aRememberedRequestEndsAfter24Hours() {
        pageRequests.rememberAcceptedRequest(ISSUE, BLOCK_LABEL);
        clock.advance(Duration.ofHours(24).plusSeconds(1));

        assertThat(pageRequests.isRemembered(ISSUE, BLOCK_LABEL)).isFalse();
        assertThat(pageRequests.rememberAcceptedRequest(ISSUE, BLOCK_LABEL)).isTrue();
    }

    @Test
    void aRememberedRequestIsForOneIssueAndOneLabel() {
        pageRequests.remember(ISSUE, BLOCK_LABEL);

        assertThat(pageRequests.isRemembered(ISSUE + 1, BLOCK_LABEL)).isFalse();
        assertThat(pageRequests.isRemembered(ISSUE, ENTER_LABEL)).isFalse();
    }

    @Test
    void anExpiredEnterPlaceIsTheGitHubComment() {
        pageRequests.rememberEnterPlace(ISSUE, LivePlace.BROWSER);
        clock.advance(Duration.ofHours(24).plusSeconds(1));

        assertThat(pageRequests.takeEnterPlace(ISSUE)).isEqualTo(LivePlace.GITHUB_COMMENT);
    }

    @Test
    void findsTheEnterPlaceAndKeepsIt() {
        pageRequests.rememberEnterPlace(ISSUE, LivePlace.BROWSER);

        assertThat(pageRequests.findEnterPlace(ISSUE)).contains(LivePlace.BROWSER);
        assertThat(pageRequests.takeEnterPlace(ISSUE)).isEqualTo(LivePlace.BROWSER);
    }

    @Test
    void findsNoExpiredEnterPlace() {
        pageRequests.rememberEnterPlace(ISSUE, LivePlace.TERMINAL);
        clock.advance(Duration.ofHours(24).plusSeconds(1));

        assertThat(pageRequests.findEnterPlace(ISSUE)).isEmpty();
        assertThat(pageRequests.findEnterPlace(ISSUE + 1)).isEqualTo(Optional.empty());
    }
}
