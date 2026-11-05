package com.meterian.factory.setup;

import com.meterian.factory.Main;
import com.meterian.factory.Main.StartupException;
import com.meterian.factory.StartChecks;
import com.meterian.factory.configuration.RepositoryName;
import com.meterian.factory.git.Git;
import com.meterian.factory.git.GitException;
import com.meterian.factory.github.GitHubClient;
import com.meterian.factory.github.GitHubException;
import com.meterian.factory.github.PullRequest;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URISyntaxException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** The command create: it asks the questions, writes worker.yaml, and opens the PR factory/setup. */
public final class CreateCommand {

    static final List<String> REQUIRED_PROGRAMS = List.of("claude", "git", "setsid", "kill", "tmux", "jq");
    private static final String JAVA_HOME_VARIABLE = "JAVA_HOME";
    private static final String CLONE_FOLDER = "repository";
    private static final String DEFAULT_JAR = "claude-ai-factory.jar";

    /** Where the GitHub of a repository is. A test gives a fake client and a local repository. */
    public interface GitHubConnector {

        GitHubClient connect(RepositoryName repository, String token);

        default String cloneUrl(RepositoryName repository) {
            return repository.sshUrl();
        }
    }

    private final SetupConsole console;
    private final Map<String, String> environment;
    private final PrintStream output;
    private final PrintStream errors;
    private final StartChecks.TokenSource ghToken;
    private final Git git;
    private final GitHubConnector gitHubConnector;
    private final Path currentFolder;
    private final Path home;

    /**
     * @param ghToken         the token of gh, when the environment has no FACTORY_GITHUB_TOKEN
     * @param gitHubConnector the client and the clone URL of the repository on GitHub
     * @param currentFolder   the folder whose remote origin gives the default repository
     * @param home            the folder of the default work folder
     */
    public CreateCommand(SetupConsole console, Map<String, String> environment, PrintStream output,
                         PrintStream errors, StartChecks.TokenSource ghToken, Git git,
                         GitHubConnector gitHubConnector, Path currentFolder, Path home) {
        this.console = console;
        this.environment = environment;
        this.output = output;
        this.errors = errors;
        this.ghToken = ghToken;
        this.git = git;
        this.gitHubConnector = gitHubConnector;
        this.currentFolder = currentFolder;
        this.home = home;
    }

    public int run(List<String> arguments) {
        try {
            return create(CreateOptions.parse(arguments));
        } catch (SetupException | StartupException | GitException | GitHubException exception) {
            errors.println(exception.getMessage());
            return Main.EXIT_FAILURE;
        }
    }

    private int create(CreateOptions options) throws SetupException, StartupException {
        StartChecks.verifyNoApiKey(environment);
        StartChecks.verifyCommandsOnPath(REQUIRED_PROGRAMS, path());
        String token = StartChecks.readToken(environment, ghToken);
        SetupQuestions questions = new SetupQuestions(console, environment, this::isOnPath, home);
        RepositoryName repository = questions.askRepository(options, OriginRemote.readRepository(currentFolder));
        Path workDirectory = questions.askWorkDirectory(options, repository).toAbsolutePath();
        verifyNoWorkerFile(WorkerSettings.configurationFileIn(workDirectory));
        Optional<WebPage> web = questions.askWeb(options);
        Optional<List<String>> terminalCommand = questions.askTerminalCommand(options);
        GitHubClient gitHub = gitHubConnector.connect(repository, token);
        RepositorySetup setup = new RepositorySetup(git, gitHub, gitHubConnector.cloneUrl(repository));
        Path clone = workDirectory.resolve(CLONE_FOLDER);
        RepositoryFacts facts = setup.read(clone);
        WorkerSettings settings = new WorkerSettings(repository, workDirectory, web, terminalCommand,
                findJavaHome(facts));
        if (facts.hasFactory()) {
            return writeOnlyTheWorkerFile(options, questions, settings);
        }
        Optional<PullRequest> openSetup = setup.findOpenSetup();
        if (openSetup.isPresent()) {
            showOpenPullRequest(openSetup.get());
            return Main.EXIT_SUCCESS;
        }
        setup.verifyNoSetupBranch();
        RepositoryBuild build = questions.askBuild(options, BuildFinder.find(facts.hasFile()),
                BuildFinder.isJavaRepository(facts.hasFile()));
        Map<String, String> files = DefaultFactoryFolder.files(build, facts.defaultBranch());
        GeneratedFactoryFolder.verifyLoads(files);
        if (!questions.confirm(options, SetupSummary.describe(settings, build, files, facts.defaultBranch()))) {
            showNothingWritten();
            return Main.EXIT_SUCCESS;
        }
        String owner = gitHub.authenticatedLogin();
        writeWorkerFile(settings);
        PullRequest pullRequest = publishOrDeleteWorkerFile(setup, clone, settings, facts, files, owner);
        showResult(settings, files, Optional.of(pullRequest));
        return Main.EXIT_SUCCESS;
    }

