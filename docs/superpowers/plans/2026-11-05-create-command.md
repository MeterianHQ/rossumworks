# The create command Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `java -jar claude-ai-factory.jar create` writes the worker configuration and opens the PR `factory/setup` with a default `.factory`, so that the owner sets up nothing by hand.

**Architecture:** A new package `com.meterian.factory.setup` holds the command. Small classes each do one job: read the options, ask the questions, find the build, write `worker.yaml`, make the `.factory` files, and prepare the repository. `CreateCommand` runs them in the order of spec section 5. `Main` sends the first word `create` to `CreateCommand`. Two small additions go outside the package: `Git.hasPath`, `GitHubClient.assignIssue`, and a clearer page reason when the default branch has no `.factory`.

**Tech Stack:** Java 21, Maven, Jackson YAML, JUnit 6, AssertJ. No new dependency.

**Spec:** `docs/superpowers/specs/2026-11-05-create-command-design.md` (it keeps section 6 of `docs/superpowers/specs/2026-11-02-workflow-definitions-design.md`).

## Global Constraints

- Build: `export JAVA_HOME=/home/bbossola/.sdkman/candidates/java/21.0.2-open; ./mvnw verify`.
- The factory never pushes to `main` or to a tag. `create` pushes only `factory/setup`, and only through the gatekeeper.
- The command never overwrites a file: not `worker.yaml`, and not a file of the repository.
- No test reaches GitHub, a real token, the real `claude`, or the default tmux server. Use `FakeGitHubClient`, `FakeGitHubServer`, local bare repositories (`TemporaryRepository`), and a fake console.
- `ANTHROPIC_API_KEY` set → stop with the message of `run`: "Remove ANTHROPIC_API_KEY: the factory uses only the Claude subscription."
- The password: 12 characters or more, asked two times, never shown, never written. Only `PasswordHash.create(password).format()` goes in the file.
- Words of the workflow: state, action, event. Clean code: verb method names, no truncated names (`ctx`, `repo`, `dir`, `e`), one-line comments only for complex code.
- Texts of questions, messages, comments, and commits: Simplified Technical English.
- Commit messages end only with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- Exit codes: 0 on success, 1 on a failure (`Main.EXIT_SUCCESS`, `Main.EXIT_FAILURE`).

## Review Focus

1. A "no" at the confirmation, or a failure before it → no `worker.yaml`, no branch on GitHub; only the clone stays. (Task 8 test `aNoAtTheConfirmationWritesNoFileAndPushesNothing`.)
2. The repository already has `.factory/factory.yaml` → no PR, no push, but `worker.yaml` is written and the `run` command is shown. (Task 8 test `anExistingFactoryFolderGivesNoPullRequest`.)
3. No console (a script) → no question; a missing `--yes`, password, or build command stops the command before it writes a file, and the message names the parameter. (Task 6 tests.)
4. A second run after a success (the PR is open) → the command shows the PR link and pushes nothing. (Task 7 test `anOpenPullRequestIsShownAndNothingIsPushed`.)
5. The forward of the gatekeeper to GitHub fails → exit 1, no PR. (Task 7 test `aBranchThatDoesNotArriveOnGitHubStopsWithoutAPullRequest`.)

---

### Task 1: Git.hasPath and GitHubClient.assignIssue

**Files:**
- Modify: `src/main/java/com/meterian/factory/git/Git.java`, `src/main/java/com/meterian/factory/git/ProcessGit.java`
- Modify: `src/main/java/com/meterian/factory/github/GitHubClient.java`, `src/main/java/com/meterian/factory/github/HttpGitHubClient.java`, `src/test/java/com/meterian/factory/testing/FakeGitHubClient.java`
- Test: `src/test/java/com/meterian/factory/git/ProcessGitTest.java`, `src/test/java/com/meterian/factory/github/HttpGitHubClientTest.java`, `src/test/java/com/meterian/factory/testing/FakeGitHubClientTest.java`

