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
import java.util.function.Supplier;

/** The command line: java -jar claude-ai-factory.jar run --config <file>. */
public final class Main {

    static final int EXIT_FAILURE = 1;
    static final int EXIT_API_KEY = 2;

    private static final String USAGE = "Usage: java -jar claude-ai-factory.jar run --config <file>";
    private static final String API_KEY_VARIABLE = "ANTHROPIC_API_KEY";
    private static final String TOKEN_VARIABLE = "FACTORY_GITHUB_TOKEN";
    private static final URI GITHUB_API = URI.create("https://api.github.com");
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration MERGE_RETRY_DELAY = Duration.ofSeconds(10);
    private static final Duration LOOP_END_TIMEOUT = Duration.ofMinutes(1);

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

    static String readToken(Map<String, String> environment, Supplier<Optional<String>> ghToken)
            throws StartupException {
        String fromEnvironment = environment.getOrDefault(TOKEN_VARIABLE, "");
        if (!fromEnvironment.isBlank()) {
            return fromEnvironment.strip();
        }
        return ghToken.get()
                .filter(token -> !token.isBlank())
                .orElseThrow(() -> new StartupException("Set FACTORY_GITHUB_TOKEN, or log in with gh auth login."));
    }

    /** Runs gh auth token. A missing gh or a failure gives no token. */
    private static Optional<String> readTokenFromGh() {
        try {
            Process process = new ProcessBuilder("gh", "auth", "token")
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
            return process.waitFor() == 0 ? Optional.of(output) : Optional.empty();
        } catch (IOException exception) {
            return Optional.empty();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return Optional.empty();
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
        IssueWorkflow workflow = createWorkflow(configuration, gitHub, agentRunner, requests, workerLogin);
        ExecutorService executor = Executors.newFixedThreadPool(configuration.limits().parallelIssues());
        Worker worker = new Worker(configuration, gitHub, workflow, agentRunner, requests, workerLogin, executor);
        try {
            worker.runUntilStopped();
        } finally {
            executor.shutdown();
            executor.awaitTermination(LOOP_END_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        }
        errors.println("The factory stopped: a claude session used an API key.");
        return EXIT_API_KEY;
    }

    private static IssueWorkflow createWorkflow(FactoryConfiguration configuration, GitHubClient gitHub,
                                                ClaudeAgentRunner agentRunner, RequestReader requests,
                                                String workerLogin) {
        Git git = new ProcessGit();
        BuildRunner buildRunner = new BuildRunner();
        String mergeTargetBranch = configuration.mergeTargetBranch();
        Merger merger = new Merger(git, gitHub, buildRunner, configuration.target(), mergeTargetBranch,
                MERGE_RETRY_DELAY);
        UsageTracker usage = new UsageTracker(configuration.limits().usageThreshold(), Clock.systemUTC());
        PromptLibrary prompts = new PromptLibrary(configuration.promptDirectory(), mergeTargetBranch);
        WorkspaceLayout layout = new WorkspaceLayout(configuration.workDirectory());
        return new IssueWorkflow(configuration, gitHub, git, agentRunner, prompts, buildRunner, merger, usage,
                requests, layout, workerLogin);
    }
}
