package com.meterian.factory;

import com.meterian.factory.agent.ClaudeAgentRunner;
import com.meterian.factory.agent.ContinueMarkerWatcher;
import com.meterian.factory.agent.LiveContextReader;
import com.meterian.factory.agent.LiveFiles;
import com.meterian.factory.agent.LiveSessions;
import com.meterian.factory.agent.TmuxLiveSessions;
import com.meterian.factory.board.BoardState;
import com.meterian.factory.build.BuildRunner;
import com.meterian.factory.configuration.ConfigurationException;
import com.meterian.factory.configuration.ConfigurationLoader;
import com.meterian.factory.configuration.FactoryConfiguration;
import com.meterian.factory.configuration.WebSettings;
import com.meterian.factory.git.Gatekeeper;
import com.meterian.factory.git.Git;
import com.meterian.factory.git.GitException;
import com.meterian.factory.git.ProcessGit;
import com.meterian.factory.github.GitHubClient;
import com.meterian.factory.github.GitHubException;
import com.meterian.factory.github.HttpGitHubClient;
import com.meterian.factory.merge.Merger;
import com.meterian.factory.prompt.PromptLibrary;
import com.meterian.factory.usage.ContextTracker;
import com.meterian.factory.usage.UsageProbe;
import com.meterian.factory.usage.UsageTracker;
import com.meterian.factory.web.BoardPage;
import com.meterian.factory.web.LabelWriter;
import com.meterian.factory.web.PageActions;
import com.meterian.factory.web.PageModelSource;
import com.meterian.factory.web.PhoneLinkSender;
import com.meterian.factory.web.WebServer;
import com.meterian.factory.web.login.PasswordHash;
import com.meterian.factory.web.login.PasswordLogin;
import com.meterian.factory.worker.PhaseStopper;
import com.meterian.factory.worker.Worker;
import com.meterian.factory.workflow.IssueWorkflow;
import com.meterian.factory.workflow.LiveComment;
import com.meterian.factory.workflow.PageRequests;
import com.meterian.factory.workflow.RequestReader;
import com.meterian.factory.workflow.WorkspaceLayout;
import java.io.Console;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.IntSupplier;

/** The command line: run --config <file>, or hash-password. */
public final class Main {

    static final int EXIT_SUCCESS = 0;
    static final int EXIT_FAILURE = 1;
    static final int EXIT_API_KEY = 2;

    private static final String USAGE = "Usage: java -jar claude-ai-factory.jar run --config <file>\n"
            + "       java -jar claude-ai-factory.jar hash-password";
    private static final int MINIMUM_PASSWORD_LENGTH = 12;
    private static final String API_KEY_VARIABLE = "ANTHROPIC_API_KEY";
    private static final String TOKEN_VARIABLE = "FACTORY_GITHUB_TOKEN";
    private static final URI GITHUB_API = URI.create("https://api.github.com");
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration MERGE_RETRY_DELAY = Duration.ofSeconds(10);
    private static final Duration LOOP_END_TIMEOUT = Duration.ofMinutes(1);
    private static final Duration SHUTDOWN_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration TOKEN_COMMAND_TIMEOUT = Duration.ofSeconds(30);
    private static final String CLAUDE_HOME_VARIABLE = "CLAUDE_CONFIG_DIR";
    private static final String LAN_WARNING = "The web page is open to the LAN with plain HTTP. "
            + "Another device on the LAN can read the password and the cookie.";
    private static final System.Logger LOGGER = System.getLogger(Main.class.getName());

    /** A check before the start failed. The message tells the owner what to do. */
    static final class StartupException extends Exception {

        StartupException(String message) {
            super(message);
        }
    }

    /** The objects in memory that the worker, the workflow, and the page share. Main makes each one once. */
    record SharedState(BoardState board, ContextTracker contexts, PageRequests pageRequests, UsageTracker usage) {

        static SharedState create(FactoryConfiguration configuration) {
            return new SharedState(new BoardState(Clock.systemUTC()), new ContextTracker(),
                    new PageRequests(Clock.systemUTC()),
                    new UsageTracker(configuration.limits().usageThreshold(), Clock.systemUTC()));
        }
    }

    private Main() {
    }

