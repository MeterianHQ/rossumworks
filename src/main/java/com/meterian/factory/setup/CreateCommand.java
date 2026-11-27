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
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The command create: it asks the questions and writes worker.yaml.
 * Then it opens the PR factory/setup, or it writes the definition folder.
 */
public final class CreateCommand {

    private static final String CLAUDE_PROGRAM = "claude";
    private static final String JAVA_HOME_VARIABLE = "JAVA_HOME";
    private static final String CLONE_FOLDER = "repository";
    private static final String RUN_SCRIPT = ".factory/run.sh";

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
    private final Optional<RunningProgram> runningProgram;

    /**
     * @param ghToken         the token of gh, when the environment has no FACTORY_GITHUB_TOKEN
     * @param gitHubConnector the client and the clone URL of the repository on GitHub
     * @param currentFolder   the folder whose remote origin gives the default repository
     * @param home            the folder of the default work folder and of the program caches
     * @param runningProgram  the program of this process, that create copies into its cache
     */
    public CreateCommand(SetupConsole console, Map<String, String> environment, PrintStream output,
                         PrintStream errors, StartChecks.TokenSource ghToken, Git git,
                         GitHubConnector gitHubConnector, Path currentFolder, Path home,
                         Optional<RunningProgram> runningProgram) {
        this.console = console;
        this.environment = environment;
        this.output = output;
        this.errors = errors;
        this.ghToken = ghToken;
        this.git = git;
        this.gitHubConnector = gitHubConnector;
        this.currentFolder = currentFolder;
        this.home = home;
        this.runningProgram = runningProgram;
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
        StartChecks.verifyCommandsOnPath(StartChecks.listRequiredCommands(CLAUDE_PROGRAM), path());
        String token = StartChecks.readToken(environment, ghToken);
        SetupQuestions questions = new SetupQuestions(console, environment, this::isOnPath, home);
        RepositoryName repository = questions.askRepository(options, OriginRemote.readRepository(currentFolder));
        Path workDirectory = questions.askWorkDirectory(options, repository).toAbsolutePath();
        verifyNoWorkerFile(WorkerSettings.configurationFileIn(workDirectory));
        Optional<CheckedDefinitionFolder> definitionFolder = checkDefinitionFolder(options);
        Optional<WebPage> web = questions.askWeb(options);
        Optional<List<String>> terminalCommand = questions.askTerminalCommand(options);
        ProviderChoice provider = questions.askProvider(options);
        StartChecks.verifyCredential(environment, provider.name());
        GitHubClient gitHub = gitHubConnector.connect(repository, token);
        RepositorySetup setup = new RepositorySetup(git, gitHub, gitHubConnector.cloneUrl(repository));
        RepositoryFacts facts = setup.read(findClone(workDirectory));
        WorkerSettings settings = new WorkerSettings(repository, workDirectory, web, terminalCommand,
                findJavaHome(facts), provider);
        if (definitionFolder.isPresent()) {
            return createWithDefinitionFolder(options, questions, settings, facts, definitionFolder.get());
        }
        return createWithPullRequest(options, questions, settings, facts, setup, gitHub);
    }

    /** The folder of --definition, checked before the clone: a wrong path then costs no clone. */
    private record CheckedDefinitionFolder(DefinitionFolderSetup setup, boolean needsDefinition) {
    }

    private static Optional<CheckedDefinitionFolder> checkDefinitionFolder(CreateOptions options)
            throws SetupException {
        if (options.definitionFolder().isEmpty()) {
            return Optional.empty();
        }
        DefinitionFolderSetup setup = new DefinitionFolderSetup(options.definitionFolder().get().toAbsolutePath());
        return Optional.of(new CheckedDefinitionFolder(setup, setup.needsDefinition()));
    }

    private static Path findClone(Path workDirectory) {
        return workDirectory.resolve(CLONE_FOLDER);
    }

