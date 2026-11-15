package com.meterian.factory.configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.meterian.factory.web.login.PasswordHash;
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
    void theLiveDirectoryDefaultsToTheWorkDirectory() throws Exception {
        FactoryConfiguration configuration = load(REQUIRED_KEYS + MERGE_SECTION);

        assertThat(configuration.liveDirectory()).isEqualTo(Path.of("/work/factory/live"));
    }

    @Test
    void readsTheLiveDirectory() throws Exception {
        FactoryConfiguration configuration = load(REQUIRED_KEYS + MERGE_SECTION + "live-directory: /work/live-files\n");

        assertThat(configuration.liveDirectory()).isEqualTo(Path.of("/work/live-files"));
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
    void loadsAnOldConfigurationWithTheAccountsSection() throws Exception {
        String withAccounts = REQUIRED_KEYS + """
                accounts:
                  allowed: [bbossola]
                """;

        FactoryConfiguration configuration = load(withAccounts + MERGE_SECTION);

        assertThat(configuration.target().fullName()).isEqualTo("MeterianHQ/heidi-fix");
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

    private static final String WEAK_HASH = "pbkdf2-sha256:1000:AAAAAAAAAAAAAAAAAAAAAA==:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";

    private String createStrongHash() {
        return PasswordHash.create("correct horse battery".toCharArray(), 100_000).format();
    }

    @Test
    void theDefaultTerminalCommandIsGnomeTerminal() throws Exception {
        FactoryConfiguration configuration = load(REQUIRED_KEYS + MERGE_SECTION);

        assertThat(configuration.terminalCommand()).isEqualTo(List.of("gnome-terminal", "--"));
    }

    @Test
    void readsTheTerminalCommand() throws Exception {
        FactoryConfiguration configuration = load(REQUIRED_KEYS + MERGE_SECTION
                + "live:\n  terminal-command: [xterm, -e]\n");

        assertThat(configuration.terminalCommand()).isEqualTo(List.of("xterm", "-e"));
    }

    @Test
    void anEmptyTerminalCommandStopsTheStart() {
        assertThatThrownBy(() -> load(REQUIRED_KEYS + MERGE_SECTION + "live:\n  terminal-command: []\n"))
                .isInstanceOf(ConfigurationException.class)
                .hasMessage("live.terminal-command must have at least one value.");
    }

    @Test
    void theWebSectionIsOptional() throws Exception {
        FactoryConfiguration configuration = load(REQUIRED_KEYS + MERGE_SECTION);

        assertThat(configuration.web()).isEmpty();
    }

    @Test
    void theWebPageListensOnTheLoopbackAddressByDefault() throws Exception {
        String hash = createStrongHash();

        FactoryConfiguration configuration = load(REQUIRED_KEYS + MERGE_SECTION
                + "web:\n  password-hash: \"" + hash + "\"\n");

        WebSettings web = configuration.web().orElseThrow();
        assertThat(web.address()).isEqualTo("127.0.0.1");
        assertThat(web.port()).isEqualTo(8090);
        assertThat(web.passwordHash().format()).isEqualTo(hash);
    }

    @Test
    void readsTheAddressAndThePort() throws Exception {
        FactoryConfiguration configuration = load(REQUIRED_KEYS + MERGE_SECTION
                + "web:\n  address: 0.0.0.0\n  port: 9000\n  password-hash: \"" + createStrongHash() + "\"\n");

        assertThat(configuration.web().orElseThrow().address()).isEqualTo("0.0.0.0");
        assertThat(configuration.web().orElseThrow().port()).isEqualTo(9000);
    }

    @Test
    void aWebSectionNeedsAPasswordHash() {
        assertThatThrownBy(() -> load(REQUIRED_KEYS + MERGE_SECTION + "web:\n  port: 8090\n"))
                .isInstanceOf(ConfigurationException.class)
                .hasMessage("Configuration key web.password-hash is missing.");
    }

    @Test
    void aHashInAnotherFormatIsRefused() {
        assertThatThrownBy(() -> load(REQUIRED_KEYS + MERGE_SECTION + "web:\n  password-hash: plain\n"))
                .isInstanceOf(ConfigurationException.class)
                .hasMessage("web.password-hash is not valid. Make a new one with hash-password.");
    }

    @Test
    void aWeakHashIsRefused() {
        assertThatThrownBy(() -> load(REQUIRED_KEYS + MERGE_SECTION
                + "web:\n  password-hash: \"" + WEAK_HASH + "\"\n"))
                .isInstanceOf(ConfigurationException.class)
                .hasMessage("web.password-hash is too weak. Make a new one with hash-password.");
    }

    @Test
    void aPortOutsideTheRangeIsRefused() {
        for (int port : new int[] {0, 65536, -1}) {
            assertThatThrownBy(() -> load(REQUIRED_KEYS + MERGE_SECTION + "web:\n  port: " + port
                    + "\n  password-hash: \"" + createStrongHash() + "\"\n"))
                    .isInstanceOf(ConfigurationException.class)
                    .hasMessage("web.port must be from 1 to 65535");
        }
    }

    private FactoryConfiguration load(String content) throws ConfigurationException, IOException {
        Path file = temporaryDirectory.resolve("factory.yaml");
        Files.writeString(file, content);
        return ConfigurationLoader.load(file);
    }
}