    public static void main(String[] arguments) {
        if (isHashPasswordCommand(arguments)) {
            System.exit(hashPassword(Main::readPasswordFromConsole, System.out, System.err));
        }
        System.exit(run(arguments, System.getenv(), System.err));
    }

    static boolean isHashPasswordCommand(String[] arguments) {
        return arguments.length == 1 && arguments[0].equals("hash-password");
    }

    /** Reads a password without echo. A test gives another source, so that it never uses the console. */
    @FunctionalInterface
    interface PasswordSource {
        Optional<char[]> read(String prompt);
    }

    private static Optional<char[]> readPasswordFromConsole(String prompt) {
        Console console = System.console();
        return console == null ? Optional.empty() : Optional.ofNullable(console.readPassword("%s", prompt));
    }

    /** Asks for the password two times and writes the hash line. It never prints the password. */
    static int hashPassword(PasswordSource passwords, PrintStream output, PrintStream errors) {
        char[] password = passwords.read("Password: ").orElse(null);
        if (password == null) {
            errors.println("Run hash-password in a terminal.");
            return EXIT_FAILURE;
        }
        char[] repeated = passwords.read("Repeat the password: ").orElse(new char[0]);
        try {
            if (!Arrays.equals(password, repeated)) {
                errors.println("The two passwords are not the same.");
                return EXIT_FAILURE;
            }
            if (password.length < MINIMUM_PASSWORD_LENGTH) {
                errors.println("The password must have " + MINIMUM_PASSWORD_LENGTH + " characters or more.");
                return EXIT_FAILURE;
            }
            output.println("password-hash: " + PasswordHash.create(password).format());
            return EXIT_SUCCESS;
        } finally {
            Arrays.fill(password, '\0');
            Arrays.fill(repeated, '\0');
        }
    }

    /** Starts the worker after the checks. A test gives another starter, so that it never reaches the network. */
    @FunctionalInterface
    interface WorkerStarter {
        int start(FactoryConfiguration configuration, String token, PrintStream errors)
                throws InterruptedException, StartupException;
    }

    static int run(String[] arguments, Map<String, String> environment, PrintStream errors) {
        return run(arguments, environment, errors, Main::readTokenFromGh, Main::runWorker);
    }

    /** Runs the checks in order, so that a wrong argument or environment stops the factory before any network. */
    static int run(String[] arguments, Map<String, String> environment, PrintStream errors, TokenSource ghToken,
                   WorkerStarter workerStarter) {
        try {
            Path configurationFile = readConfigurationPath(arguments);
            verifyNoApiKey(environment);
            FactoryConfiguration configuration = ConfigurationLoader.load(configurationFile);
            verifyCommands(configuration, environment.getOrDefault("PATH", ""));
            Gatekeeper.ensure(configuration.gatekeeperDirectory().toAbsolutePath(), configuration.target().sshUrl());
            String token = readToken(environment, ghToken);
            return workerStarter.start(configuration, token, errors);
        } catch (StartupException | ConfigurationException | GitException | GitHubException exception) {
            errors.println(exception.getMessage());
            return EXIT_FAILURE;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            errors.println("The factory was interrupted.");
            return EXIT_FAILURE;
        }
    }

    static Path readConfigurationPath(String[] arguments) throws StartupException {
        boolean isValid = arguments.length == 3 && arguments[0].equals("run") && arguments[1].equals("--config");
        if (!isValid) {
            throw new StartupException(USAGE);
        }
        return Path.of(arguments[2]);
    }

    static void verifyNoApiKey(Map<String, String> environment) throws StartupException {
        if (environment.containsKey(API_KEY_VARIABLE)) {
            throw new StartupException("Remove ANTHROPIC_API_KEY: the factory uses only the Claude subscription.");
        }
    }

    private static void verifyCommands(FactoryConfiguration configuration, String path) throws StartupException {
        List<String> commands = List.of(configuration.claude().command(), "git", "setsid", "kill", "tmux", "jq");
        List<String> missing = findMissingCommands(commands, path);
        if (!missing.isEmpty()) {
            throw new StartupException("The factory needs these commands on the PATH: " + String.join(", ", missing));
        }
    }

    /** A command with a slash is a file path. Another command must be an executable file in a PATH folder. */
    static List<String> findMissingCommands(List<String> commands, String path) {
        List<String> missing = new ArrayList<>();
        for (String command : commands) {
            if (!isExecutableCommand(command, path)) {
                missing.add(command);
            }
        }
        return missing;
    }

