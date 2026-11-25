package com.meterian.factory.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.meterian.factory.agent.CredentialMismatchException;
import com.meterian.factory.agent.SessionReport;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ApiKeyProviderTest {

    private static final Path SETTINGS_FILE = Path.of("/work/credentials/api-key-settings.json");
    private final ApiKeyProvider provider = new ApiKeyProvider(SETTINGS_FILE, new BigDecimal("20"));

    @Test
    void acceptsOnlyTheHelperSource() {
        assertThatCode(() -> provider.verifyCredentialSource("apiKeyHelper")).doesNotThrowAnyException();
        assertThatThrownBy(() -> provider.verifyCredentialSource("none"))
                .isInstanceOf(CredentialMismatchException.class)
                .hasMessage("The session uses the credential source none. "
                        + "The provider anthropic-api-key expects apiKeyHelper.");
        assertThatThrownBy(() -> provider.verifyCredentialSource("ANTHROPIC_API_KEY"))
                .isInstanceOf(CredentialMismatchException.class);
    }

    @Test
    void acceptsASuccessfulSessionWithoutUsageWindows() {
        SessionReport report = new SessionReport(true, "ok", Optional.of("apiKeyHelper"), Optional.empty(),
                List.of());

        assertThatCode(() -> provider.verifyFinishedSession(report)).doesNotThrowAnyException();
    }

    @Test
    void givesTheSettingsFileToThePhases() {
        assertThat(provider.phaseArguments()).containsExactly("--settings", SETTINGS_FILE.toString());
    }

    @Test
    void hasNoLiveModeAndNoUsageWindows() {
        assertThat(provider.hasLiveMode()).isFalse();
        assertThat(provider.readsUsageWindows()).isFalse();
    }

    @Test
    void describesTheProviderAndTheBudget() {
        assertThat(provider.describe()).isEqualTo(
                "The provider is anthropic-api-key: the sessions use an Anthropic API key. "
                        + "The daily budget is $20.00.");
    }
}
