package com.meterian.factory.setup;

import static com.meterian.factory.testing.TemporaryRepository.commitFile;
import static com.meterian.factory.testing.TemporaryRepository.createBare;
import static com.meterian.factory.testing.TemporaryRepository.createClone;
import static com.meterian.factory.testing.TemporaryRepository.run;
import static org.assertj.core.api.Assertions.assertThat;

import com.meterian.factory.configuration.ConfigurationLoader;
import com.meterian.factory.configuration.FactoryConfiguration;
import com.meterian.factory.configuration.RepositoryName;
import com.meterian.factory.definition.DefinitionLoader;
import com.meterian.factory.definition.FactoryDefinition;
import com.meterian.factory.definition.GitDefinitionFiles;
import com.meterian.factory.git.Git;
import com.meterian.factory.git.ProcessGit;
import com.meterian.factory.github.GitHubClient;
import com.meterian.factory.github.PullRequest;
import com.meterian.factory.testing.FakeGitHubClient;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CreateCommandTest {

    private static final String PASSWORD = "correct-horse-battery";
    private static final String DEFAULT_START_LINE = "Start the worker with this command, in a clone of owner/project:";
    private static final List<String> PROGRAMS = List.of("claude", "git", "setsid", "kill", "tmux", "jq");

    @TempDir
    Path temporaryDirectory;

    private final Git git = new ProcessGit();
    private final FakeGitHubClient gitHub = new FakeGitHubClient();
    private final Map<String, String> environment = new HashMap<>();
    private final ByteArrayOutputStream outputBytes = new ByteArrayOutputStream();
    private final ByteArrayOutputStream errorBytes = new ByteArrayOutputStream();
    private Path github;
    private Path seed;
    private Path currentFolder;
    private Path home;
    private Path programs;

    @BeforeEach
    void createRepositories() throws IOException {
        github = createBare(temporaryDirectory.resolve("github.git"));
        seed = createClone(github, temporaryDirectory.resolve("seed"));
        currentFolder = Files.createDirectories(temporaryDirectory.resolve("current"));
        run(currentFolder, "init", "-q");
        run(currentFolder, "remote", "add", "origin", "git@github.com:owner/project.git");
        home = temporaryDirectory.resolve("home");
        programs = createPrograms(PROGRAMS);
        environment.put("PATH", programs.toString());
        gitHub.readPullRequestHeadsFrom(github);
        gitHub.setAuthenticatedLogin("bbossola");
    }

    @Test
    void setsUpARepositoryWithDefaults() throws Exception {
        pushToMain("package.json", "{}\n");
        FakeSetupConsole console = answering("", "", "", "", "y");

        int exitCode = runCreate(console);

        assertThat(exitCode).isEqualTo(0);
        assertThat(console.questions).containsExactly("The starting repository (owner/name)", "The work folder",
                "The web page address (address:port, or none for no page)", "Password for the web page: ",
                "Repeat the password: ", "The build command", "Write the files and open the pull request?");
        FactoryConfiguration configuration = ConfigurationLoader.load(workerFile());
        assertThat(configuration.repository()).isEqualTo(new RepositoryName("owner", "project"));
        assertThat(configuration.workDirectory()).isEqualTo(workDirectory());
        assertThat(configuration.web()).isPresent();
        FactoryDefinition definition = DefinitionLoader.load(
                new GitDefinitionFiles(git, github, run(github, "rev-parse", "factory/setup")));
        assertThat(definition.build().command()).isEqualTo("npm test");
        assertThat(gitHub.pullRequests()).hasSize(1);
        PullRequest pullRequest = gitHub.pullRequests().get(0);
        assertThat(gitHub.assigneesOf(pullRequest.number())).containsExactly("bbossola");
        assertThat(outputText()).contains(workerFile().toString(), ".factory/factory.yaml", pullRequest.url(),
                DEFAULT_START_LINE);
    }

    @Test
    void aNoAtTheConfirmationWritesNoFileAndPushesNothing() {
        pushToMain("package.json", "{}\n");

        int exitCode = runCreate(answering("", "", "", "", "n"));

        assertThat(exitCode).isEqualTo(0);
        assertThat(workerFile()).doesNotExist();
        assertThat(branchesOfGitHub()).isEqualTo("refs/heads/main");
        assertThat(gitHub.pullRequests()).isEmpty();
        assertThat(workDirectory().resolve("repository/.git")).exists();
        assertThat(outputText()).contains("The command wrote no file and pushed nothing.");
    }

    @Test
    void anExistingFactoryFolderGivesNoPullRequest() throws Exception {
        pushToMain(".factory/factory.yaml", "build: {}\n");
        FakeSetupConsole console = answering("", "", "", "y");

        int exitCode = runCreate(console);

        assertThat(exitCode).isEqualTo(0);
        assertThat(console.questions).contains("Write the file worker.yaml?")
                .doesNotContain("The build command", "Write the files and open the pull request?");
        ConfigurationLoader.load(workerFile());
        assertThat(branchesOfGitHub()).isEqualTo("refs/heads/main");
        assertThat(gitHub.pullRequests()).isEmpty();
        assertThat(outputText()).contains("The repository already has a .factory. The command opens no pull request.",
                DEFAULT_START_LINE);
    }

    @Test
    void anExistingFactoryFolderNeedsNoYesWithoutAConsole() {
        pushToMain(".factory/factory.yaml", "build: {}\n");

        int exitCode = runCreate(FakeSetupConsole.withoutTerminal(), "--no-web");

        assertThat(exitCode).isEqualTo(0);
        assertThat(workerFile()).exists();
        assertThat(gitHub.pullRequests()).isEmpty();
    }

    @Test
    void anOpenPullRequestIsShownAndNothingIsPushed() {
        pushToMain("package.json", "{}\n");
        run(github, "branch", "factory/setup", "main");
        String headBefore = run(github, "rev-parse", "factory/setup");
        PullRequest existing = gitHub.createPullRequest("factory/setup", "main", "Set up", "body");

        int exitCode = runCreate(answering("", "", "", "", "y"));

        assertThat(exitCode).isEqualTo(0);
        assertThat(outputText()).contains(existing.url());
        assertThat(run(github, "rev-parse", "factory/setup")).isEqualTo(headBefore);
        assertThat(gitHub.pullRequests()).containsExactly(existing);
        assertThat(workerFile()).doesNotExist();
    }

    @Test
    void aFailedPushLeavesNoWorkerFile() throws IOException {
        pushToMain("package.json", "{}\n");
        refuseEveryPushToGitHub();

        int exitCode = runCreate(answering("", "", "", "", "y"));

        assertThat(exitCode).isEqualTo(1);
        assertThat(errorText()).contains("GitHub does not have the branch factory/setup.");
        assertThat(workerFile()).doesNotExist();
        assertThat(gitHub.pullRequests()).isEmpty();
        errorBytes.reset();

        int secondExitCode = runCreate(answering("", "", "", "", "y"));

        assertThat(secondExitCode).isEqualTo(1);
        assertThat(errorText()).contains("GitHub does not have the branch factory/setup.")
                .doesNotContain("exists. The command never overwrites it.");
        assertThat(workerFile()).doesNotExist();
    }

    @Test
    void aFailedPullRequestLeavesNoWorkerFile() {
        pushToMain("package.json", "{}\n");
        gitHub.failTheNextPullRequestCreation();

        int exitCode = runCreate(answering("", "", "", "", "y"));

        assertThat(exitCode).isEqualTo(1);
        assertThat(errorText()).contains("GitHub answered 502 for POST /pulls");
        assertThat(workerFile()).doesNotExist();
        assertThat(gitHub.pullRequests()).isEmpty();
    }

    @Test
    void theDefaultWorkFolderGivesTheStartCommandWithoutTheOptionConfig() {
        pushToMain("package.json", "{}\n");

        runCreate(answering("", "", "", "", "y"));

        assertThat(outputText()).contains(DEFAULT_START_LINE + System.lineSeparator()
                + "  .factory/run.sh" + System.lineSeparator()
                + "The script comes with the merge of the pull request. It runs the latest release.")
                .doesNotContain("--config", "java -jar");
    }

    @Test
    void anExistingFactoryFolderGivesTheStartCommandWithTheJar() {
        pushToMain(".factory/factory.yaml", "build: {}\n");

        runCreate(answering("", "", "", "y"));

        assertThat(outputText()).contains(DEFAULT_START_LINE + System.lineSeparator()
                + "  java -jar claude-ai-factory.jar run" + System.lineSeparator()).doesNotContain("run.sh");
    }

    @Test
    void thePullRequestHasTheExecutableRunScript() {
        pushToMain("package.json", "{}\n");

        runCreate(answering("", "", "", "", "y"));

        assertThat(run(github, "ls-tree", "factory/setup", ".factory/run.sh")).startsWith("100755 blob ");
    }

    @Test
    void anotherWorkFolderGivesTheStartCommandWithTheOptionConfig() {
        pushToMain("package.json", "{}\n");
        Path otherWorkDirectory = temporaryDirectory.resolve("other-work");

        int exitCode = runCreate(answering("", otherWorkDirectory.toString(), "", "", "y"));

        assertThat(exitCode).isEqualTo(0);
        assertThat(outputText()).contains(DEFAULT_START_LINE + System.lineSeparator()
                + "  .factory/run.sh --config " + otherWorkDirectory.resolve("worker.yaml"));
    }

    @Test
    void aFailedAssignmentKeepsTheWorkerFileAndShowsThePullRequest() throws Exception {
        pushToMain("package.json", "{}\n");
        gitHub.failTheNextAssignment();

        int exitCode = runCreate(answering("", "", "", "", "y"));

        assertThat(exitCode).isEqualTo(0);
        ConfigurationLoader.load(workerFile());
        assertThat(gitHub.pullRequests()).hasSize(1);
        PullRequest pullRequest = gitHub.pullRequests().get(0);
        assertThat(gitHub.assigneesOf(pullRequest.number())).isEmpty();
        assertThat(outputText()).contains("The pull request: " + pullRequest.url(),
                "Cannot assign the PR to bbossola: GitHub answered 422 for POST /issues/"
                        + pullRequest.number() + "/assignees. Assign it by hand.",
                DEFAULT_START_LINE);
    }

    @Test
    void anExistingWorkerFileStopsTheCommand() throws IOException {
        Files.createDirectories(workDirectory());
        Files.writeString(workerFile(), "old\n");

        int exitCode = runCreate(answering("", "", "", "", "y"));

        assertThat(exitCode).isEqualTo(1);
        assertThat(errorText()).contains("The file " + workerFile() + " exists. The command never overwrites it.");
        assertThat(Files.readString(workerFile())).isEqualTo("old\n");
        assertThat(workDirectory().resolve("repository")).doesNotExist();
        assertThat(gitHub.pullRequests()).isEmpty();
    }

    @Test
    void anApiKeyStopsTheCommand() {
        environment.put("ANTHROPIC_API_KEY", "key");

        int exitCode = runCreate(answering("", "", "", "", "y"));

        assertThat(exitCode).isEqualTo(1);
        assertThat(errorText()).contains("Remove ANTHROPIC_API_KEY: the factory uses only the Claude subscription.");
        assertThat(workDirectory()).doesNotExist();
    }

    @Test
    void aMissingProgramStopsTheCommand() throws IOException {
        Files.delete(programs.resolve("tmux"));

        int exitCode = runCreate(answering("", "", "", "", "y"));

        assertThat(exitCode).isEqualTo(1);
        assertThat(errorText()).contains("The factory needs these commands on the PATH: tmux");
        assertThat(workDirectory()).doesNotExist();
    }

    @Test
    void withoutAConsoleTheCommandNeedsYes() {
        pushToMain("package.json", "{}\n");

        int exitCode = runCreate(FakeSetupConsole.withoutTerminal(), "--no-web");

        assertThat(exitCode).isEqualTo(1);
        assertThat(errorText()).contains("--yes");
        assertThat(workerFile()).doesNotExist();
        assertThat(branchesOfGitHub()).isEqualTo("refs/heads/main");
        assertThat(gitHub.pullRequests()).isEmpty();
    }

    @Test
    void withoutAConsoleAndWithYesTheCommandOpensThePullRequest() {
        pushToMain("package.json", "{}\n");

        int exitCode = runCreate(FakeSetupConsole.withoutTerminal(), "--no-web", "--yes");

        assertThat(exitCode).isEqualTo(0);
        assertThat(workerFile()).exists();
        assertThat(gitHub.pullRequests()).hasSize(1);
    }

    @Test
    void addsJavaHomeForAMavenRepository() throws Exception {
        pushToMain("mvnw", "#!/bin/sh\n");
        environment.put("JAVA_HOME", "/opt/jdk-21");

        int exitCode = runCreate(answering("", "", "", "", "y"));

        assertThat(exitCode).isEqualTo(0);
        assertThat(ConfigurationLoader.load(workerFile()).buildEnvironment()).containsEntry("JAVA_HOME", "/opt/jdk-21");
    }

    @Test
    void addsNoJavaHomeForARepositoryThatIsNotJava() throws Exception {
        pushToMain("package.json", "{}\n");
        environment.put("JAVA_HOME", "/opt/jdk-21");

        runCreate(answering("", "", "", "", "y"));

        assertThat(ConfigurationLoader.load(workerFile()).buildEnvironment()).doesNotContainKey("JAVA_HOME");
    }

    @Test
    void aFolderOutsideGitGivesNoDefaultRepository() throws IOException {
        currentFolder = Files.createDirectories(temporaryDirectory.resolve("not-git"));

        int exitCode = runCreate(FakeSetupConsole.withoutTerminal(), "--no-web", "--yes");

        assertThat(exitCode).isEqualTo(1);
        assertThat(errorText()).contains("Give --repository owner/name.");
    }

    private int runCreate(SetupConsole console, String... arguments) {
        CreateCommand command = new CreateCommand(console, environment,
                new PrintStream(outputBytes, true, StandardCharsets.UTF_8),
                new PrintStream(errorBytes, true, StandardCharsets.UTF_8),
                () -> Optional.of("token"), git, new LocalGitHub(), currentFolder, home);
        return command.run(List.of(arguments));
    }

    /** The GitHub of the tests: the fake client, and the local bare repository as the remote. */
    private final class LocalGitHub implements CreateCommand.GitHubConnector {

        @Override
        public GitHubClient connect(RepositoryName repository, String token) {
            return gitHub;
        }

        @Override
        public String cloneUrl(RepositoryName repository) {
            return github.toString();
        }
    }

    private static FakeSetupConsole answering(String... answers) {
        return new FakeSetupConsole(true, List.of(answers), List.of(PASSWORD, PASSWORD));
    }

    private void refuseEveryPushToGitHub() throws IOException {
        Path hook = Files.writeString(github.resolve("hooks/pre-receive"), "#!/bin/sh\necho push-refused >&2\nexit 1\n");
        assertThat(hook.toFile().setExecutable(true)).isTrue();
    }

    private Path createPrograms(List<String> names) throws IOException {
        Path folder = Files.createDirectories(temporaryDirectory.resolve("bin"));
        for (String name : names) {
            Path program = Files.writeString(folder.resolve(name), "#!/bin/sh\n");
            assertThat(program.toFile().setExecutable(true)).isTrue();
        }
        return folder;
    }

    private void pushToMain(String fileName, String content) {
        commitFile(seed, fileName, content, "Add " + fileName);
        run(seed, "push", "-q", "origin", "main");
    }

    private String branchesOfGitHub() {
        return run(github, "for-each-ref", "--format=%(refname)", "refs/heads");
    }

    private Path workDirectory() {
        return home.resolve(".claude-ai-factory/owner-project");
    }

    private Path workerFile() {
        return workDirectory().resolve("worker.yaml");
    }

    private String outputText() {
        return outputBytes.toString(StandardCharsets.UTF_8);
    }

    private String errorText() {
        return errorBytes.toString(StandardCharsets.UTF_8);
    }
}