**Interfaces:**
- Produces: `boolean Git.hasPath(Path repository, String commit, String path)` — true for a file or a folder (`git cat-file -e <commit>:<path>`).
- Produces: `void GitHubClient.assignIssue(int issueNumber, String login)` — `POST /repos/<owner>/<name>/issues/<n>/assignees` with `{"assignees": [login]}`. A PR is an issue for this call.
- Produces: `List<String> FakeGitHubClient.assigneesOf(int issueNumber)`.

- [ ] **Step 1: Write the failing tests**
  - `ProcessGitTest.hasPathFindsAFileAndAFolder`: commit `.factory/prompts/a.md`; `hasPath(clone, head, ".factory")` and `hasPath(clone, head, ".factory/prompts/a.md")` are true; `hasPath(clone, head, "mvnw")` is false.
  - `HttpGitHubClientTest.assignIssueSendsTheLogin`: with `FakeGitHubServer`, `assignIssue(12, "bbossola")` sends `POST /repos/<owner>/<name>/issues/12/assignees` with the body `{"assignees":["bbossola"]}`.
  - `FakeGitHubClientTest.assignIssueKeepsTheLogin`: after `assignIssue(5, "owner")`, `assigneesOf(5)` is `["owner"]`.
- [ ] **Step 2: Run them; expect compile failures** — `./mvnw -q test -Dtest='ProcessGitTest,HttpGitHubClientTest,FakeGitHubClientTest'`.
- [ ] **Step 3: Implement the three methods.**
- [ ] **Step 4: Run the same command; expect PASS.**
- [ ] **Step 5: Commit** "Add the check of a path in git and the assignment of an issue".

### Task 2: The page reason for a default branch without .factory

**Files:**
- Modify: `src/main/java/com/meterian/factory/definition/DefinitionVersions.java` (`describeFailure`)
- Test: `src/test/java/com/meterian/factory/definition/DefinitionVersionsTest.java`

**Interfaces:**
- Produces: when no version is current and the reason is the missing main file, `warning()` is exactly
  `"The default branch has no .factory. Merge the PR factory/setup, or run claude-ai-factory create. The factory takes no new issue."`
  Other reasons keep today's text.

- [ ] **Step 1: Write the failing test** `aDefaultBranchWithoutFactoryAsksForTheSetup`: a clone whose only commit has no `.factory` (`DefinitionCommits.commitWithoutDefinition`); after `refresh()`, `current()` is empty and `warning()` equals the text above.
- [ ] **Step 2: Run it; expect FAIL** (today: "The default branch has no .factory that loads: ... does not exist ...").
- [ ] **Step 3: Implement.** Decide from the loader failure ".factory/factory.yaml does not exist" (a constant shared with `DefinitionLoader.parseMainFile`), not from a text search in a free message.
- [ ] **Step 4: Run `DefinitionVersionsTest` and `WorkerTest`; expect PASS.** Also assert in the existing `WorkerTest` style (or a new `WorkerTest.aDefaultBranchWithoutFactoryTakesNoIssue`) that an issue with the label `factory` is not taken while no version loads.
- [ ] **Step 5: Commit** "Tell the owner to merge factory/setup when the default branch has no .factory".

### Task 3: The repository from git remote

**Files:**
- Create: `src/main/java/com/meterian/factory/setup/GitHubRemoteUrl.java`
- Test: `src/test/java/com/meterian/factory/setup/GitHubRemoteUrlTest.java`

**Interfaces:**
- Produces: `static Optional<RepositoryName> GitHubRemoteUrl.parse(String url)`.