    private static boolean isExecutableCommand(String command, String path) {
        if (command.contains("/")) {
            return Files.isExecutable(Path.of(command));
        }
        return Arrays.stream(path.split(File.pathSeparator))
                .filter(folder -> !folder.isBlank())
                .map(folder -> Path.of(folder).resolve(command))
                .anyMatch(file -> Files.isRegularFile(file) && Files.isExecutable(file));
    }

    /** A source of the token that can stop the start, for example a command that does not answer. */
    @FunctionalInterface
    interface TokenSource {
        Optional<String> read() throws StartupException;
    }

    static String readToken(Map<String, String> environment, TokenSource ghToken) throws StartupException {
        String fromEnvironment = environment.getOrDefault(TOKEN_VARIABLE, "");
        if (!fromEnvironment.isBlank()) {
            return fromEnvironment.strip();
        }
        return ghToken.read()
                .filter(token -> !token.isBlank())
                .orElseThrow(() -> new StartupException("Set FACTORY_GITHUB_TOKEN, or log in with gh auth login."));
    }

    private static Optional<String> readTokenFromGh() throws StartupException {
        return readTokenFromCommand(List.of("gh", "auth", "token"), TOKEN_COMMAND_TIMEOUT);
    }

    /**
     * Runs the command and returns its output. A missing command or a failure gives no token. A command that
     * does not end in the timeout stops the start.
     */
    static Optional<String> readTokenFromCommand(List<String> command, Duration timeout) throws StartupException {
        Path outputFile = null;
        try {
            outputFile = Files.createTempFile("factory-token", ".out");
            Process process = new ProcessBuilder(command)
                    .redirectOutput(outputFile.toFile())
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                throw new StartupException("The command " + String.join(" ", command) + " did not answer in "
                        + describeSeconds(timeout) + " seconds. Set FACTORY_GITHUB_TOKEN.");
            }
            String output = Files.readString(outputFile, StandardCharsets.UTF_8).strip();
            return process.exitValue() == 0 ? Optional.of(output) : Optional.empty();
        } catch (IOException exception) {
            return Optional.empty();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } finally {
            deleteQuietly(outputFile);
        }
    }

    private static String describeSeconds(Duration duration) {
        long millis = duration.toMillis();
        return millis % 1000 == 0 ? Long.toString(millis / 1000) : Double.toString(millis / 1000.0);
    }

    /** The file can hold the token, so it must not stay on the disk. */
    private static void deleteQuietly(Path file) {
        if (file == null) {
            return;
        }
        try {
            Files.deleteIfExists(file);
        } catch (IOException exception) {
            LOGGER.log(System.Logger.Level.WARNING, "Cannot delete the temporary file {0}", file);
        }
    }

    static HttpClient createHttpClient() {
        return HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(CONNECT_TIMEOUT)
                .build();
    }

    private static int runWorker(FactoryConfiguration configuration, String token, PrintStream errors)
            throws InterruptedException, StartupException {
        LiveFiles liveFiles = installLiveFiles(configuration);
        GitHubClient gitHub = new HttpGitHubClient(GITHUB_API, token, configuration.target(), createHttpClient());
        String workerLogin = gitHub.authenticatedLogin();
        SharedState shared = SharedState.create(configuration);
        ClaudeAgentRunner agentRunner = new ClaudeAgentRunner(configuration.claude(), Clock.systemUTC());
        RequestReader requests = createRequestReader(gitHub, configuration, workerLogin, shared);
        BuildRunner buildRunner = new BuildRunner();
        TmuxLiveSessions liveSessions = new TmuxLiveSessions(configuration.claude(), liveFiles, System.getenv(),
                TmuxLiveSessions.DEFAULT_OPEN_TIMEOUT,
                configuration.terminalCommand());
        IssueWorkflow workflow = createWorkflow(configuration, gitHub, agentRunner, buildRunner, requests, workerLogin,
                liveSessions, shared);
        ExecutorService executor = Executors.newFixedThreadPool(configuration.limits().parallelIssues());
        PhaseStopper phaseStopper = new PhaseStopper(agentRunner, buildRunner,
                new WorkspaceLayout(configuration.workDirectory()), liveSessions);
        Worker worker = new Worker(configuration, gitHub, workflow, phaseStopper, requests, workerLogin, executor,
                liveSessions, Clock.systemUTC(), shared.board());
        Path claudeHome = findClaudeHome(System.getenv(), Path.of(System.getProperty("user.home")));
        Optional<WebServer> webServer = createWebServer(configuration, shared, liveSessions, createLabelWriter(gitHub),
                createPhoneLinkSender(gitHub, configuration), () -> worker.issuesInWork().size(),
                worker::requestEarlyPoll, claudeHome);
        startWebServerIfPresent(webServer, configuration, errors, worker);
        ContinueMarkerWatcher continueMarkerWatcher = new ContinueMarkerWatcher(configuration.liveDirectory(), () -> {
            shared.board().recordRequestChange();
            worker.requestEarlyPoll();
        });
        startContinueMarkerWatcher(continueMarkerWatcher, errors);
        new UsageProbe(configuration.claude().command(), System.getenv(), Clock.systemUTC(),
                UsageProbe.DEFAULT_TIMEOUT).startRecording(shared.usage());
        Runtime.getRuntime().addShutdownHook(createShutdownHook(worker, webServer));
        try {
            worker.runUntilStopped();
            worker.shutDown(LOOP_END_TIMEOUT);
        } finally {
            continueMarkerWatcher.close();
            webServer.ifPresent(WebServer::stop);
        }
        if (!worker.foundAnApiKey()) {
            errors.println("The factory stopped.");
            return EXIT_SUCCESS;
        }
        errors.println("The factory stopped: a claude session used an API key.");
        return EXIT_API_KEY;
    }

    /** The same page requests go to the reader and to the page, so that the reader accepts a label of the page. */
    static RequestReader createRequestReader(GitHubClient gitHub, FactoryConfiguration configuration,
                                             String workerLogin, SharedState shared) {
        return new RequestReader(gitHub, configuration.allowedAccounts(), workerLogin, shared.pageRequests());
    }

    /** The page gets only these two label requests of the GitHub client. A 404 on a remove is not an error. */
    static LabelWriter createLabelWriter(GitHubClient gitHub) {
        return new LabelWriter() {
            @Override
            public void addLabel(int issueNumber, String label) {
                gitHub.addLabel(issueNumber, label);
            }

            @Override
            public void removeLabel(int issueNumber, String label) {
                gitHub.removeLabel(issueNumber, label);
            }
        };
    }

    /** The page sends the live link again with the comment of the worker: the mention notifies the phone. */
    static PhoneLinkSender createPhoneLinkSender(GitHubClient gitHub, FactoryConfiguration configuration) {
        Optional<String> mentionedLogin = configuration.allowedAccounts().stream().findFirst();
        return (issueNumber, link) -> gitHub.createComment(issueNumber, LiveComment.render(link, mentionedLogin));
    }

    /**
     * Without a web section, the worker serves no page. The page gets only a label writer and a phone link
     * sender, never the GitHub client.
     */
    static Optional<WebServer> createWebServer(FactoryConfiguration configuration, SharedState shared,
                                               LiveSessions liveSessions, LabelWriter labels,
                                               PhoneLinkSender phoneLinkSender, IntSupplier issuesInWork,
                                               Runnable earlyPollRequest, Path claudeHome) {
        return configuration.web().map(settings -> new WebServer(settings,
                new PasswordLogin(settings.passwordHash(), Clock.systemDefaultZone(), new SecureRandom()),
                new PageModelSource(shared.board(), shared.usage(), shared.contexts(),
                        new LiveContextReader(claudeHome), liveSessions, shared.pageRequests(),
                        new WorkspaceLayout(configuration.workDirectory()), configuration, issuesInWork,
                        Clock.systemUTC()),
                new BoardPage(ZoneId.systemDefault()), shared.board(),
                new PageActions(labels, shared.pageRequests(), liveSessions, phoneLinkSender, shared.board(),
                        earlyPollRequest)));
    }

    /** A web server that cannot start stops the worker before its first poll. */
    private static void startWebServerIfPresent(Optional<WebServer> webServer, FactoryConfiguration configuration,
                                                PrintStream errors, Worker worker) throws StartupException {
        if (webServer.isEmpty()) {
            return;
        }
        try {
            startWebServer(webServer.get(), configuration.web().orElseThrow(), errors);
        } catch (StartupException exception) {
            worker.shutDown(SHUTDOWN_TIMEOUT);
            throw exception;
        }
    }

    static void startWebServer(WebServer webServer, WebSettings settings, PrintStream errors)
            throws StartupException {
        try {
            webServer.start();
        } catch (IOException exception) {
            throw new StartupException("The web page cannot listen on " + settings.address() + ":" + settings.port()
                    + ": " + exception.getMessage());
        }
        errors.println("The web page is at " + describeWebAddress(settings.address(), webServer.port()));
        findWebWarning(settings).ifPresent(errors::println);
    }

    static String describeWebAddress(WebSettings settings) {
        return describeWebAddress(settings.address(), settings.port());
    }

    private static String describeWebAddress(String address, int port) {
        String host = address.contains(":") ? "[" + address + "]" : address;
        return "http://" + host + ":" + port + "/";
    }

    /** Only a loopback address keeps the plain HTTP on this computer. An address that is not known also warns. */
    static Optional<String> findWebWarning(WebSettings settings) {
        return isLoopbackAddress(settings.address()) ? Optional.empty() : Optional.of(LAN_WARNING);
    }

    private static boolean isLoopbackAddress(String address) {
        try {
            return InetAddress.getByName(address).isLoopbackAddress();
        } catch (UnknownHostException exception) {
            return false;
        }
    }

    /** The claude home holds the session files of the live sessions. */
    static Path findClaudeHome(Map<String, String> environment, Path userHome) {
        String configured = environment.getOrDefault(CLAUDE_HOME_VARIABLE, "");
        return configured.isBlank() ? userHome.resolve(".claude") : Path.of(configured.strip());
    }

    /** The install runs before the first poll: a live folder that is not private stops the start. */
    /** Without the watch the worker still works: it sees /factory:continue at the next poll. */
    private static void startContinueMarkerWatcher(ContinueMarkerWatcher watcher, PrintStream errors) {
        try {
            watcher.start();
        } catch (IOException exception) {
            errors.println("The worker sees /factory:continue only at the next poll: " + exception.getMessage());
        }
    }

    private static LiveFiles installLiveFiles(FactoryConfiguration configuration) throws StartupException {
        LiveFiles liveFiles = new LiveFiles(configuration.liveDirectory());
        try {
            liveFiles.install();
        } catch (IllegalStateException | UncheckedIOException | UnsupportedOperationException exception) {
            throw new StartupException(describeLiveFilesFailure(exception));
        }
        return liveFiles;
    }

    private static String describeLiveFilesFailure(RuntimeException exception) {
        if (exception instanceof UnsupportedOperationException) {
            return "The factory needs a file system with POSIX permissions for the live folder.";
        }
        Throwable cause = exception.getCause();
        return cause == null ? exception.getMessage() : exception.getMessage() + ": " + cause.getMessage();
    }

    /** On Ctrl-C or a kill, the hook ends the claude process groups. They run under setsid and get no signal. */
    static Thread createShutdownHook(Worker worker, Optional<WebServer> webServer) {
        return new Thread(() -> {
            webServer.ifPresent(WebServer::stop);
            worker.shutDown(SHUTDOWN_TIMEOUT);
        }, "factory-shutdown");
    }

    private static IssueWorkflow createWorkflow(FactoryConfiguration configuration, GitHubClient gitHub,
                                                ClaudeAgentRunner agentRunner, BuildRunner buildRunner,
                                                RequestReader requests, String workerLogin,
                                                LiveSessions liveSessions, SharedState shared) {
        Git git = new ProcessGit();
        String mergeTargetBranch = configuration.mergeTargetBranch();
        Merger merger = new Merger(git, gitHub, buildRunner, configuration.target(), mergeTargetBranch,
                configuration.gatekeeperDirectory().toAbsolutePath(), MERGE_RETRY_DELAY);
        PromptLibrary prompts = new PromptLibrary(configuration.promptDirectory(), mergeTargetBranch);
        WorkspaceLayout layout = new WorkspaceLayout(configuration.workDirectory());
        return new IssueWorkflow(configuration, gitHub, git, agentRunner, prompts, buildRunner, merger,
                shared.usage(), requests, layout, workerLogin, liveSessions, shared.board(), shared.contexts(),
                shared.pageRequests());
    }
}
