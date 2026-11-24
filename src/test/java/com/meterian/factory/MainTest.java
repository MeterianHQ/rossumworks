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
import com.meterian.factory.provider.ApiKeyProvider;
import com.meterian.factory.provider.Provider;
import com.meterian.factory.setup.CreateOptions;
import com.meterian.factory.setup.WorkerFileFinder;
import com.meterian.factory.state.IssueRecord;
import com.meterian.factory.testing.FakeGitHubClient;
import com.meterian.factory.testing.FakeLiveSessions;
import com.meterian.factory.testing.RecordStates;
import com.meterian.factory.web.CardFingerprint;
import com.meterian.factory.web.NoPasswordLogin;
import com.meterian.factory.web.WebServer;
import com.meterian.factory.web.login.PasswordHash;
import com.meterian.factory.web.login.PasswordLogin;
import com.meterian.factory.workflow.RetrySettings;
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
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MainTest {

    private static final DefinitionLookup BUILT_IN = DefinitionLookup.builtInOnly(BuiltInDefinition.load());

    private static final String USAGE = "Usage: java -jar rossumworks.jar run [--config <file>]\n"
            + "       java -jar rossumworks.jar hash-password\n"
            + "       java -jar rossumworks.jar create [--repository owner/name] [--work-directory <path>] "
            + "[--web <address:port> | --no-web] [--terminal-command \"<command>\"] [--build-command \"<command>\"] "
            + "[--yes]";

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
                (configuration, provider, retries, token, errorStream) -> {
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
                (loaded, provider, retries, token, errorStream) -> {
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
                (loaded, provider, retries, token, errorStream) -> 0, createRecordingGit(clones));

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
    void stopsTheWorkerOnAnInvalidProperty() throws IOException {
        Path configuration = writeConfiguration("claude");
        System.setProperty("factory.retries.review-session", "0");
        try {
            int exitCode = Main.run(new String[] {"run", "--config", configuration.toString()},
                    createEnvironmentWithAllCommands(), errors,
                    () -> {
                        throw new AssertionError("The test reached gh");
                    },
                    (loaded, provider, retries, token, errorStream) -> {
                        throw new AssertionError("The test reached the worker");
                    }, createRecordingGit(new ArrayList<>()));

            assertThat(exitCode).isEqualTo(1);
            assertThat(errorText()).contains("The system property factory.retries.review-session must be a whole"
                    + " number of 1 or more, but it is 0.");
        } finally {
            System.clearProperty("factory.retries.review-session");
        }
    }

    @Test
    void givesTheWorkerTheRetrySettingsOfTheSystemProperties() throws IOException {
        Path configuration = writeConfiguration("claude");
        List<RetrySettings> startedWith = new ArrayList<>();
        System.setProperty("factory.retries.forward-check", "5");
        try {
            int exitCode = Main.run(new String[] {"run", "--config", configuration.toString()},
                    createEnvironmentWithAllCommands(), errors,
                    () -> {
                        throw new AssertionError("The test reached gh");
                    },
                    (loaded, provider, retries, token, errorStream) -> {
                        startedWith.add(retries);
                        return 0;
                    }, createRecordingGit(new ArrayList<>()));

            assertThat(exitCode).isZero();
            assertThat(startedWith).containsExactly(new RetrySettings(2, 5));
        } finally {
            System.clearProperty("factory.retries.forward-check");
        }
    }

    /** A defect in the worker must keep its stack trace, so Main does not convert it to one line. */
    @Test
    void doesNotConvertAnIllegalArgumentExceptionOfTheWorker() throws IOException {
        Path configuration = writeConfiguration("claude");
        Map<String, String> environment = createEnvironmentWithAllCommands();

        assertThatThrownBy(() -> Main.run(new String[] {"run", "--config", configuration.toString()},
                environment, errors,
                () -> {
                    throw new AssertionError("The test reached gh");
                },
                (loaded, provider, retries, token, errorStream) -> {
                    throw new IllegalArgumentException("A defect of the worker");
                }, createRecordingGit(new ArrayList<>())))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("A defect of the worker");
        assertThat(errorText()).doesNotContain("A defect of the worker");
    }

    @Test
    void readsTheConfigurationPath() throws Main.StartupException {
        Path path = Main.readConfigurationPath(new String[] {"run", "--config", "factory.yaml"}, refusingFinder());

        assertThat(path).isEqualTo(Path.of("factory.yaml"));
    }

    @Test
    void findsTheConfigurationOfTheCloneWithoutTheOptionConfig() throws Exception {
        Path workerFile = Files.createDirectories(temporaryDirectory.resolve(".rossumworks/owner-project"))
                .resolve("worker.yaml");
        Files.writeString(workerFile, "repository: owner/project\n");
        WorkerFileFinder finder = new WorkerFileFinder(temporaryDirectory,
                () -> Optional.of(new RepositoryName("owner", "project")));

        Path path = Main.readConfigurationPath(new String[] {"run"}, finder);

        assertThat(path).isEqualTo(workerFile);
    }

    @Test
    void refusesOtherArguments() {
        for (String[] arguments : List.of(new String[] {}, new String[] {"start", "--config", "factory.yaml"},
                new String[] {"run", "--config"}, new String[] {"run", "--file", "factory.yaml"},
                new String[] {"run", "factory.yaml"})) {
            assertThatThrownBy(() -> Main.readConfigurationPath(arguments, refusingFinder())).hasMessage(USAGE);
        }
    }

    private static WorkerFileFinder refusingFinder() {
        return new WorkerFileFinder(Path.of("no-home"), () -> {
            throw new AssertionError("The command gives the file, so the finder must not run.");
        });
    }

    private Path writeApiKeyConfiguration() throws IOException {
        Files.createDirectory(temporaryDirectory.resolve("work"),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        Path file = temporaryDirectory.resolve("api-key.yaml");
        Files.writeString(file, """
                repository: MeterianHQ/sample
                claude:
                  command: claude
                  provider: anthropic-api-key
                limits:
                  daily-budget-usd: 20
                work-directory: %s
                gatekeeper-directory: %s
                """.formatted(temporaryDirectory.resolve("work"), temporaryDirectory.resolve("gatekeeper.git")));
        return file;
    }

    private Map<String, String> withVariable(Map<String, String> environment, String name, String value) {
        Map<String, String> changed = new HashMap<>(environment);
        changed.put(name, value);
        return changed;
    }

    @Test
    void refusesTheSubscriptionWithAnApiKey() throws IOException {
        Path configuration = writeConfiguration("claude");

        int exitCode = runMain(new String[] {"run", "--config", configuration.toString()},
                withVariable(createEnvironmentWithAllCommands(), "ANTHROPIC_API_KEY", "secret"));

        assertThat(exitCode).isEqualTo(1);
        assertThat(errorText()).contains("Remove ANTHROPIC_API_KEY: the provider is subscription. "
                + "To use the key, set claude.provider to anthropic-api-key.").doesNotContain("secret");
    }

    @Test
    void refusesTheApiKeyProviderWithoutAKey() throws IOException {
        int exitCode = runMain(new String[] {"run", "--config", writeApiKeyConfiguration().toString()},
                createEnvironmentWithAllCommands());

        assertThat(exitCode).isEqualTo(1);
        assertThat(errorText()).contains("Set ANTHROPIC_API_KEY: the provider is anthropic-api-key. "
                + "To use the claude login, remove claude.provider.");
    }

    @Test
    void printsTheApiKeyProviderAtStart() throws IOException {
        List<Provider> started = new ArrayList<>();

        int exitCode = Main.run(new String[] {"run", "--config", writeApiKeyConfiguration().toString()},
                withVariable(createEnvironmentWithAllCommands(), "ANTHROPIC_API_KEY", "secret"), errors,
                () -> {
                    throw new AssertionError("The test reached gh");
                },
                (loaded, provider, retries, token, errorStream) -> {
                    started.add(provider);
                    return 0;
                }, createRecordingGit(new ArrayList<>()));

        assertThat(exitCode).isZero();
        assertThat(started).singleElement().isInstanceOf(ApiKeyProvider.class);
        assertThat(errorText()).contains("The provider is anthropic-api-key: the sessions use an Anthropic API "
                + "key. The daily budget is $20.00.").doesNotContain("secret");
        assertThat(temporaryDirectory.resolve("work/credentials/api-key-settings.json")).isRegularFile();
    }

    @Test
    void printsTheSubscriptionProviderAtStart() throws IOException {
        int exitCode = Main.run(new String[] {"run", "--config", writeConfiguration("claude").toString()},
                createEnvironmentWithAllCommands(), errors,
                () -> {
                    throw new AssertionError("The test reached gh");
                },
                (loaded, provider, retries, token, errorStream) -> 0, createRecordingGit(new ArrayList<>()));

        assertThat(exitCode).isZero();
        assertThat(errorText())
                .contains("The provider is subscription: the sessions use the claude login of the owner.");
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

        List<String> missing = StartChecks.findMissingCommands(
                List.of("tool", "not-executable", "absent", tool.toString(), "/no/such/tool"), binDirectory.toString());

        assertThat(missing).containsExactly("not-executable", "absent", "/no/such/tool");
    }

    @Test
    void readsTheTokenFromTheEnvironmentFirst() throws Main.StartupException {
        String token = StartChecks.readToken(Map.of("FACTORY_GITHUB_TOKEN", "environment-token"),
                () -> Optional.of("gh-token"));

        assertThat(token).isEqualTo("environment-token");
    }

    @Test
    void readsTheTokenFromGhWhenTheEnvironmentHasNone() throws Main.StartupException {
        assertThat(StartChecks.readToken(Map.of(), () -> Optional.of("gh-token"))).isEqualTo("gh-token");
        assertThat(StartChecks.readToken(Map.of("FACTORY_GITHUB_TOKEN", " "), () -> Optional.of("gh-token")))
                .isEqualTo("gh-token");
    }

    @Test
    void refusesToStartWithoutAToken() {
        assertThatThrownBy(() -> StartChecks.readToken(Map.of(), Optional::empty))
                .isInstanceOf(Main.StartupException.class)
                .hasMessage("Set FACTORY_GITHUB_TOKEN, or log in with gh auth login.");
    }

    @Test
    void readsTheTokenFromTheOutputOfACommand() throws Main.StartupException {
        Optional<String> token = StartChecks.readTokenFromCommand(List.of("sh", "-c", "echo command-token"),
                Duration.ofSeconds(30));

        assertThat(token).contains("command-token");
    }

    @Test
    void givesNoTokenWhenTheCommandFails() throws Main.StartupException {
        assertThat(StartChecks.readTokenFromCommand(List.of("sh", "-c", "echo partial; exit 1"), Duration.ofSeconds(30)))
                .isEmpty();
        assertThat(StartChecks.readTokenFromCommand(List.of("no-such-gh-for-the-factory"), Duration.ofSeconds(30)))
                .isEmpty();
    }

    @Test
    void stopsATokenCommandThatDoesNotAnswer() {
        assertThatThrownBy(() -> StartChecks.readTokenFromCommand(List.of("sleep", "20"), Duration.ofMillis(300)))
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

    @Test
    void createIsACommand() {
        assertThat(Main.isCreateCommand(new String[] {"create", "--unknown"})).isTrue();
        assertThat(Main.isCreateCommand(new String[] {"run", "--config", "f"})).isFalse();

        int exitCode = Main.create(List.of("--unknown"), output, errors);

        assertThat(exitCode).isEqualTo(1);
        assertThat(errorText()).contains("Unknown parameter --unknown.", CreateOptions.USAGE);
    }

    private static final String PASSWORD = "correct horse battery";
    private static final int FAST_ITERATIONS = 1000;
    private static final String LAN_WARNING = "The web page is open to the LAN with plain HTTP. "
            + "Another device on the LAN can read the password and the cookie.";

    private static WebSettings webSettings(String address, int port) {
        return new WebSettings(address, port,
                Optional.of(PasswordHash.create(PASSWORD.toCharArray(), FAST_ITERATIONS)));
    }

    private static WebSettings webSettingsWithoutPassword(String address) {
        return new WebSettings(address, 8090, Optional.empty());
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
    void tellsWhenThePageHasNoPassword() {
        assertThat(Main.findWebWarning(webSettingsWithoutPassword("127.0.0.1")))
                .contains("The web page has no password.");
        assertThat(Main.findWebWarning(webSettingsWithoutPassword("0.0.0.0"))).contains(
                "The web page is open to the LAN without a password. Every device on the LAN can use the page.");
    }

    @Test
    void aPageWithoutAPasswordHasNoLogin() {
        assertThat(Main.createLogin(webSettingsWithoutPassword("127.0.0.1"))).isInstanceOf(NoPasswordLogin.class);
        assertThat(Main.createLogin(webSettings("127.0.0.1", 8090))).isInstanceOf(PasswordLogin.class);
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
    void makesNoWebServerWithoutAWebSection() {
        FactoryConfiguration configuration = configuration(Optional.empty());

        Optional<WebServer> server = Main.createWebServer(configuration, Main.SharedState.create(configuration),
                new FakeLiveSessions(), true, Main.createLabelWriter(new FakeGitHubClient()), BUILT_IN,
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
                    new FakeLiveSessions(), true, Main.createLabelWriter(new FakeGitHubClient()), BUILT_IN,
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
                RecordStates.move(RecordStates.queued(12).withAgentSessionStarted(), "waits-for-approval"));
        WebServer server = Main.createWebServer(configuration, shared, new FakeLiveSessions(), true,
                Main.createLabelWriter(gitHub), BUILT_IN, Optional::empty, () -> 1, () -> { },
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
            assertThat(shared.pageRequests().isRemembered(12, "factory:approve")).isTrue();
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