- [ ] **Step 1: Write the failing test** `readsTheForms` (parameterized): `git@github.com:MeterianHQ/heidi-fix.git`, `ssh://git@github.com/MeterianHQ/heidi-fix.git`, `https://github.com/MeterianHQ/heidi-fix`, `https://github.com/MeterianHQ/heidi-fix.git` → `MeterianHQ/heidi-fix`. `refusesOtherHosts`: `git@gitlab.com:a/b.git`, `https://github.com/only-owner`, `""` → empty.
- [ ] **Step 2: Run; expect compile failure.**
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run; expect PASS.**
- [ ] **Step 5: Commit** "Read the GitHub repository from a remote URL".

### Task 4: The default .factory and the build of the repository

**Files:**
- Create: `src/main/java/com/meterian/factory/setup/RepositoryBuild.java` (record), `src/main/java/com/meterian/factory/setup/BuildFinder.java`, `src/main/java/com/meterian/factory/setup/DefaultFactoryFolder.java`
- Test: `src/test/java/com/meterian/factory/setup/BuildFinderTest.java`, `src/test/java/com/meterian/factory/setup/DefaultFactoryFolderTest.java`

**Interfaces:**
- Produces: `record RepositoryBuild(String command, List<String> allowedTools, boolean isJava)`.
  - `mvnw` → `./mvnw verify`, tools `["Bash(./mvnw *)", "Bash(mvn *)"]`, Java.
  - `gradlew` → `./gradlew build`, tools `["Bash(./gradlew *)"]`, Java.
  - `package.json` → `npm test`, tools `["Bash(npm *)"]`, not Java.
  - `static RepositoryBuild RepositoryBuild.custom(String command, boolean isJava)`: tools `["Bash(" + firstWord + " *)"]`.
- Produces: `static Optional<RepositoryBuild> BuildFinder.find(Predicate<String> hasFile)` — checks in the order `mvnw`, `gradlew`, `package.json`. `static boolean BuildFinder.isJavaRepository(Predicate<String> hasFile)`: `mvnw`, `gradlew`, or `pom.xml`.
- Produces: `static Map<String, String> DefaultFactoryFolder.files(RepositoryBuild build, String targetBranch)` — keys are paths relative to the repository (`.factory/factory.yaml`, `.factory/prompts/<name>.md`), values are the texts. The texts are the resources under `/factory/` of the jar. In `factory.yaml`, `build.command` becomes `build.command` of `build`, `merge.target-branch` becomes `targetBranch`, and the `Bash(./mvnw *)` and `Bash(mvn *)` entries of `claude.allowed-tools` become `build.allowedTools()`. The other tools stay.

- [ ] **Step 1: Write the failing tests**
  - `BuildFinderTest`: one test per row above; `findsMavenBeforeNpm` (both files → Maven); `findsNothing` → empty; `isJavaRepository` true for `pom.xml` only.
  - `DefaultFactoryFolderTest.copiesEveryFileOfTheBuiltInDefinition`: the key set is `.factory/factory.yaml` plus `.factory/` + each prompt file that `BuiltInDefinition.load()` reads.
  - `DefaultFactoryFolderTest.loadsForEachBuild` (parameterized over Maven, Gradle, npm, `custom("make test", false)`): write the files to a temporary folder, load them with `DefinitionLoader` (through a `DefinitionFiles` of that folder); `build().command()` is the command, `allowedTools()` contains the build tools and `Read`, and does not contain `Bash(./mvnw *)` unless the build is Maven; `mergeTargetBranch()` is `"develop"` when `targetBranch` is `"develop"`.
- [ ] **Step 2: Run; expect compile failure.**
- [ ] **Step 3: Implement.** Change `factory.yaml` with Jackson (read the tree, set the three keys, write YAML), or with an exact replace of the three lines. Then a test of the result decides. Keep the comments and key order of the resource when you can. If you cannot, the load test is the gate.
- [ ] **Step 4: Run; expect PASS.**
- [ ] **Step 5: Commit** "Make the default .factory for the build of the repository".

### Task 5: worker.yaml

