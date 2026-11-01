package com.meterian.factory;

import com.meterian.factory.agent.ClaudeAgentRunner;
import com.meterian.factory.build.BuildRunner;
import com.meterian.factory.configuration.ConfigurationException;
import com.meterian.factory.configuration.ConfigurationLoader;
import com.meterian.factory.configuration.FactoryConfiguration;
import com.meterian.factory.git.Gatekeeper;
import com.meterian.factory.git.Git;
import com.meterian.factory.git.GitException;
import com.meterian.factory.git.ProcessGit;
import com.meterian.factory.github.GitHubClient;
import com.meterian.factory.github.GitHubException;
import com.meterian.factory.github.HttpGitHubClient;
import com.meterian.factory.merge.Merger;
import com.meterian.factory.prompt.PromptLibrary;
import com.meterian.factory.usage.UsageTracker;
import com.meterian.factory.worker.PhaseStopper;
import com.meterian.factory.worker.Worker;
import com.meterian.factory.workflow.IssueWorkflow;
import com.meterian.factory.workflow.RequestReader;
import com.meterian.factory.workflow.WorkspaceLayout;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** The command line: java -jar claude-ai-factory.jar run --config <file>. */
public final class Main {

    static final int EXIT_SUCCESS = 0;
    static final int EXIT_FAILURE = 1;
    static final int EXIT_API_KEY = 2;

    private static final String USAGE = "Usage: java -jar claude-ai-factory.jar run --config <file>";
    private static final String API_KEY_VARIABLE = "ANTHROPIC_API_KEY";
    private static final String TOKEN_VARIABLE = "FACTORY_GITHUB_TOKEN";
    private static final URI GITHUB_API = URI.create("https://api.github.com");
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration MERGE_RETRY_DELAY = Duration.ofSeconds(10);
    private static final Duration LOOP_END_TIMEOUT = Duration.ofMinutes(1);
    private static final Duration SHUTDOWN_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration TOKEN_COMMAND_TIMEOUT = Duration.ofSeconds(30);
    private static final System.Logger LOGGER = System.getLogger(Main.class.getName());

    /** A check before the start failed. The message tells the owner what to do. */
    static final class StartupException extends Exception {

        StartupException(String message) {
            super(message);
        }
    }

    private Main() {
    }

    public static void main(String[] arguments) {
        System.exit(run(arguments, System.getenv(), System.err));
    }

    /** Runs the checks in order, so that a wrong argument or environment stops the factory before any network. */
    static int run(String[] arguments, Map<String, String> environment, PrintStream errors) {
        try {
            Path configurationFile = readConfigurationPath(arguments);
            verifyNoApiKey(environment);
            FactoryConfiguration configuration = ConfigurationLoader.load(configurationFile);
            verifyCommands(configuration, environment.getOrDefault("PATH", ""));
            Gatekeeper.ensure(configuration.gatekeeperDirectory().toAbsolutePath(), configuration.target().sshUrl());
            String token = readToken(environment, Main::readTokenFromGh);
            return runWorker(configuration, token, errors);
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
        List<String> commands = List.of(configuration.claude().command(), "git", "setsid", "kill");
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
            throws InterruptedException {
        GitHubClient gitHub = new HttpGitHubClient(GITHUB_API, token, configuration.target(), createHttpClient());
        String workerLogin = gitHub.authenticatedLogin();
        ClaudeAgentRunner agentRunner = new ClaudeAgentRunner(configuration.claude(), Clock.systemUTC());
        RequestReader requests = new RequestReader(gitHub, configuration.allowedAccounts());
        BuildRunner buildRunner = new BuildRunner();
        IssueWorkflow workflow = createWorkflow(configuration, gitHub, agentRunner, buildRunner, requests, workerLogin);
        ExecutorService executor = Executors.newFixedThreadPool(configuration.limits().parallelIssues());
        PhaseStopper phaseStopper = new PhaseStopper(agentRunner, buildRunner,
                new WorkspaceLayout(configuration.workDirectory()));
        Worker worker = new Worker(configuration, gitHub, workflow, phaseStopper, requests, workerLogin, executor);
        Runtime.getRuntime().addShutdownHook(createShutdownHook(worker));
        worker.runUntilStopped();
        worker.shutDown(LOOP_END_TIMEOUT);
        if (!worker.foundAnApiKey()) {
            errors.println("The factory stopped.");
            return EXIT_SUCCESS;
        }
        errors.println("The factory stopped: a claude session used an API key.");
        return EXIT_API_KEY;
    }

    /** On Ctrl-C or a kill, the hook ends the claude process groups. They run under setsid and get no signal. */
    static Thread createShutdownHook(Worker worker) {
        return new Thread(() -> worker.shutDown(SHUTDOWN_TIMEOUT), "factory-shutdown");
    }

    private static IssueWorkflow createWorkflow(FactoryConfiguration configuration, GitHubClient gitHub,
                                                ClaudeAgentRunner agentRunner, BuildRunner buildRunner,
                                                RequestReader requests, String workerLogin) {
        Git git = new ProcessGit();
        String mergeTargetBranch = configuration.mergeTargetBranch();
        Merger merger = new Merger(git, gitHub, buildRunner, configuration.target(), mergeTargetBranch,
                configuration.gatekeeperDirectory().toAbsolutePath(), MERGE_RETRY_DELAY);
        UsageTracker usage = new UsageTracker(configuration.limits().usageThreshold(), Clock.systemUTC());
        PromptLibrary prompts = new PromptLibrary(configuration.promptDirectory(), mergeTargetBranch);
        WorkspaceLayout layout = new WorkspaceLayout(configuration.workDirectory());
        return new IssueWorkflow(configuration, gitHub, git, agentRunner, prompts, buildRunner, merger, usage,
                requests, layout, workerLogin);
    }
}
