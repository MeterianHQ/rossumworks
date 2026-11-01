package com.meterian.factory.configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigurationLoaderTest {

    private static final String REQUIRED_KEYS = """
            target:
              owner: MeterianHQ
              name: heidi-fix
              clone: /work/heidi-fix
              build:
                command: ./mvnw verify
            accounts:
              allowed: [bbossola]
            work-directory: /work/factory
            gatekeeper-directory: /work/gatekeeper.git
            """;

    private static final String MERGE_SECTION = """
            merge:
              target-branch: main
            """;

    @TempDir
    Path temporaryDirectory;

    @Test
    void loadsTheExampleConfiguration() throws Exception {
        FactoryConfiguration configuration = ConfigurationLoader.load(Path.of("factory.example.yaml"));

        assertThat(configuration.target().fullName()).isEqualTo("MeterianHQ/heidi-fix");
        assertThat(configuration.target().sshUrl()).isEqualTo("git@github.com:MeterianHQ/heidi-fix.git");
        assertThat(configuration.target().buildEnvironment()).containsEntry("LANG", "C.utf8");
        assertThat(configuration.mergeTargetBranch()).isEqualTo("main");
        assertThat(configuration.allowedAccounts()).containsExactly("bbossola");
        assertThat(configuration.limits().pollInterval()).isEqualTo(Duration.ofSeconds(60));
        assertThat(configuration.promptDirectory()).isEmpty();
    }

    @Test
    void appliesTheDefaultsForOptionalKeys() throws Exception {
        FactoryConfiguration configuration = load(REQUIRED_KEYS + MERGE_SECTION);

        assertThat(configuration.limits()).isEqualTo(new Limits(2, 2, Duration.ofSeconds(60), 0.90));
        assertThat(configuration.isolationMode()).isEqualTo(IsolationMode.SAME_USER);
        assertThat(configuration.claude().command()).isEqualTo("claude");
        assertThat(configuration.claude().allowedTools()).contains("Bash(./mvnw *)");
        assertThat(configuration.claude().disallowedTools()).contains("Bash(git push*)");
    }

    @Test
    void acceptsThePollIntervalInMinutes() throws Exception {
        FactoryConfiguration configuration = load(REQUIRED_KEYS + MERGE_SECTION + """
                limits:
                  poll-interval: 5m
                """);

        assertThat(configuration.limits().pollInterval()).isEqualTo(Duration.ofMinutes(5));
    }

    @Test
    void namesTheMissingRequiredKey() {
        assertThatThrownBy(() -> load(REQUIRED_KEYS))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("merge.target-branch");
    }

    @Test
    void refusesTheSeparateUserMode() {
        assertThatThrownBy(() -> load(REQUIRED_KEYS + MERGE_SECTION + """
                isolation:
                  mode: separate-user
                """))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("isolation.mode separate-user is not available in this version");
    }

    @Test
    void refusesAnEmptyListOfAccounts() {
        String withoutAccounts = REQUIRED_KEYS.replace("[bbossola]", "[]");

        assertThatThrownBy(() -> load(withoutAccounts + MERGE_SECTION))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("accounts.allowed");
    }

    @Test
    void refusesAThresholdOutsideZeroToOne() {
        assertThatThrownBy(() -> load(REQUIRED_KEYS + MERGE_SECTION + """
                limits:
                  usage-threshold: 1.5
                """))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("limits.usage-threshold");
    }

    @Test
    void refusesAThresholdThatIsNotANumber() {
        assertThatThrownBy(() -> load(REQUIRED_KEYS + MERGE_SECTION + """
                limits:
                  usage-threshold: .nan
                """))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("Malformed numeric value '.nan'");
    }

    @Test
    void refusesAPollIntervalBelowTenSeconds() {
        assertThatThrownBy(() -> load(REQUIRED_KEYS + MERGE_SECTION + """
                limits:
                  poll-interval: 9s
                """))
                .isInstanceOf(ConfigurationException.class)
                .hasMessage("Configuration key limits.poll-interval must be 10 seconds or more.");
    }

    @Test
    void acceptsAPollIntervalOfTenSeconds() throws Exception {
        FactoryConfiguration configuration = load(REQUIRED_KEYS + MERGE_SECTION + """
                limits:
                  poll-interval: 10s
                """);

        assertThat(configuration.limits().pollInterval()).isEqualTo(Duration.ofSeconds(10));
    }

    @Test
    void reportsAFileThatDoesNotExist() {
        assertThatThrownBy(() -> ConfigurationLoader.load(temporaryDirectory.resolve("missing.yaml")))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("missing.yaml");
    }

    private FactoryConfiguration load(String content) throws ConfigurationException, IOException {
        Path file = temporaryDirectory.resolve("factory.yaml");
        Files.writeString(file, content);
        return ConfigurationLoader.load(file);
    }
}