**Files:**
- Create: `src/main/java/com/meterian/factory/setup/WorkerSettings.java` (record), `src/main/java/com/meterian/factory/setup/WorkerConfigurationWriter.java`
- Test: `src/test/java/com/meterian/factory/setup/WorkerConfigurationWriterTest.java`

**Interfaces:**
- Produces: `record WorkerSettings(RepositoryName repository, Path workDirectory, Optional<WebSettings> web, Optional<List<String>> terminalCommand, Optional<String> javaHome)` with `record WebSettings(String address, int port, String passwordHash)` nested or in its own file.
  - `Path configurationFile()` → `workDirectory.resolve("worker.yaml")`; `Path gatekeeperDirectory()` → `workDirectory.resolve("gatekeeper.git")`.
- Produces: `static String WorkerConfigurationWriter.render(WorkerSettings settings)` and `static void WorkerConfigurationWriter.write(WorkerSettings settings) throws IOException` — `write` fails with `FileAlreadyExistsException` when the file exists (`Files.writeString(..., CREATE_NEW)`).
- The text: `repository`, `work-directory`, `gatekeeper-directory`; `build.environment.JAVA_HOME` when present; `web.address`, `web.port`, `web.password-hash` when present; `live.terminal-command` when present; one comment line for each optional section that the file does not contain, as in `factory.example.yaml`.

- [ ] **Step 1: Write the failing tests**
  - `theFileLoadsWithTheConfigurationLoader`: render all values, write to a temporary folder, `ConfigurationLoader.load` gives the same repository, folders, password hash, terminal command, and `JAVA_HOME`.
  - `theMinimalFileLoads`: no web, no terminal, no Java → loads; `web` is absent.
  - `neverOverwritesAFile`: a second `write` throws `FileAlreadyExistsException`, and the file keeps its first text.
- [ ] **Step 2: Run; expect compile failure.**
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run; expect PASS.**
- [ ] **Step 5: Commit** "Write the configuration file of the worker".

### Task 6: The options and the questions

**Files:**
- Create: `src/main/java/com/meterian/factory/setup/CreateOptions.java`, `src/main/java/com/meterian/factory/setup/SetupConsole.java` (interface), `src/main/java/com/meterian/factory/setup/SystemSetupConsole.java`, `src/main/java/com/meterian/factory/setup/SetupQuestions.java`, `src/main/java/com/meterian/factory/setup/SetupException.java`
- Test: `src/test/java/com/meterian/factory/setup/CreateOptionsTest.java`, `src/test/java/com/meterian/factory/setup/SetupQuestionsTest.java`, `src/test/java/com/meterian/factory/setup/FakeSetupConsole.java`

**Interfaces:**
- Produces: `record CreateOptions(Optional<RepositoryName> repository, Optional<Path> workDirectory, Optional<String> web, boolean noWeb, Optional<List<String>> terminalCommand, Optional<String> buildCommand, boolean yes)` and `static CreateOptions CreateOptions.parse(List<String> arguments) throws SetupException`. The parameters: `--repository owner/name`, `--work-directory <path>`, `--web <address:port>`, `--no-web`, `--terminal-command "<command>"` (split on spaces), `--build-command "<command>"`, `--yes`. An unknown parameter, a missing value, or `--web` with `--no-web` → `SetupException` with the usage of `create`.
- Produces: `interface SetupConsole { boolean isInteractive(); String ask(String question, String defaultAnswer); Optional<char[]> readPassword(String prompt); boolean confirm(String question); void show(String line); }` — `ask` returns the default on an empty answer; `confirm` returns false on an empty answer ("no" is the default). `SystemSetupConsole` uses `System.console()`; it is not interactive when `System.console()` is null.
- Produces: `SetupQuestions(SetupConsole console, Map<String, String> environment, Predicate<String> isOnPath, Path home)` with:
  - `RepositoryName askRepository(CreateOptions options, Optional<RepositoryName> fromRemote)` — default from the remote; without a default and without a console → `SetupException` naming `--repository`.
  - `Path askWorkDirectory(CreateOptions options, RepositoryName repository)` — default `<home>/.claude-ai-factory/<owner>-<name>`.
  - `Optional<WebSettings> askWeb(CreateOptions options)` — default `127.0.0.1:8090`; the answer `none` gives no page; then the password two times (`readPassword`), the same checks and messages as `Main.hashPassword`; without a console: `FACTORY_WEB_PASSWORD` of the environment, else `SetupException` "Give --no-web, or set FACTORY_WEB_PASSWORD." The password array is cleared after the hash.
  - `Optional<List<String>> askTerminalCommand(CreateOptions options)` — only when `gnome-terminal` is on the PATH: default `gnome-terminal --`; otherwise empty, no question.
  - `RepositoryBuild askBuild(CreateOptions options, Optional<RepositoryBuild> found, boolean isJava)` — `--build-command` wins; else the found build with a question whose default is its command; else a question with no default (an empty answer asks again); without a console and nothing found → `SetupException` naming `--build-command`.
  - `boolean confirm(CreateOptions options, String summary)` — `--yes` → true; without a console and without `--yes` → `SetupException` naming `--yes`; else shows the summary and asks `console.confirm`.

