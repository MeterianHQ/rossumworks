# Release with native programs: implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A tag push publishes a release with the jar and the native programs for Linux x64 and Linux arm64 (issue #75).

**Architecture:** A Maven profile `native` builds the native program with GraalVM. Failsafe tests start the native program and run the new command `self-check`. The workflow `native.yml` builds and tests the program on two Linux runners. The workflow `release.yml` calls `native.yml`, checks the version, and publishes the files. `run.sh` prefers the native program of the machine.

**Tech Stack:** Java 21, Maven, GraalVM CE 21 (`native-maven-plugin` 0.10.6, the same as heidi-fix), maven-failsafe-plugin, GitHub Actions, bash.

---

## Facts from the investigation

- The native image of the current jar builds in 45 seconds. `run --config` gives the same result as the jar.
- A native program has no jar manifest. `FactoryVersion` reads `Implementation-Version` from the manifest, so it gives `unknown`.
- A native image contains only the resources that the configuration names.
- Jackson binds `IssueRecord` with reflection (`StatusComment`, `MainRecordReader`).
- The worker needs its shutdown hook on Ctrl-C and on `kill`. A native program runs the hook only with `--install-exit-handlers`.
- `native-maven-plugin` 0.10.6 cannot compile the JUnit 6 tests to a native image. So the native tests start the program from outside.
- `run` and `create` call GitHub before the board, the status comment, and the definition. So the native tests use the command `self-check`.

## Decisions

1. **The version comes from a filtered resource.** Maven writes the project version into `factory-version.properties`. The jar and the native program read the same file.
2. **The command `self-check` checks the parts that need GraalVM metadata.** It loads the built-in definition, writes and reads a status comment, writes an event, and serves the board on a free loopback port. It needs no network and no GitHub token.
3. **The native program has its own cache folder.** `create` copies a running jar to `~/.rossumworks/jars`, and a running native program to `~/.rossumworks/native`. `run.sh` reads both folders.
4. **The messages name the running program.** A jar gives `java -jar <path>`. A native program gives its own path.
5. **`run.sh` checks the SHA-256 of a native program.** A native program without a `.sha256` file in the release is not used.

## File structure

| File | Responsibility |
|---|---|
| `src/main/resources-filtered/factory-version.properties` | The project version, written by Maven. |
| `events/FactoryVersion.java` | Reads the version from the resource. |
| `setup/RunningProgram.java` (replaces `RunningJar.java`) | The jar or the native program of this process, and its start command. |
| `setup/ProgramCache.java` (replaces `JarCache.java`) | The cache folder that `run.sh` reads. |
| `SelfCheck.java` | Runs the checks in order, and prints one line for each check. |
| `SelfCheckStep.java` | One named check. |
| `SelfCheckSteps.java`, `BoardSelfCheck.java` | The steps. They are in the root package, because the board step uses `Main.SharedState` and `Main.createWebServer`. |
| `Main.java` | The commands `--version` and `self-check`. |
| `src/main/resources/META-INF/native-image/com.meterian/rossumworks/*` | The GraalVM configuration: resources, reflection, build arguments. |
| `src/test/java/com/meterian/factory/nativeprogram/NativeProgramIT.java` | Starts the native program and checks its output. |
| `src/main/resources/setup/run.sh` | Downloads and starts the native program, else the jar. |
| `src/test/java/com/meterian/factory/setup/RunScriptTest.java` | Runs `run.sh` with fake `curl` and `uname`. |
| `.github/workflows/native.yml` | Builds and tests the native program on two Linux runners. |
| `.github/workflows/release.yml` | Builds and publishes a release from a tag. |
| `docs/glossary.md`, `CONTRIBUTING.md`, `README.md`, `docs/getting-started.md` | The term, the release steps, and the install. |

## Tasks

### Task 1: The version from a filtered resource, and the command `--version`