    /** The definition comes from the repository: the PR factory/setup adds .factory, unless it exists already. */
    private int createWithPullRequest(CreateOptions options, SetupQuestions questions, WorkerSettings settings,
                                      RepositoryFacts facts, RepositorySetup setup, GitHubClient gitHub)
            throws SetupException {
        facts.verifyFactoryFolderIsComplete();
        if (facts.hasFactory()) {
            return createWithRepositoryFactory(options, questions, settings);
        }
        Optional<PullRequest> openSetup = setup.findOpenSetup();
        if (openSetup.isPresent()) {
            showOpenPullRequest(openSetup.get());
            return Main.EXIT_SUCCESS;
        }
        setup.verifyNoSetupBranch(settings.repository());
        RepositoryBuild build = askBuild(options, questions, facts);
        Map<String, String> files = DefaultFactoryFolder.files(build, facts.defaultBranch());
        GeneratedFactoryFolder.verifyLoads(files);
        if (!questions.confirm(options, SetupSummary.describe(settings, build, files, facts.defaultBranch()))) {
            showNothingWritten();
            return Main.EXIT_SUCCESS;
        }
        String owner = gitHub.authenticatedLogin();
        writeWorkerFile(settings);
        PullRequest pullRequest = publishOrDeleteWorkerFile(setup, findClone(settings.workDirectory()), settings,
                facts, files);
        Optional<String> assignmentProblem = setup.assignPullRequest(pullRequest, owner);
        copyRunningProgramToCache();
        showResult(settings, files, Optional.of(pullRequest), assignmentProblem);
        return Main.EXIT_SUCCESS;
    }

    private int createWithRepositoryFactory(CreateOptions options, SetupQuestions questions,
                                            WorkerSettings settings) throws SetupException {
        output.println("The repository already has a .factory. The command opens no pull request.");
        if (confirmAndWriteWorkerFile(options, questions, settings)) {
            copyRunningProgramToCache();
            showResult(settings, Map.of(), Optional.empty(), Optional.empty());
        }
        return Main.EXIT_SUCCESS;
    }

    /** The definition stays outside the repository: the command writes the folder and opens no pull request. */
    private int createWithDefinitionFolder(CreateOptions options, SetupQuestions questions,
                                           WorkerSettings settings, RepositoryFacts facts,
                                           CheckedDefinitionFolder definitionFolder) throws SetupException {
        Path folder = definitionFolder.setup().folder();
        if (!definitionFolder.needsDefinition()) {
            return keepDefinitionFolder(options, questions, settings, folder);
        }
        RepositoryBuild build = askBuild(options, questions, facts);
        Map<String, String> files = DefaultFactoryFolder.definitionFiles(build, facts.defaultBranch());
        GeneratedFactoryFolder.verifyDefinitionLoads(files);
        if (!questions.confirmDefinitionFolder(options, SetupSummary.describe(settings, build, folder, files))) {
            showNothingWritten();
            return Main.EXIT_SUCCESS;
        }
        writeWorkerFile(settings);
        writeDefinitionFolderOrDeleteWorkerFile(definitionFolder.setup(), settings, files);
        copyRunningProgramToCache();
        output.println("The command wrote the definition folder " + folder + ".");
        showResultWithDefinitionFolder(settings, folder);
        return Main.EXIT_SUCCESS;
    }

    private int keepDefinitionFolder(CreateOptions options, SetupQuestions questions, WorkerSettings settings,
                                     Path folder) throws SetupException {
        output.println("The definition folder " + folder + " has a factory.yaml. The command keeps the folder.");
        if (confirmAndWriteWorkerFile(options, questions, settings)) {
            copyRunningProgramToCache();
            showResultWithDefinitionFolder(settings, folder);
        }
        return Main.EXIT_SUCCESS;
    }

    private static RepositoryBuild askBuild(CreateOptions options, SetupQuestions questions, RepositoryFacts facts)
            throws SetupException {
        return questions.askBuild(options, BuildFinder.find(facts.hasFile()),
                BuildFinder.isJavaRepository(facts.hasFile()));
    }

    /** @return false when the owner refuses: the command then writes nothing */
    private boolean confirmAndWriteWorkerFile(CreateOptions options, SetupQuestions questions,
                                              WorkerSettings settings) throws SetupException {
        if (!questions.confirmWorkerFile(options, SetupSummary.describe(settings))) {
            showNothingWritten();
            return false;
        }
        writeWorkerFile(settings);
        return true;
    }