- [ ] **Step 1: Write the failing tests** (`FakeSetupConsole` takes a list of answers and records the questions):
  - `CreateOptionsTest`: each parameter; `refusesAnUnknownParameter`; `refusesWebWithNoWeb`; `refusesAMissingValue`.
  - `SetupQuestionsTest`: `takesTheDefaultsOnEmptyAnswers`; `aParameterRemovesTheQuestion` (no question recorded for a value given as a parameter); `noneGivesNoWebPage`; `refusesAShortPassword`; `refusesTwoDifferentPasswords`; `writesOnlyTheHash` (`PasswordHash` verifies the typed password; the settings contain no password text); `withoutAConsoleReadsTheVariable`; `withoutAConsoleAndWithoutAPasswordStops` (message contains `--no-web` and `FACTORY_WEB_PASSWORD`); `withoutGnomeTerminalAsksNothing`; `findsTheBuildAndAsksWithItsCommand`; `withoutAConsoleAndWithoutABuildStops` (message contains `--build-command`); `theConfirmationDefaultIsNo`; `withoutAConsoleTheConfirmationNeedsYes` (message contains `--yes`).
- [ ] **Step 2: Run; expect compile failure.**
- [ ] **Step 3: Implement.** Use the password checks of `Main.hashPassword` without a copy: move them to a shared method, for example `PasswordRules.verify(char[] password, char[] repeated)`, and make `hashPassword` call it.
- [ ] **Step 4: Run these tests and `MainTest`; expect PASS.**
- [ ] **Step 5: Commit** "Ask the questions of the create command".

### Task 7: The repository: clone, checks, push, and the PR

**Files:**
- Create: `src/main/java/com/meterian/factory/setup/RepositorySetup.java`, `src/main/java/com/meterian/factory/setup/RepositoryFacts.java` (record)
- Test: `src/test/java/com/meterian/factory/setup/RepositorySetupTest.java`