    private int writeOnlyTheWorkerFile(CreateOptions options, SetupQuestions questions, WorkerSettings settings)
            throws SetupException {
        output.println("The repository already has a .factory. The command opens no pull request.");
        if (!questions.confirmWorkerFile(options, SetupSummary.describe(settings))) {
            showNothingWritten();
            return Main.EXIT_SUCCESS;
        }
        writeWorkerFile(settings);
        showResult(settings, Map.of(), Optional.empty());
        return Main.EXIT_SUCCESS;
    }

    private String path() {
        return environment.getOrDefault("PATH", "");
    }

    private boolean isOnPath(String program) {
        return StartChecks.isExecutableCommand(program, path());
    }

    private static void verifyNoWorkerFile(Path configurationFile) throws SetupException {
        if (Files.exists(configurationFile)) {
            throw new SetupException(describeExistingFile(configurationFile));
        }
    }

    private static String describeExistingFile(Path file) {
        return "The file " + file + " exists. The command never overwrites it.";
    }

    private Optional<String> findJavaHome(RepositoryFacts facts) {
        if (!BuildFinder.isJavaRepository(facts.hasFile())) {
            return Optional.empty();
        }
        return Optional.ofNullable(environment.get(JAVA_HOME_VARIABLE))
                .filter(javaHome -> !javaHome.isBlank())
                .map(String::strip);
    }

    private static void writeWorkerFile(WorkerSettings settings) throws SetupException {
        try {
            WorkerConfigurationWriter.write(settings);
        } catch (FileAlreadyExistsException exception) {
            throw new SetupException(describeExistingFile(settings.configurationFile()));
        } catch (IOException exception) {
            throw new SetupException("Cannot write the file " + settings.configurationFile() + ": "
                    + exception.getMessage());
        }
    }

    /** A failed publish deletes the worker.yaml of this run, so that the next create does not stop on it. */
    private static PullRequest publishOrDeleteWorkerFile(RepositorySetup setup, Path clone, WorkerSettings settings,
                                                         RepositoryFacts facts, Map<String, String> files,
                                                         String owner) throws SetupException {
        try {
            return setup.publish(clone, settings.gatekeeperDirectory(), facts, files, owner);
        } catch (SetupException | GitException | GitHubException failure) {
            String deleteProblem = deleteWorkerFile(settings.configurationFile()).map(problem -> "\n" + problem)
                    .orElse("");
            throw new SetupException(failure.getMessage() + deleteProblem);
        }
    }

    private static Optional<String> deleteWorkerFile(Path configurationFile) {
        try {
            Files.deleteIfExists(configurationFile);
            return Optional.empty();
        } catch (IOException exception) {
            return Optional.of("Cannot delete the file " + configurationFile + ": " + exception.getMessage()
                    + ". Delete the file before the next create.");
        }
    }

    private void showOpenPullRequest(PullRequest pullRequest) {
        output.println("The pull request from factory/setup is open: " + pullRequest.url());
        output.println("The command writes no file. Merge the pull request, and run create again.");
    }

    private void showNothingWritten() {
        output.println("The command wrote no file and pushed nothing.");
    }

    private void showResult(WorkerSettings settings, Map<String, String> files, Optional<PullRequest> pullRequest) {
        output.println("The command wrote these files:");
        output.println("  " + settings.configurationFile());
        files.keySet().forEach(path -> output.println("  " + path + " (on the branch factory/setup)"));
        pullRequest.ifPresent(opened -> {
            output.println("The pull request: " + opened.url());
            output.println("The worker takes issues after the merge of the pull request.");
        });
        output.println("Start the worker with this command:");
        output.println("  java -jar " + findJarPath() + " run --config " + settings.configurationFile());
    }

    /** The path of the running jar. Outside a jar, for example in a test, the name of the jar. */
    static String findJarPath() {
        try {
            CodeSource source = Main.class.getProtectionDomain().getCodeSource();
            if (source != null) {
                Path location = Path.of(source.getLocation().toURI());
                if (location.toString().endsWith(".jar")) {
                    return location.toString();
                }
            }
        } catch (URISyntaxException | IllegalArgumentException | SecurityException exception) {
            return DEFAULT_JAR;
        }
        return DEFAULT_JAR;
    }
}
