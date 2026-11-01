package com.meterian.factory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MainTest {

    private static final String USAGE = "Usage: java -jar claude-ai-factory.jar run --config <file>";

    @TempDir
    Path temporaryDirectory;

    private final ByteArrayOutputStream errorBytes = new ByteArrayOutputStream();
    private final PrintStream errors = new PrintStream(errorBytes, true, StandardCharsets.UTF_8);

    private String errorText() {
        return errorBytes.toString(StandardCharsets.UTF_8);
    }

    private Path writeConfiguration(String claudeCommand) throws IOException {
        Path file = temporaryDirectory.resolve("factory.yaml");
        Files.writeString(file, """
                target:
                  owner: MeterianHQ
                  name: sample
                  clone: %s
                  build:
                    command: "true"
                merge:
                  target-branch: main
                accounts:
                  allowed: [bbossola]
                claude:
                  command: %s
                work-directory: %s
                gatekeeper-directory: %s
                """.formatted(temporaryDirectory.resolve("clone"), claudeCommand,
                temporaryDirectory.resolve("work"), temporaryDirectory.resolve("gatekeeper.git")));
        return file;
    }

    @Test
    void readsTheConfigurationPath() throws Main.StartupException {
        Path path = Main.readConfigurationPath(new String[] {"run", "--config", "factory.yaml"});

        assertThat(path).isEqualTo(Path.of("factory.yaml"));
    }

    @Test
    void refusesOtherArguments() {
        for (String[] arguments : List.of(new String[] {}, new String[] {"start", "--config", "factory.yaml"},
                new String[] {"run", "--config"}, new String[] {"run", "--file", "factory.yaml"})) {
            assertThatThrownBy(() -> Main.readConfigurationPath(arguments)).hasMessage(USAGE);
        }
    }

    @Test
    void refusesToStartWithAnApiKey() {
        int exitCode = Main.run(new String[] {"run", "--config", "missing.yaml"},
                Map.of("ANTHROPIC_API_KEY", "secret", "PATH", "/usr/bin:/bin"), errors);

        assertThat(exitCode).isEqualTo(1);
        assertThat(errorText()).contains("Remove ANTHROPIC_API_KEY: the factory uses only the Claude subscription.")
                .doesNotContain("secret");
    }

    @Test
    void refusesToStartWithoutAUsableArgument() {
        int exitCode = Main.run(new String[] {}, Map.of(), errors);

        assertThat(exitCode).isEqualTo(1);
        assertThat(errorText()).contains(USAGE);
    }

    @Test
    void refusesToStartWhenACommandIsMissing() throws IOException {
        Path configuration = writeConfiguration("no-such-claude-for-the-factory");

        int exitCode = Main.run(new String[] {"run", "--config", configuration.toString()},
                Map.of("PATH", System.getenv("PATH")), errors);

        assertThat(exitCode).isEqualTo(1);
        assertThat(errorText())
                .contains("The factory needs these commands on the PATH: no-such-claude-for-the-factory");
        assertThat(temporaryDirectory.resolve("gatekeeper.git")).doesNotExist();
    }

    @Test
    void findsTheMissingCommands() throws IOException {
        Path binDirectory = Files.createDirectories(temporaryDirectory.resolve("bin"));
        Path tool = Files.writeString(binDirectory.resolve("tool"), "#!/bin/sh\n");
        assertThat(tool.toFile().setExecutable(true)).isTrue();
        Files.writeString(binDirectory.resolve("not-executable"), "text");

        List<String> missing = Main.findMissingCommands(
                List.of("tool", "not-executable", "absent", tool.toString(), "/no/such/tool"), binDirectory.toString());

        assertThat(missing).containsExactly("not-executable", "absent", "/no/such/tool");
    }

    @Test
    void readsTheTokenFromTheEnvironmentFirst() throws Main.StartupException {
        String token = Main.readToken(Map.of("FACTORY_GITHUB_TOKEN", "environment-token"),
                () -> Optional.of("gh-token"));

        assertThat(token).isEqualTo("environment-token");
    }

    @Test
    void readsTheTokenFromGhWhenTheEnvironmentHasNone() throws Main.StartupException {
        assertThat(Main.readToken(Map.of(), () -> Optional.of("gh-token"))).isEqualTo("gh-token");
        assertThat(Main.readToken(Map.of("FACTORY_GITHUB_TOKEN", " "), () -> Optional.of("gh-token")))
                .isEqualTo("gh-token");
    }

    @Test
    void refusesToStartWithoutAToken() {
        assertThatThrownBy(() -> Main.readToken(Map.of(), Optional::empty))
                .isInstanceOf(Main.StartupException.class)
                .hasMessage("Set FACTORY_GITHUB_TOKEN, or log in with gh auth login.");
    }

    @Test
    void createsAClientThatFollowsNoRedirect() {
        HttpClient client = Main.createHttpClient();

        assertThat(client.followRedirects()).isEqualTo(HttpClient.Redirect.NEVER);
        assertThat(client.connectTimeout()).contains(Duration.ofSeconds(30));
    }
}
