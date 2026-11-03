package com.meterian.factory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.meterian.factory.configuration.ClaudeSettings;
import com.meterian.factory.configuration.FactoryConfiguration;
import com.meterian.factory.configuration.IsolationMode;
import com.meterian.factory.configuration.RepositoryName;
import com.meterian.factory.configuration.WorkerLimits;
import com.meterian.factory.configuration.WebSettings;
import com.meterian.factory.definition.BuiltInDefinition;
import com.meterian.factory.definition.DefinitionLookup;
import com.meterian.factory.git.Git;
import com.meterian.factory.github.Issue;
import com.meterian.factory.github.IssueComment;
import com.meterian.factory.state.IssueRecord;
import com.meterian.factory.state.IssueStatus;
import com.meterian.factory.state.WaitReason;
import com.meterian.factory.testing.FakeGitHubClient;
import com.meterian.factory.testing.FakeLiveSessions;
import com.meterian.factory.testing.RecordStates;
import com.meterian.factory.web.CardFingerprint;
import com.meterian.factory.web.WebServer;
import com.meterian.factory.web.login.PasswordHash;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.lang.reflect.Proxy;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MainTest {

    private static final DefinitionLookup BUILT_IN = DefinitionLookup.builtInOnly(BuiltInDefinition.load());

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
                }, createRecordingGit(new ArrayList<>()));
    }

    /** A git that records each clone and does nothing else, so that a test never reaches the network. */
    private static Git createRecordingGit(List<String> clones) {
        return (Git) Proxy.newProxyInstance(Git.class.getClassLoader(), new Class<?>[] {Git.class},
                (proxy, method, arguments) -> {
                    if (method.getName().equals("cloneRepository")) {
                        clones.add(arguments[0] + " -> " + arguments[1]);
                        Files.createDirectories((Path) arguments[1]);
                        return null;
                    }
                    throw new AssertionError("The test used git." + method.getName());
                });
    }

    private Map<String, String> createEnvironmentWithAllCommands() throws IOException {
        Path binDirectory = Files.createDirectories(temporaryDirectory.resolve("bin"));
        for (String command : List.of("claude", "git", "setsid", "kill", "tmux", "jq")) {
            Path file = Files.writeString(binDirectory.resolve(command), "#!/bin/sh\n");
            assertThat(file.toFile().setExecutable(true)).isTrue();
        }
        return Map.of("PATH", binDirectory.toString(), "FACTORY_GITHUB_TOKEN", "test-token");
    }

    @Test
    void clonesTheRepositoryWhenTheCloneIsMissing() throws IOException {
        Path configuration = writeConfiguration("claude");
        Path clone = temporaryDirectory.resolve("work").resolve("repository");
        List<String> clones = new ArrayList<>();
        List<Path> startedWithClone = new ArrayList<>();

        int exitCode = Main.run(new String[] {"run", "--config", configuration.toString()},
                createEnvironmentWithAllCommands(), errors,
                () -> {
                    throw new AssertionError("The test reached gh");
                },
                (loaded, token, errorStream) -> {
                    startedWithClone.add(loaded.cloneDirectory());
                    assertThat(clone).isDirectory();
                    return 0;
                }, createRecordingGit(clones));

        assertThat(exitCode).isZero();
        assertThat(clones).containsExactly("git@github.com:MeterianHQ/sample.git -> " + clone);
        assertThat(startedWithClone).containsExactly(clone);
    }

    @Test
    void keepsTheCloneThatExists() throws IOException {
        Path configuration = writeConfiguration("claude");
        Files.createDirectories(temporaryDirectory.resolve("work").resolve("repository"));
        List<String> clones = new ArrayList<>();

        int exitCode = Main.run(new String[] {"run", "--config", configuration.toString()},
                createEnvironmentWithAllCommands(), errors,
                () -> {
                    throw new AssertionError("The test reached gh");
                },
                (loaded, token, errorStream) -> 0, createRecordingGit(clones));

        assertThat(exitCode).isZero();
        assertThat(clones).isEmpty();
    }

    private String errorText() {
        return errorBytes.toString(StandardCharsets.UTF_8);
    }

    private Path writeConfiguration(String claudeCommand) throws IOException {
        Path file = temporaryDirectory.resolve("factory.yaml");
        Files.writeString(file, """
                repository: MeterianHQ/sample
                claude:
                  command: %s
                work-directory: %s
                gatekeeper-directory: %s
                """.formatted(claudeCommand, temporaryDirectory.resolve("work"),
                temporaryDirectory.resolve("gatekeeper.git")));
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

    private static final String PASSWORD = "correct horse battery";
    private static final int FAST_ITERATIONS = 1000;
    private static final String LAN_WARNING = "The web page is open to the LAN with plain HTTP. "
            + "Another device on the LAN can read the password and the cookie.";

    private static WebSettings webSettings(String address, int port) {
        return new WebSettings(address, port, PasswordHash.create(PASSWORD.toCharArray(), FAST_ITERATIONS));
    }

    private FactoryConfiguration configuration(Optional<WebSettings> web) {
        Path workDirectory = temporaryDirectory.resolve("work");
        return new FactoryConfiguration(new RepositoryName("MeterianHQ", "target"), workDirectory,
                temporaryDirectory.resolve("gatekeeper.git"), temporaryDirectory.resolve("live"),
                new WorkerLimits(2, Duration.ofSeconds(60), 0.90), Map.of(), IsolationMode.SAME_USER,
                ClaudeSettings.createDefaults(),
                web, FactoryConfiguration.DEFAULT_TERMINAL_COMMAND);
    }

    @Test
    void describesTheWebAddress() {
        assertThat(Main.describeWebAddress(webSettings("127.0.0.1", 8090))).isEqualTo("http://127.0.0.1:8090/");
        assertThat(Main.describeWebAddress(webSettings("::1", 8090))).isEqualTo("http://[::1]:8090/");
    }

    @Test
    void warnsWhenThePageIsOpenToTheLan() {
        assertThat(Main.findWebWarning(webSettings("0.0.0.0", 8090))).contains(LAN_WARNING);
        assertThat(Main.findWebWarning(webSettings("192.168.1.20", 8090))).contains(LAN_WARNING);
        assertThat(Main.findWebWarning(webSettings("127.0.0.1", 8090))).isEmpty();
        assertThat(Main.findWebWarning(webSettings("::1", 8090))).isEmpty();
    }

    @Test
    void findsTheClaudeHome() {
        assertThat(Main.findClaudeHome(Map.of("CLAUDE_CONFIG_DIR", "/opt/claude-home"), Path.of("/home/u")))
                .isEqualTo(Path.of("/opt/claude-home"));
        assertThat(Main.findClaudeHome(Map.of("CLAUDE_CONFIG_DIR", " "), Path.of("/home/u")))
                .isEqualTo(Path.of("/home/u/.claude"));
        assertThat(Main.findClaudeHome(Map.of(), Path.of("/home/u"))).isEqualTo(Path.of("/home/u/.claude"));
    }

    @Test
    void thePhoneLinkSenderPostsTheLinkWithNoMention() {
        FactoryConfiguration configuration = configuration(Optional.empty());
        FakeGitHubClient gitHub = new FakeGitHubClient();
        gitHub.addIssue(12, "Fix the login", "body");

        Main.createPhoneLinkSender(gitHub).sendLink(12, "https://claude.ai/code/session_abc123");

        assertThat(gitHub.commentsOf(12)).singleElement().extracting(IssueComment::body)
                .isEqualTo("The live conversation is open: https://claude.ai/code/session_abc123"
                        + "\n\nType /factory:continue in the conversation when you are done.");
    }

    @Test
    void makesNoWebServerWithoutAWebSection() {
        FactoryConfiguration configuration = configuration(Optional.empty());

        Optional<WebServer> server = Main.createWebServer(configuration, Main.SharedState.create(configuration),
                new FakeLiveSessions(), Main.createLabelWriter(new FakeGitHubClient()),
                Main.createPhoneLinkSender(new FakeGitHubClient()), BUILT_IN,
                Optional::empty, () -> 0, () -> { },
                temporaryDirectory.resolve("claude-home"));

        assertThat(server).isEmpty();
    }

    @Test
    void aPortInUseStopsTheStart() throws IOException {
        try (ServerSocket occupied = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            int port = occupied.getLocalPort();
            FactoryConfiguration configuration = configuration(Optional.of(webSettings("127.0.0.1", port)));
            WebServer server = Main.createWebServer(configuration, Main.SharedState.create(configuration),
                    new FakeLiveSessions(), Main.createLabelWriter(new FakeGitHubClient()),
                    Main.createPhoneLinkSender(new FakeGitHubClient()), BUILT_IN,
                    Optional::empty, () -> 0, () -> { }, temporaryDirectory.resolve("claude-home")).orElseThrow();

            assertThatThrownBy(() -> Main.startWebServer(server, configuration.web().orElseThrow(), errors))
                    .isInstanceOf(Main.StartupException.class)
                    .hasMessageStartingWith("The web page cannot listen on 127.0.0.1:" + port + ": ");
        }
    }

    @Test
    void thePageSharesTheBoardAndThePageRequestsWithTheWorker() throws Exception {
        FactoryConfiguration configuration = configuration(Optional.of(webSettings("127.0.0.1", 0)));
        Main.SharedState shared = Main.SharedState.create(configuration);
        FakeGitHubClient gitHub = new FakeGitHubClient();
        gitHub.addIssue(12, "Fix the login", "body");
        shared.board().recordIssue(new Issue(12, "Fix the login", "body", Set.of("factory")),
                RecordStates.move(IssueRecord.queued(12).withAgentSessionStarted().withStatus(IssueStatus.WAITS_FOR_YOU),
                        "merge").withWaitReason(WaitReason.MERGE_NEEDS_APPROVAL));
        WebServer server = Main.createWebServer(configuration, shared, new FakeLiveSessions(),
                Main.createLabelWriter(gitHub), Main.createPhoneLinkSender(gitHub),
                BUILT_IN, Optional::empty, () -> 1, () -> { },
                temporaryDirectory.resolve("claude-home")).orElseThrow();
        Main.startWebServer(server, configuration.web().orElseThrow(), errors);
        try {
            HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
            URI base = URI.create("http://127.0.0.1:" + server.port());
            String cookie = logIn(client, base);
            String fingerprint = CardFingerprint.of(shared.board().snapshot().cards().getFirst());

            HttpResponse<String> approve = client.send(HttpRequest.newBuilder(base.resolve("/issues/12/approve"))
                    .POST(HttpRequest.BodyPublishers.ofString("state=" + fingerprint))
                    .header("Content-Type", "application/x-www-form-urlencoded").header("Cookie", cookie).build(),
                    HttpResponse.BodyHandlers.ofString());

            assertThat(approve.statusCode()).isEqualTo(303);
            assertThat(gitHub.labelsOf(12)).contains("factory:approve");
            assertThat(shared.board().snapshot().cards().getFirst().labels()).contains("factory:approve");
            assertThat(errorText()).contains("The web page is at http://127.0.0.1:" + server.port() + "/")
                    .doesNotContain("open to the LAN");
        } finally {
            server.stop();
        }
    }

    private static String logIn(HttpClient client, URI base) throws Exception {
        HttpResponse<String> login = client.send(HttpRequest.newBuilder(base.resolve("/login"))
                .POST(HttpRequest.BodyPublishers.ofString("password=" + PASSWORD.replace(' ', '+')))
                .header("Content-Type", "application/x-www-form-urlencoded").build(),
                HttpResponse.BodyHandlers.ofString());
        String setCookie = login.headers().firstValue("Set-Cookie").orElseThrow();
        return setCookie.substring(0, setCookie.indexOf(';'));
    }
}