**Files:** Create `src/main/resources-filtered/factory-version.properties`. Modify `pom.xml`, `events/FactoryVersion.java`, `Main.java`. Test `events/FactoryVersionTest.java`, `MainTest.java`.

- [ ] Write a failing test: `FactoryVersion.current()` is not `unknown`, and it matches `\d+\.\d+\.\d+.*`.
- [ ] Add the resource with the line `version=${project.version}`.
- [ ] Add `src/main/resources-filtered` to `<resources>` with `<filtering>true</filtering>`. Keep `src/main/resources` without filtering, because `run.sh` contains `${...}`.
- [ ] Change `FactoryVersion.current()` to read the resource. A missing resource gives `unknown`.
- [ ] Write a failing test: `--version` prints `RossumWorks <version>` and gives exit code 0.
- [ ] Add the command in `Main`.
- [ ] Run `./mvnw verify`, and commit.

### Task 2: The running program, and its cache folder

**Files:** Create `setup/RunningProgram.java`, `setup/ProgramCache.java`. Delete `RunningJar.java`, `JarCache.java`. Modify `CreateCommand.java`, `WorkerFileFinder.java`, `Main.java`, `CreateOptions.java`. Test `CreateCommandTest.java`, new `RunningProgramTest.java`.

- [ ] Write failing tests: a jar gives the start command `java -jar <path>` and the cache `~/.rossumworks/jars`. A native program gives the start command `<path>` and the cache `~/.rossumworks/native`.
- [ ] Write `RunningProgram` as a record of the path and the kind (`JAR`, `NATIVE`). `find()` detects the native program with the system property `org.graalvm.nativeimage.imagecode`, and reads the path from `ProcessHandle.current().info().command()`.
- [ ] Rename `JarCache` to `ProgramCache`. The constructor takes the cache folder.
- [ ] Change `CreateCommand` and `WorkerFileFinder` to use `RunningProgram`.
- [ ] Change the usage texts from `java -jar rossumworks.jar` to `rossumworks`, with one line that tells about `java -jar`.
- [ ] Run `./mvnw verify`, and commit.

### Task 3: The command `self-check`

**Files:** Create `SelfCheck.java`, `SelfCheckStep.java`, `SelfCheckSteps.java`, `BoardSelfCheck.java` in the root package. Modify `Main.java`. Test `SelfCheckTest.java`.

- [ ] Write a failing test: `self-check` prints one `ok` line for each step and gives exit code 0.
- [ ] Write a failing test: a step that throws prints `failed: <step>: <message>` and gives exit code 1. The next steps still run.
- [ ] Write the steps:
  - `version`: the version is not `unknown`.
  - `definition`: `BuiltInDefinition.load()` and `DefaultFactoryFolder.files(RepositoryBuild.MAVEN, "main")` with `GeneratedFactoryFolder.verifyLoads`.
  - `status comment`: render an `IssueRecord` with `StatusComment.render`, and read it again with `StatusComment.findRecord`. Each type that Jackson binds has a value.
  - `findings`: write and read a list of `Finding` with Jackson, as `pending-findings.json` does.
  - `events`: write a worker start event to a temporary `events.jsonl`, and read the line as JSON.
  - `live files`: `LiveFiles.install()` in a temporary folder.
  - `gatekeeper`: `Gatekeeper.ensure()` in a temporary folder.
  - `password`: make, format, read, and check a `PasswordHash`.
  - `board`: a configuration with `web` on `127.0.0.1:0` and no password. `Main.createWebServer`, start, GET `/`, `/page.css`, `/page.js`, `/live.js`. Each answer is 200, and the page shows the card. `/events` stays open, so the step does not use it. Stop.
- [ ] Each step uses a new temporary folder, and deletes it at the end.
- [ ] Run `./mvnw verify`, and commit.

### Task 4: The Maven profile `native`, the GraalVM configuration, and the native tests

**Files:** Modify `pom.xml`. Create `src/main/resources/META-INF/native-image/com.meterian/rossumworks/native-image.properties`, `resource-config.json`, `reflect-config.json`. Create `NativeProgramIT.java`.

