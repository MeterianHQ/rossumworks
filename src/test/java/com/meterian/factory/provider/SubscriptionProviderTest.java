package com.meterian.factory.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.meterian.factory.agent.CredentialMismatchException;
import com.meterian.factory.agent.SessionReport;
import com.meterian.factory.agent.UsageWindow;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SubscriptionProviderTest {

    private static final Instant NOW = Instant.parse("2026-11-23T10:00:00Z");
    private final SubscriptionProvider provider = new SubscriptionProvider();

    @Test
    void acceptsOnlyTheSubscriptionSource() {
        assertThatCode(() -> provider.verifyCredentialSource("none")).doesNotThrowAnyException();
        assertThatThrownBy(() -> provider.verifyCredentialSource("apiKeyHelper"))
                .isInstanceOf(CredentialMismatchException.class)
                .hasMessage("The session uses the credential source apiKeyHelper. "
                        + "The provider subscription expects none.");
    }

    @Test
    void refusesASuccessfulSessionWithoutUsageWindows() {
        SessionReport report = new SessionReport(true, "ok", Optional.of("none"), Optional.empty(), List.of());

        assertThatThrownBy(() -> provider.verifyFinishedSession(report))
                .isInstanceOf(CredentialMismatchException.class)
                .hasMessage("The session sent no usage windows. The provider subscription expects them.");
    }

    @Test
    void acceptsASuccessfulSessionWithUsageWindows() {
        UsageWindow window = new UsageWindow("five_hour", 0.2, NOW.plusSeconds(3600), "allowed", NOW);
        SessionReport report = new SessionReport(true, "ok", Optional.of("none"), Optional.empty(), List.of(window));

        assertThatCode(() -> provider.verifyFinishedSession(report)).doesNotThrowAnyException();
    }

    @Test
    void acceptsAFailedSessionWithoutUsageWindows() {
        SessionReport report = new SessionReport(false, "error", Optional.of("none"), Optional.empty(), List.of());

        assertThatCode(() -> provider.verifyFinishedSession(report)).doesNotThrowAnyException();
    }

    @Test
    void addsNoArgumentAndHasTheLiveMode() {
        assertThat(provider.phaseArguments()).isEmpty();
        assertThat(provider.hasLiveMode()).isTrue();
        assertThat(provider.readsUsageWindows()).isTrue();
        assertThat(provider.describe())
                .isEqualTo("The provider is subscription: the sessions use the claude login of the owner.");
    }
}
