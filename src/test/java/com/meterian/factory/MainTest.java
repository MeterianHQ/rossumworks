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

    private static final String USAGE = "Usage: java -jar claude-ai-factory.jar run --config <file>\n"
            + "       java -jar claude-ai-factory.jar hash-password";

    @TempDir
    Path temporaryDirectory;

    private final ByteArrayOutputStream errorBytes = new ByteArrayOutputStream();
    private final PrintStream errors = new PrintStream(errorBytes, true, StandardCharsets.UTF_8);
    private final ByteArrayOutputStream outputBytes = new ByteArrayOutputStream();
    private final PrintStream output = new PrintStream(outputBytes, true, StandardCharsets.UTF_8);

    private int runMain(String[] arguments, Map<String, String> environment) {
        return Main.run(arguments, environment, errors,
                () -> {
                    throw new AssertionError("The test reached the token");
                },
                (configuration, token, errorStream) -> {
                    throw new AssertionError("The test reached the worker");
                });
    }

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
        int exitCode = runMain(new String[] {"run", "--config", "missing.yaml"},
                Map.of("ANTHROPIC_API_KEY", "secret", "PATH", "/usr/bin:/bin"));

        assertThat(exitCode).isEqualTo(1);
        assertThat(errorText()).contains("Remove ANTHROPIC_API_KEY: the factory uses only the Claude subscription.")
                .doesNotContain("secret");
    }

    @Test
    void refusesToStartWithoutAUsableArgument() {
        int exitCode = runMain(new String[] {}, Map.of());

        assertThat(exitCode).isEqualTo(1);
        assertThat(errorText()).contains(USAGE);
    }

    @Test
    void refusesToStartWhenACommandIsMissing() throws IOException {
        Path configuration = writeConfiguration("no-such-claude-for-the-factory");

        int exitCode = runMain(new String[] {"run", "--config", configuration.toString()},
                Map.of("PATH", System.getenv("PATH")));

        assertThat(exitCode).isEqualTo(1);
        assertThat(errorText())
                .contains("The factory needs these commands on the PATH: no-such-claude-for-the-factory");
        assertThat(temporaryDirectory.resolve("gatekeeper.git")).doesNotExist();
    }

    @Test
    void refusesToStartWhenTmuxAndJqAreMissing() throws IOException {
        Path binDirectory = Files.createDirectories(temporaryDirectory.resolve("bin"));
        for (String command : List.of("claude", "git", "setsid", "kill")) {
            Path file = Files.writeString(binDirectory.resolve(command), "#!/bin/sh\n");
            assertThat(file.toFile().setExecutable(true)).isTrue();
        }
        Path configuration = writeConfiguration("claude");

        int exitCode = runMain(new String[] {"run", "--config", configuration.toString()},
                Map.of("PATH", binDirectory.toString()));

        assertThat(exitCode).isEqualTo(1);
        assertThat(errorText()).contains("The factory needs these commands on the PATH: tmux, jq");
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
    void readsTheTokenFromTheOutputOfACommand() throws Main.StartupException {
        Optional<String> token = Main.readTokenFromCommand(List.of("sh", "-c", "echo command-token"),
                Duration.ofSeconds(30));

        assertThat(token).contains("command-token");
    }

    @Test
    void givesNoTokenWhenTheCommandFails() throws Main.StartupException {
        assertThat(Main.readTokenFromCommand(List.of("sh", "-c", "echo partial; exit 1"), Duration.ofSeconds(30)))
                .isEmpty();
        assertThat(Main.readTokenFromCommand(List.of("no-such-gh-for-the-factory"), Duration.ofSeconds(30)))
                .isEmpty();
    }

    @Test
    void stopsATokenCommandThatDoesNotAnswer() {
        assertThatThrownBy(() -> Main.readTokenFromCommand(List.of("sleep", "20"), Duration.ofMillis(300)))
                .isInstanceOf(Main.StartupException.class)
                .hasMessage("The command sleep 20 did not answer in 0.3 seconds. Set FACTORY_GITHUB_TOKEN.");
    }

    @Test
    void createsAClientThatFollowsNoRedirect() {
        HttpClient client = Main.createHttpClient();

        assertThat(client.followRedirects()).isEqualTo(HttpClient.Redirect.NEVER);
        assertThat(client.connectTimeout()).contains(Duration.ofSeconds(30));
    }

    private int hashPassword(List<String> typedPasswords) {
        List<String> remaining = new java.util.ArrayList<>(typedPasswords);
        Main.PasswordSource passwords = prompt -> remaining.isEmpty()
                ? Optional.empty() : Optional.of(remaining.remove(0).toCharArray());
        return Main.hashPassword(passwords, output, errors);
    }

    private String outputText() {
        return outputBytes.toString(StandardCharsets.UTF_8);
    }

    @Test
    void hashPasswordWritesTheHashLine() {
        int exitCode = hashPassword(List.of("correct horse battery", "correct horse battery"));

        assertThat(exitCode).isEqualTo(0);
        String line = outputText().strip();
        assertThat(line).startsWith("password-hash: pbkdf2-sha256:600000:");
        assertThat(com.meterian.factory.web.login.PasswordHash.parse(line.substring("password-hash: ".length()))
                .matches("correct horse battery".toCharArray())).isTrue();
        assertThat(errorText()).isEmpty();
    }

    @Test
    void hashPasswordRefusesTwoDifferentPasswords() {
        int exitCode = hashPassword(List.of("correct horse battery", "another horse battery"));

        assertThat(exitCode).isEqualTo(1);
        assertThat(errorText()).contains("The two passwords are not the same.")
                .doesNotContain("horse");
        assertThat(outputText()).doesNotContain("password-hash");
    }

    @Test
    void hashPasswordRefusesAShortPassword() {
        int exitCode = hashPassword(List.of("short", "short"));

        assertThat(exitCode).isEqualTo(1);
        assertThat(errorText()).contains("The password must have 12 characters or more.");
        assertThat(outputText()).doesNotContain("password-hash");
    }

    @Test
    void hashPasswordNeedsATerminal() {
        int exitCode = hashPassword(List.of());

        assertThat(exitCode).isEqualTo(1);
        assertThat(errorText()).contains("Run hash-password in a terminal.");
    }

    @Test
    void theHashPasswordCommandIsSelectedByItsName() {
        assertThat(Main.isHashPasswordCommand(new String[] {"hash-password"})).isTrue();
        assertThat(Main.isHashPasswordCommand(new String[] {"hash-password", "x"})).isFalse();
        assertThat(Main.isHashPasswordCommand(new String[] {"run", "--config", "f"})).isFalse();
    }
}