**Interfaces:**
- Consumes: `Git.hasPath`, `GitHubClient.assignIssue` (Task 1), `DefaultFactoryFolder.files` (Task 4), `Gatekeeper.ensure`.
- Produces: `RepositorySetup(Git git, GitHubClient gitHub, String githubUrl)`.
  - `RepositoryFacts read(Path clone) throws SetupException` — clone with `git.cloneRepository(githubUrl, clone)` when the folder does not exist, else `git.fetch(clone, "origin", <default branch>)`; returns `record RepositoryFacts(String defaultBranch, String baseCommit, boolean hasFactory, Predicate<String> hasFile)`. `hasFactory` is `hasPath(".factory/factory.yaml")`. A `.factory` folder without `factory.yaml` → `SetupException` "The default branch has a folder .factory without factory.yaml. Correct the folder, and run create again."
  - `Optional<PullRequest> findOpenSetup()` → `gitHub.openPullRequest("factory/setup")`.
  - `void verifyNoSetupBranch(RepositoryName repository) throws SetupException` — `findBranchHead("factory/setup")` present → "The branch factory/setup exists on GitHub without an open PR. The command wrote no worker.yaml. Delete the branch on GitHub with this command: gh api -X DELETE repos/<owner>/<name>/git/refs/heads/factory/setup. Then run create again."
  - `PullRequest publish(Path clone, Path gatekeeper, RepositoryFacts facts, Map<String, String> files, String owner) throws SetupException`:
    1. `Gatekeeper.ensure(gatekeeper, githubUrl)`; `git.setRemote(clone, "gatekeeper", gatekeeper.toString())`.
    2. `git.addWorktree(clone, <clone>/../setup-worktree, "factory/setup", facts.baseCommit())`; write the files; `git.commitAll(worktree, "Add the definition of the factory")`; `git.push(worktree, "gatekeeper", "factory/setup")`; remove the worktree in a `finally`.
    3. `gitHub.findBranchHead("factory/setup")` must equal the commit; else `SetupException` "GitHub does not have the branch factory/setup. The gatekeeper did not forward it: <reason>". No PR.
    4. `gitHub.createPullRequest("factory/setup", facts.defaultBranch(), "Set up the factory", body)`; `gitHub.assignIssue(number, owner)`.
  - The PR body (STE): it lists the files, says that the worker takes issues after the merge, and points to `docs/factory-definition.md` of claude-ai-factory.

- [ ] **Step 1: Write the failing tests** with `TemporaryRepository` (a bare "github" repository with one commit), `FakeGitHubClient` that reads branch heads from the bare repository (`readPullRequestHeadsFrom` or a new helper of the same kind):
  - `clonesAndReadsTheDefaultBranch`; `fetchesAnExistingClone`.
  - `findsAnExistingFactory`; `refusesAFactoryFolderWithoutTheMainFile`.
  - `publishesOnlyTheSetupBranch`: after `publish`, the bare repository has `refs/heads/factory/setup` with the files, `main` is unchanged, a PR from `factory/setup` to the default branch exists, and `assigneesOf(pr)` is `[owner]`.
  - `anOpenPullRequestIsShownAndNothingIsPushed` (Review Focus 4): `findOpenSetup()` gives the PR; the caller pushes nothing (assert in Task 8 too).
  - `refusesAnExistingSetupBranch`.
  - `aBranchThatDoesNotArriveOnGitHubStopsWithoutAPullRequest` (Review Focus 5): the gatekeeper forwards to a missing path; `publish` throws `SetupException`, and the fake has no PR.
- [ ] **Step 2: Run; expect compile failure.**
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run; expect PASS.**
- [ ] **Step 5: Commit** "Push the setup branch through the gatekeeper and open the PR".

### Task 8: CreateCommand, Main, and the README

**Files:**
- Create: `src/main/java/com/meterian/factory/setup/CreateCommand.java`
- Modify: `src/main/java/com/meterian/factory/Main.java` (dispatch and `USAGE`), `README.md` (section "Setup", and the end of the section ".factory"), `factory.example.yaml` (one comment line: "create writes this file for you")
- Test: `src/test/java/com/meterian/factory/setup/CreateCommandTest.java`, `src/test/java/com/meterian/factory/MainTest.java`