    /** Outside a clone, run cannot find worker.yaml, so the command always has --config. */
    private void showResultWithDefinitionFolder(WorkerSettings settings, Path definitionFolder) {
        output.println("The command wrote the file " + settings.configurationFile() + ".");
        output.println("Start the worker with this command:");
        output.println("  " + findStartCommand() + " run --config " + settings.configurationFile()
                + " --definition " + definitionFolder);
    }

    /** The run script then needs no download. A failed copy only gives the problem: the download still works. */
    private void copyRunningProgramToCache() {
        if (runningProgram.isEmpty()) {
            return;
        }
        RunningProgram program = runningProgram.get();
        String description = program.kind().description();
        if (!program.hasCacheFileName()) {
            output.println("The command does not copy the " + description + " " + program.path()
                    + ": run.sh uses only the file names of a release.");
            return;
        }
        ProgramCache cache = new ProgramCache(program.cacheDirectory(home));
        try {
            cache.copy(program.path()).ifPresent(copy -> output.println(
                    "The command copied the " + description + " to " + copy + "."));
        } catch (IOException exception) {
            output.println("Cannot copy the " + description + " to " + cache.directory() + ": "
                    + exception.getMessage());
        }
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
                                                         RepositoryFacts facts, Map<String, String> files)
            throws SetupException {
        try {
            return setup.publish(clone, settings.gatekeeperDirectory(), facts, files);
        } catch (SetupException | GitException | GitHubException failure) {
            throw new SetupException(failure.getMessage() + describeDeleteProblem(settings.configurationFile()));
        }
    }

    /** A failed write deletes the worker.yaml of this run, so that the next create does not stop on it. */
    private static void writeDefinitionFolderOrDeleteWorkerFile(DefinitionFolderSetup definitionFolder,
                                                                WorkerSettings settings, Map<String, String> files)
            throws SetupException {
        try {
            definitionFolder.write(files);
        } catch (SetupException failure) {
            throw new SetupException(failure.getMessage() + describeDeleteProblem(settings.configurationFile()));
        }
    }

    private static String describeDeleteProblem(Path configurationFile) {
        return deleteWorkerFile(configurationFile).map(problem -> "\n" + problem).orElse("");
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

    private void showResult(WorkerSettings settings, Map<String, String> files, Optional<PullRequest> pullRequest,
                            Optional<String> assignmentProblem) {
        output.println("The command wrote these files:");
        output.println("  " + settings.configurationFile());
        files.keySet().forEach(path -> output.println("  " + path + " (on the branch factory/setup)"));
        pullRequest.ifPresent(opened -> {
            output.println("The pull request: " + opened.url());
            assignmentProblem.ifPresent(output::println);
            output.println("The worker takes issues after the merge of the pull request.");
        });
        showStartCommand(settings, pullRequest.isPresent());
    }

    /** After the merge, the run script of .factory starts the latest release. Without a PR, the running program. */
    private void showStartCommand(WorkerSettings settings, boolean pullRequestAddsRunScript) {
        String program = pullRequestAddsRunScript ? RUN_SCRIPT : findStartCommand() + " run";
        output.println("Start the worker with this command, in a clone of " + settings.repository().fullName() + ":");
        output.println("  " + program + findConfigurationOption(settings));
        if (pullRequestAddsRunScript) {
            output.println("The script comes with the merge of the pull request. It runs the latest release.");
        }
    }

    /** In the default work folder, run finds worker.yaml from the clone. Another folder needs --config. */
    private String findConfigurationOption(WorkerSettings settings) {
        Path defaultWorkDirectory = WorkerSettings.defaultWorkDirectory(home, settings.repository())
                .toAbsolutePath();
        if (settings.workDirectory().equals(defaultWorkDirectory)) {
            return "";
        }
        return " --config " + settings.configurationFile();
    }

    private String findStartCommand() {
        return runningProgram.map(RunningProgram::startCommand).orElse(RunningProgram.DEFAULT_START_COMMAND);
    }
}
