package com.meterian.factory.setup;

import java.math.BigDecimal;
import com.meterian.factory.configuration.ProviderName;
import com.meterian.factory.configuration.ConfigurationException;
import com.meterian.factory.configuration.ConfigurationLoader;
import com.meterian.factory.configuration.FactoryConfiguration;
import com.meterian.factory.configuration.RepositoryName;
import com.meterian.factory.web.login.PasswordHash;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkerConfigurationWriterTest {

    @TempDir
    Path workDirectory;

    @Test
    void theFileLoadsWithTheConfigurationLoader() throws IOException, ConfigurationException {
        String hash = PasswordHash.create("a long password".toCharArray()).format();
        List<String> terminal = List.of("my term #1", "-e: \"x\"", "back\\slash");
        WorkerSettings settings = new WorkerSettings(
                new RepositoryName("octocat", "hello-world"), workDirectory,
                Optional.of(new WebPage("0.0.0.0", 9001, Optional.of(hash))),
                Optional.of(terminal), Optional.of("/opt/jdk #21: home"));

        WorkerConfigurationWriter.write(settings);
        FactoryConfiguration configuration = ConfigurationLoader.load(settings.configurationFile());

        assertThat(configuration.repository().fullName()).isEqualTo("octocat/hello-world");
        assertThat(configuration.workDirectory()).isEqualTo(workDirectory);
        assertThat(configuration.gatekeeperDirectory()).isEqualTo(workDirectory.resolve("gatekeeper.git"));
        assertThat(configuration.web()).isPresent();
        assertThat(configuration.web().get().address()).isEqualTo("0.0.0.0");
        assertThat(configuration.web().get().port()).isEqualTo(9001);
        assertThat(configuration.web().get().passwordHash().orElseThrow().format()).isEqualTo(hash);
        assertThat(configuration.terminalCommand()).isEqualTo(terminal);
        assertThat(configuration.buildEnvironment()).containsEntry("JAVA_HOME", "/opt/jdk #21: home");
    }

    @Test
    void theMinimalFileLoads() throws IOException, ConfigurationException {
        WorkerSettings settings = new WorkerSettings(
                new RepositoryName("octocat", "hello-world"), workDirectory,
                Optional.empty(), Optional.empty(), Optional.empty());

        WorkerConfigurationWriter.write(settings);
        FactoryConfiguration configuration = ConfigurationLoader.load(settings.configurationFile());

        assertThat(configuration.web()).isEmpty();
        assertThat(configuration.buildEnvironment()).isEmpty();
        assertThat(configuration.terminalCommand()).isEqualTo(FactoryConfiguration.DEFAULT_TERMINAL_COMMAND);
    }

    @Test
    void aPageWithoutAPasswordLoadsWithoutAPasswordHash() throws IOException, ConfigurationException {
        WorkerSettings settings = new WorkerSettings(
                new RepositoryName("octocat", "hello-world"), workDirectory,
                Optional.of(new WebPage("127.0.0.1", 8090, Optional.empty())), Optional.empty(), Optional.empty());

        WorkerConfigurationWriter.write(settings);
        FactoryConfiguration configuration = ConfigurationLoader.load(settings.configurationFile());

        assertThat(configuration.web()).isPresent();
        assertThat(configuration.web().get().passwordHash()).isEmpty();
    }

    @Test
    void neverOverwritesAFile() throws IOException {
        WorkerSettings first = new WorkerSettings(
                new RepositoryName("octocat", "hello-world"), workDirectory,
                Optional.empty(), Optional.empty(), Optional.empty());
        WorkerSettings second = new WorkerSettings(
                new RepositoryName("Other", "project"), workDirectory,
                Optional.empty(), Optional.empty(), Optional.empty());
        WorkerConfigurationWriter.write(first);

        assertThatThrownBy(() -> WorkerConfigurationWriter.write(second))
                .isInstanceOf(FileAlreadyExistsException.class);
        assertThat(Files.readString(first.configurationFile())).isEqualTo(WorkerConfigurationWriter.render(first));
    }

    @Test
    void writesTheApiKeyProviderAndTheBudget() throws Exception {
        WorkerSettings settings = new WorkerSettings(RepositoryName.parse("MeterianHQ/sample"), Path.of("/work"),
                Optional.empty(), Optional.empty(), Optional.empty(),
                new ProviderChoice(ProviderName.ANTHROPIC_API_KEY, Optional.of(new BigDecimal("20"))));

        String text = WorkerConfigurationWriter.render(settings);

        assertThat(text).contains("claude:\n  provider: anthropic-api-key\nlimits:\n  daily-budget-usd: 20\n");
    }

    @Test
    void theApiKeyProviderLoadsWithTheConfigurationLoader() throws Exception {
        WorkerSettings settings = new WorkerSettings(RepositoryName.parse("MeterianHQ/sample"), workDirectory,
                Optional.empty(), Optional.empty(), Optional.empty(),
                new ProviderChoice(ProviderName.ANTHROPIC_API_KEY, Optional.of(new BigDecimal("20"))));

        WorkerConfigurationWriter.write(settings);
        FactoryConfiguration configuration = ConfigurationLoader.load(settings.configurationFile());

        assertThat(configuration.claude().provider()).isEqualTo(ProviderName.ANTHROPIC_API_KEY);
        assertThat(configuration.limits().dailyBudgetUsd()).hasValueSatisfying(
                budget -> assertThat(budget).isEqualByComparingTo("20"));
    }

    @Test
    void theSubscriptionGivesOnlyAComment() throws Exception {
        WorkerSettings settings = new WorkerSettings(RepositoryName.parse("MeterianHQ/sample"), Path.of("/work"),
                Optional.empty(), Optional.empty(), Optional.empty());

        String text = WorkerConfigurationWriter.render(settings);

        assertThat(text).contains("# claude:\n#   provider: anthropic-api-key")
                .doesNotContain("\nclaude:").doesNotContain("\nlimits:");
    }
}