**Interfaces:**
- Consumes: Tasks 3-7; `Main.verifyNoApiKey`, `Main.findMissingCommands`, `Main.readToken` (make them package-visible to `setup` through a small public helper, or move them to a class `StartChecks` in the root package; do not copy them).
- Produces: `int CreateCommand.run(List<String> arguments)` with a test constructor that takes `SetupConsole`, the environment map, `PrintStream` output and errors, `Main.TokenSource`, `Git`, a `Function<RepositoryName, GitHubClient>`, the current folder, and the home folder.
- The order (spec section 5): parse the options → `ANTHROPIC_API_KEY` → the programs `claude`, `git`, `setsid`, `kill`, `tmux`, `jq` → the token → the repository (default from `git remote get-url origin` of the current folder through `GitHubRemoteUrl`) → the work folder → stop when `worker.yaml` exists ("The file <path> exists. The command never overwrites it.") → the web page → the terminal → `RepositorySetup.read` → when no `.factory`: an open PR → show its link and stop with exit 0, and write nothing; `verifyNoSetupBranch`; the build → the summary → `confirm` → `WorkerConfigurationWriter.write` → when no `.factory`: `publish` with the owner `gitHub.authenticatedLogin()` → print the files, the PR link, and `java -jar <jar> run --config <worker.yaml>`.
  - The confirmation is asked also when `.factory` exists (it confirms `worker.yaml`). Without a console in that case, no `--yes` is necessary (spec section 3).
  - `JAVA_HOME` goes in `WorkerSettings` only when `BuildFinder.isJavaRepository(facts.hasFile())` and the environment has `JAVA_HOME`.
- `Main.main`: the first argument `create` → `System.exit(new CreateCommand(...).run(rest))`. `USAGE` gets the line `java -jar claude-ai-factory.jar create [--repository owner/name] [--work-directory <path>] [--web <address:port> | --no-web] [--terminal-command "<command>"] [--build-command "<command>"] [--yes]`.

- [ ] **Step 1: Write the failing tests** (`CreateCommandTest`, all with fakes and local bare repositories):
  - `setsUpARepositoryWithDefaults`: empty answers + "yes" → `worker.yaml` loads; the bare repository has `factory/setup` with a `.factory` that loads; one PR, assigned; the output contains the PR link and the `run` command with the path of `worker.yaml`; exit 0.
  - `aNoAtTheConfirmationWritesNoFileAndPushesNothing` (Review Focus 1): no `worker.yaml`; no `factory/setup` in the bare repository; no PR; the clone exists; exit 0.
  - `anExistingFactoryFolderGivesNoPullRequest` (Review Focus 2): `worker.yaml` written; no push; no PR; the output says that the repository already has a `.factory` and shows the `run` command.
  - `anOpenPullRequestIsShownAndNothingIsPushed`: the output contains the link; no new commit on `factory/setup`; no `worker.yaml`.
  - `anExistingWorkerFileStopsTheCommand`; `anApiKeyStopsTheCommand`; `aMissingProgramStopsTheCommand` (the message names it); each exits 1 and writes nothing.
  - `withoutAConsoleTheCommandNeedsYes` (Review Focus 3): exit 1, no file, the message names `--yes`.
  - `addsJavaHomeForAMavenRepository`.
  - `MainTest.createIsACommand`: `Main` sends `create` to the command (for example, `create --unknown` prints the usage of `create` and exits 1).
- [ ] **Step 2: Run; expect compile failure.**
- [ ] **Step 3: Implement `CreateCommand` and the dispatch in `Main`.**
- [ ] **Step 4: Update `README.md`:** the section "Setup" becomes: 1. Log in to `claude`. 2. Log in with `gh auth login` (or set `FACTORY_GITHUB_TOKEN`). 3. Build the jar. 4. In a clone of the target repository, run `java -jar <jar> create`, and answer the questions (Enter takes the default). 5. Merge the PR `factory/setup`. 6. Run the `run` command that `create` shows. Name each parameter of `create` in one table. Remove "Until the command `claude-ai-factory create` exists, the owner commits a `.factory` to the repository by hand. ..." and say that `create` writes it.
- [ ] **Step 5: Run `./mvnw verify`; expect BUILD SUCCESS with 0 failures.**
- [ ] **Step 6: Commit** "Add the create command".
