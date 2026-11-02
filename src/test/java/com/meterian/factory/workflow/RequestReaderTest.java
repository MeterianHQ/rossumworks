package com.meterian.factory.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import com.meterian.factory.testing.FakeGitHubClient;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RequestReaderTest {

    private final FakeGitHubClient gitHub = new FakeGitHubClient();
    private final RequestReader requests = new RequestReader(gitHub, List.of("bbossola"));

    @BeforeEach
    void createIssue() {
        gitHub.addIssue(7, "Title", "Body");
    }

    @Test
    void acceptsALabelFromAnAllowedAccount() {
        gitHub.addLabelBy(7, "factory:approve", "bbossola");

        assertThat(requests.isGivenByAllowedAccount(7, "factory:approve")).isTrue();
    }

    @Test
    void refusesALabelWithNoEvent() {
        assertThat(requests.isGivenByAllowedAccount(7, "factory:approve")).isFalse();
    }

    @Test
    void refusesALabelFromAnotherAccount() {
        gitHub.addLabelBy(7, "factory:approve", "mallory");

        assertThat(requests.isGivenByAllowedAccount(7, "factory:approve")).isFalse();
    }

    @Test
    void readsOnlyTheNewestEvent() {
        gitHub.addLabelBy(7, "factory:approve", "bbossola");
        gitHub.removeLabel(7, "factory:approve");
        gitHub.addLabelBy(7, "factory:approve", "mallory");

        assertThat(requests.isGivenByAllowedAccount(7, "factory:approve")).isFalse();
    }

    @Test
    void comparesTheAccountsWithoutCase() {
        gitHub.addLabelBy(7, "factory:approve", "BBossola");

        assertThat(requests.isGivenByAllowedAccount(7, "factory:approve")).isTrue();
    }

    @Test
    void readsTheEventsOnceForAllLabelChecksOfOneIssue() {
        gitHub.addLabelBy(7, "factory:block", "mallory");
        gitHub.addLabelBy(7, "factory:enter", "bbossola");
        IssueLabelActors actors = requests.readLabelActorsOf(7);

        assertThat(actors.isGivenByAllowedAccount("factory:block")).isFalse();
        assertThat(actors.isGivenByAllowedAccount("factory:enter")).isTrue();
        assertThat(actors.findActor("factory:block")).contains("mallory");
        assertThat(gitHub.labelEventReadCount(7)).isEqualTo(1);
    }

    @Test
    void readsNoEventsBeforeTheFirstLabelCheck() {
        requests.readLabelActorsOf(7);

        assertThat(gitHub.labelEventReadCount(7)).isZero();
    }
}