- [ ] Add the profile `native`: `native-maven-plugin` 0.10.6 with `compile-no-fork` in `package`, image name `rossumworks`, `metadataRepository` enabled, and the failsafe plugin.
- [ ] `native-image.properties`: `--no-fallback --install-exit-handlers --enable-url-protocols=http,https`.
- [ ] `resource-config.json`: the patterns `web/.*`, `factory/.*`, `setup/.*`, `gatekeeper/.*`, `live/.*`, `factory-version\.properties`.
- [ ] Write `NativeProgramIT`: it starts `target/rossumworks` with `--version`, with no argument (usage), and with `self-check`. It checks the output and the exit codes.
- [ ] Run `./mvnw -Pnative verify` with GraalVM. Each failure of `self-check` names the missing metadata. Add the classes to `reflect-config.json`, or collect them with the tracing agent, and keep only the `com.meterian.factory` entries.
- [ ] Make sure that `./mvnw verify` without GraalVM still passes, and runs no `*IT`.
- [ ] Commit.

### Task 5: `run.sh` prefers the native program

**Files:** Modify `src/main/resources/setup/run.sh`. Create `setup/RunScriptTest.java`. Modify `DefaultFactoryFolderTest.java`.

- [ ] Write failing tests. Each test runs `bash run.sh` with a fake `curl`, a fake `uname`, and a fake `java` in `PATH`, and a temporary `HOME`:
  - On `x86_64`, the script downloads `rossumworks-<version>-linux-x64`, checks its SHA-256, and starts it with `run`.
  - On `aarch64`, it downloads `rossumworks-<version>-linux-arm64`.
  - A wrong SHA-256 stops the script, and the file does not stay in the cache.
  - A release without a native program for the machine gives the jar and `java -jar`.
  - A failed download uses the newest cached native program, else the newest cached jar.
- [ ] Change `run.sh`. Before it uses a native program, the script starts it once with `--version`. A native program needs glibc 2.34 or later, so on an older Linux the script uses the jar.
- [ ] Run `./mvnw verify`, and commit.

### Task 6: The workflows

**Files:** Create `.github/workflows/native.yml`, `.github/workflows/release.yml`.

- [ ] `native.yml`: `workflow_dispatch` and `workflow_call`. A matrix of `linux-x64` on `ubuntu-latest` and `linux-arm64` on `ubuntu-24.04-arm`. It runs `./mvnw -B -ntp -Pnative verify`, and keeps `target/rossumworks` as the artifact `rossumworks-<platform>` for 7 days, with `overwrite: true`.
- [ ] `release.yml`: start on a tag `v*`. Jobs `check-tag`, `native` (calls `native.yml`), and `release`. Use the same action pins as `build.yml`. `check-tag` reads the version of `pom.xml`.
- [ ] The job `release`: `./mvnw -B -ntp verify`, the version check from `factory-version.properties` (not from the manifest, because the native program reads this file), the download of the native artifacts, the rename to `rossumworks-<version>-<platform>`, `sha256sum` for each file, the previous tag, and `gh release create` with the rerun logic of heidi-fix.
- [ ] Run actionlint 1.7.12 on the workflows.
- [ ] Commit.

### Task 7: The documentation

**Files:** Modify `docs/glossary.md`, `CONTRIBUTING.md`, `README.md`, `docs/getting-started.md`.

- [ ] Add "native program" to the glossary.
- [ ] Add the section "Release" to `CONTRIBUTING.md`: the steps and what the workflow does.
- [ ] Tell about the native program, `self-check`, and the jar in the README and in `docs/getting-started.md`.
- [ ] Correct the text that says that the repository is private.
- [ ] Write the upgrade text for the release notes: replace `.factory/run.sh`.
- [ ] Commit.

## Acceptance

See issue #75. A pre-release tag, for example `v0.6.0-rc.1`, is the last check. Push it only after the owner agrees.
