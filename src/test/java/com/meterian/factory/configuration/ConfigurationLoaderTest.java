package com.meterian.factory.configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.meterian.factory.web.login.PasswordHash;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigurationLoaderTest {

    private static final String REQUIRED_KEYS = """
            repository: MeterianHQ/heidi-fix
            work-directory: /work/factory
            gatekeeper-directory: /work/gatekeeper.git
            """;

    @TempDir
    Path temporaryDirectory;

    @Test
    void loadsTheExampleConfiguration() throws Exception {
        FactoryConfiguration configuration = ConfigurationLoader.load(Path.of("factory.example.yaml"));

        assertThat(configuration.repository().fullName()).isEqualTo("MeterianHQ/heidi-fix");
        assertThat(configuration.repository().sshUrl()).isEqualTo("git@github.com:MeterianHQ/heidi-fix.git");
        assertThat(configuration.buildEnvironment()).containsKey("JAVA_HOME");
        assertThat(configuration.limits().pollInterval()).isEqualTo(Duration.ofSeconds(60));
    }

    @Test
    void loadsTheNewForm() throws Exception {
        String hash = createStrongHash();

        FactoryConfiguration configuration = load("""
                repository: MeterianHQ/heidi-fix
                work-directory: /work/factory
                gatekeeper-directory: /work/gatekeeper.git
                live-directory: /work/live-files
                limits:
                  parallel-issues: 3
                  poll-interval: 5m
                  usage-threshold: 0.75
                build:
                  environment:
                    JAVA_HOME: /jdk
                    LANG: C.utf8
                isolation:
                  mode: same-user
                claude:
                  command: /bin/claude
                  disallowed-tools: [WebFetch]
                live:
                  terminal-command: [xterm, -e]
                web:
                  address: 0.0.0.0
                  port: 9000
                  password-hash: "%s"
                """.formatted(hash));

        assertThat(configuration.repository()).isEqualTo(new RepositoryName("MeterianHQ", "heidi-fix"));
        assertThat(configuration.workDirectory()).isEqualTo(Path.of("/work/factory"));
        assertThat(configuration.cloneDirectory()).isEqualTo(Path.of("/work/factory/repository"));
        assertThat(configuration.gatekeeperDirectory()).isEqualTo(Path.of("/work/gatekeeper.git"));
        assertThat(configuration.liveDirectory()).isEqualTo(Path.of("/work/live-files"));
        assertThat(configuration.limits()).isEqualTo(new WorkerLimits(3, Duration.ofMinutes(5), 0.75));
        assertThat(configuration.buildEnvironment()).isEqualTo(Map.of("JAVA_HOME", "/jdk", "LANG", "C.utf8"));
        assertThat(configuration.isolationMode()).isEqualTo(IsolationMode.SAME_USER);
        assertThat(configuration.claude()).isEqualTo(new ClaudeSettings("/bin/claude", List.of("WebFetch")));
        assertThat(configuration.terminalCommand()).isEqualTo(List.of("xterm", "-e"));
        assertThat(configuration.web().orElseThrow().port()).isEqualTo(9000);
    }

    @Test
    void usesTheDefaultsOfTheLimits() throws Exception {
        FactoryConfiguration configuration = load(REQUIRED_KEYS);

        assertThat(configuration.limits()).isEqualTo(new WorkerLimits(2, Duration.ofSeconds(60), 0.90));
        assertThat(configuration.buildEnvironment()).isEmpty();
        assertThat(configuration.isolationMode()).isEqualTo(IsolationMode.SAME_USER);
        assertThat(configuration.claude().command()).isEqualTo("claude");
        assertThat(configuration.claude().disallowedTools()).contains("Bash(git push*)");
    }

    @Test
    void refusesTheOldForm() throws Exception {
        Map<String, String> oldForms = Map.of(
                "target", "target:\n  owner: MeterianHQ\n",
                "merge", "merge:\n  target-branch: main\n",
                "accounts", "accounts:\n  allowed: [bbossola]\n",
                "prompts-directory", "prompts-directory: /prompts\n",
                "limits.fix-loops", "limits:\n  fix-loops: 2\n",
                "claude.allowed-tools", "claude:\n  allowed-tools: [Read]\n");

        for (Map.Entry<String, String> oldForm : oldForms.entrySet()) {
            Path file = temporaryDirectory.resolve("factory.yaml");
            Files.writeString(file, REQUIRED_KEYS + oldForm.getValue());

            assertThatThrownBy(() -> ConfigurationLoader.load(file))
                    .isInstanceOf(ConfigurationException.class)
                    .hasMessage("The configuration file " + file + " has the old form (the key "
                            + oldForm.getKey() + "). The workflow, the build, and the merge are now "
                            + "in the folder .factory of the repository. "
                            + "Run java -jar <path of the jar> create to write the new configuration.");
        }
    }

    @Test
    void refusesARepositoryWithoutAnOwner() {
        assertThatThrownBy(() -> load(REQUIRED_KEYS.replace("MeterianHQ/heidi-fix", "heidi-fix")))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("repository")
                .hasMessageContaining("owner/name");
    }

    @Test
    void theLiveDirectoryDefaultsToTheWorkDirectory() throws Exception {
        FactoryConfiguration configuration = load(REQUIRED_KEYS);

        assertThat(configuration.liveDirectory()).isEqualTo(Path.of("/work/factory/live"));
    }

    @Test
    void acceptsThePollIntervalInMinutes() throws Exception {
        FactoryConfiguration configuration = load(REQUIRED_KEYS + """
                limits:
                  poll-interval: 5m
                """);

        assertThat(configuration.limits().pollInterval()).isEqualTo(Duration.ofMinutes(5));
    }

    @Test
    void namesTheMissingRequiredKey() {
        assertThatThrownBy(() -> load("work-directory: /work\n"))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("repository");
    }

    @Test
    void refusesTheSeparateUserMode() {
        assertThatThrownBy(() -> load(REQUIRED_KEYS + """
                isolation:
                  mode: separate-user
                """))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("isolation.mode separate-user is not available in this version");
    }

    @Test
    void refusesAThresholdOutsideZeroToOne() {
        assertThatThrownBy(() -> load(REQUIRED_KEYS + """
                limits:
                  usage-threshold: 1.5
                """))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("limits.usage-threshold");
    }

    @Test
    void refusesAThresholdThatIsNotANumber() {
        assertThatThrownBy(() -> load(REQUIRED_KEYS + """
                limits:
                  usage-threshold: .nan
                """))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("Malformed numeric value '.nan'");
    }

    @Test
    void refusesAPollIntervalBelowTenSeconds() {
        assertThatThrownBy(() -> load(REQUIRED_KEYS + """
                limits:
                  poll-interval: 9s
                """))
                .isInstanceOf(ConfigurationException.class)
                .hasMessage("Configuration key limits.poll-interval must be 10 seconds or more.");
    }

    @Test
    void acceptsAPollIntervalOfTenSeconds() throws Exception {
        FactoryConfiguration configuration = load(REQUIRED_KEYS + """
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
        FactoryConfiguration configuration = load(REQUIRED_KEYS);

        assertThat(configuration.terminalCommand()).isEqualTo(List.of("gnome-terminal", "--"));
    }

    @Test
    void readsTheTerminalCommand() throws Exception {
        FactoryConfiguration configuration = load(REQUIRED_KEYS
                + "live:\n  terminal-command: [xterm, -e]\n");

        assertThat(configuration.terminalCommand()).isEqualTo(List.of("xterm", "-e"));
    }

    @Test
    void anEmptyTerminalCommandStopsTheStart() {
        assertThatThrownBy(() -> load(REQUIRED_KEYS + "live:\n  terminal-command: []\n"))
                .isInstanceOf(ConfigurationException.class)
                .hasMessage("live.terminal-command must have at least one value.");
    }

    @Test
    void theWebSectionIsOptional() throws Exception {
        FactoryConfiguration configuration = load(REQUIRED_KEYS);

        assertThat(configuration.web()).isEmpty();
    }

    @Test
    void theWebPageListensOnTheLoopbackAddressByDefault() throws Exception {
        String hash = createStrongHash();

        FactoryConfiguration configuration = load(REQUIRED_KEYS
                + "web:\n  password-hash: \"" + hash + "\"\n");

        WebSettings web = configuration.web().orElseThrow();
        assertThat(web.address()).isEqualTo("127.0.0.1");
        assertThat(web.port()).isEqualTo(8090);
        assertThat(web.passwordHash().orElseThrow().format()).isEqualTo(hash);
    }

    @Test
    void aWebSectionWithoutAPasswordHashGivesAPageWithoutALogin() throws Exception {
        FactoryConfiguration configuration = load(REQUIRED_KEYS + "web:\n  port: 9000\n");

        WebSettings web = configuration.web().orElseThrow();
        assertThat(web.port()).isEqualTo(9000);
        assertThat(web.passwordHash()).isEmpty();
    }

    @Test
    void readsTheAddressAndThePort() throws Exception {
        FactoryConfiguration configuration = load(REQUIRED_KEYS
                + "web:\n  address: 0.0.0.0\n  port: 9000\n  password-hash: \"" + createStrongHash() + "\"\n");

        assertThat(configuration.web().orElseThrow().address()).isEqualTo("0.0.0.0");
        assertThat(configuration.web().orElseThrow().port()).isEqualTo(9000);
    }

    @Test
    void aHashInAnotherFormatIsRefused() {
        assertThatThrownBy(() -> load(REQUIRED_KEYS + "web:\n  password-hash: plain\n"))
                .isInstanceOf(ConfigurationException.class)
                .hasMessage("web.password-hash is not valid. Make a new one with hash-password.");
    }

    @Test
    void aWeakHashIsRefused() {
        assertThatThrownBy(() -> load(REQUIRED_KEYS
                + "web:\n  password-hash: \"" + WEAK_HASH + "\"\n"))
                .isInstanceOf(ConfigurationException.class)
                .hasMessage("web.password-hash is too weak. Make a new one with hash-password.");
    }

    @Test
    void aPortOutsideTheRangeIsRefused() {
        for (int port : new int[] {0, 65536, -1}) {
            assertThatThrownBy(() -> load(REQUIRED_KEYS + "web:\n  port: " + port
                    + "\n  password-hash: \"" + createStrongHash() + "\"\n"))
                    .isInstanceOf(ConfigurationException.class)
                    .hasMessage("web.port must be from 1 to 65535");
        }
    }

    @Test
    void theProviderIsTheSubscriptionByDefault() throws Exception {
        FactoryConfiguration configuration = load(REQUIRED_KEYS);

        assertThat(configuration.claude().provider()).isEqualTo(ProviderName.SUBSCRIPTION);
        assertThat(configuration.limits().dailyBudgetUsd()).isEmpty();
    }

    @Test
    void readsTheApiKeyProviderAndTheBudget() throws Exception {
        FactoryConfiguration configuration = load(REQUIRED_KEYS + """
                claude:
                  provider: anthropic-api-key
                limits:
                  daily-budget-usd: 20.50
                """);

        assertThat(configuration.claude().provider()).isEqualTo(ProviderName.ANTHROPIC_API_KEY);
        assertThat(configuration.limits().dailyBudgetUsd()).hasValueSatisfying(
                budget -> assertThat(budget).isEqualByComparingTo("20.50"));
    }

    @Test
    void refusesAnUnknownProvider() {
        assertThatThrownBy(() -> load(REQUIRED_KEYS + """
                claude:
                  provider: ollama
                """))
                .isInstanceOf(ConfigurationException.class)
                .hasMessage("Configuration key claude.provider must be subscription or anthropic-api-key.");
    }

    @Test
    void theApiKeyProviderNeedsTheBudget() {
        assertThatThrownBy(() -> load(REQUIRED_KEYS + """
                claude:
                  provider: anthropic-api-key
                """))
                .isInstanceOf(ConfigurationException.class)
                .hasMessage("The provider anthropic-api-key needs the key limits.daily-budget-usd.");
    }

    @Test
    void refusesABudgetOfZero() {
        assertThatThrownBy(() -> load(REQUIRED_KEYS + """
                limits:
                  daily-budget-usd: 0
                """))
                .isInstanceOf(ConfigurationException.class)
                .hasMessage("Configuration key limits.daily-budget-usd must be more than 0.");
    }

    @Test
    void refusesAnInfiniteBudget() {
        assertThatThrownBy(() -> load(REQUIRED_KEYS + """
                limits:
                  daily-budget-usd: 1.0e999
                """))
                .isInstanceOf(ConfigurationException.class)
                .hasMessage("Configuration key limits.daily-budget-usd must be more than 0.");
    }

    private FactoryConfiguration load(String content) throws ConfigurationException, IOException {
        Path file = temporaryDirectory.resolve("factory.yaml");
        Files.writeString(file, content);
        return ConfigurationLoader.load(file);
    }
}
