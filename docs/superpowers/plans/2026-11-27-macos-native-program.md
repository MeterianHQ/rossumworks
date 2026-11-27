# The native program for macOS — implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Each release publishes the native program `rossumworks-<version>-macos-arm64`, and `run.sh` uses it on a Mac with arm64.

**Architecture:** `native.yml` gets a third matrix row on `macos-latest`. A new script
`.github/scripts/collect-native-programs.sh` gives the programs their release names, and a test script checks
it. `run.sh` and `RunningProgram` know the new platform name `macos-arm64`. The spec is
`docs/superpowers/specs/2026-11-27-macos-native-program-design.md`.

**Tech Stack:** GitHub Actions, GraalVM native image, bash, Java 21, JUnit 5, AssertJ.

All the paths are relative to the worktree `.worktree/macos-native`. All the text in the files obeys the
writing style of `CLAUDE.md`.

---

## File structure

| File | Change | Responsibility |
|---|---|---|
| `docs/glossary.md` | Modify | The new term "quarantine flag". |
| `src/main/java/com/meterian/factory/setup/RunningProgram.java` | Modify | The cache file names of a native program. |
| `src/test/java/com/meterian/factory/setup/RunningProgramTest.java` | Modify | The test of the new name. |
| `src/main/resources/setup/run.sh` | Modify | The platform `macos-arm64`, and the fallback to `shasum`. |
| `src/test/java/com/meterian/factory/setup/RunScriptTest.java` | Modify | The tests of the two `run.sh` changes. |
| `.github/scripts/collect-native-programs.sh` | Create | Gives the native programs their release names. |
| `.github/scripts/test-collect-native-programs.sh` | Create | Tests `collect-native-programs.sh`. |
| `.github/workflows/build.yml` | Modify | Runs the test script. |
| `.github/workflows/release.yml` | Modify | Uses the script, and gets the macOS artifact. |
| `.github/workflows/native.yml` | Modify | Builds and tests on `macos-latest`. Shows the minimum macOS version. |
| `README.md`, `docs/getting-started.md`, `docs/tutorial.md`, `CONTRIBUTING.md` | Modify | The Mac program in the documents. |

---

### Task 1: The term "quarantine flag" in the glossary

**Files:**
- Modify: `docs/glossary.md:33-34`

- [ ] **Step 1: Add the row**

Put this row between the row `provider` and the row `record`:

```markdown
| quarantine flag | The mark `com.apple.quarantine` that a browser puts on a downloaded file on macOS. macOS blocks a program with this mark and no Apple signature. `xattr -d com.apple.quarantine <program>` removes the mark. See [Getting started](getting-started.md). |
```

- [ ] **Step 2: Commit**

```bash
git add docs/glossary.md
git commit -m "Add the term quarantine flag to the glossary"
```

---

### Task 2: `RunningProgram` knows the name of the Mac program

**Files:**
- Modify: `src/main/java/com/meterian/factory/setup/RunningProgram.java:28`
- Test: `src/test/java/com/meterian/factory/setup/RunningProgramTest.java:31-41`

- [ ] **Step 1: Write the failing test**

In `onlyTheFileNamesOfAReleaseAreCacheFileNames`, after the line of `linux-arm64`, add:

```java
        assertThat(nativeProgram("rossumworks-0.6.0-macos-arm64").hasCacheFileName()).isTrue();
        assertThat(nativeProgram("rossumworks-0.6.0-macos-x64").hasCacheFileName()).isFalse();
```

- [ ] **Step 2: Run the test and make sure that it fails**

Run: `./mvnw -B -ntp -q test -Dtest=RunningProgramTest`
Expected: FAIL in `onlyTheFileNamesOfAReleaseAreCacheFileNames`, because `macos-arm64` gives `false`.

- [ ] **Step 3: Change the pattern**

```java
        NATIVE("native program", ".rossumworks/native", "rossumworks-.+-(linux-x64|linux-arm64|macos-arm64)");
```

- [ ] **Step 4: Run the test and make sure that it passes**

Run: `./mvnw -B -ntp -q test -Dtest=RunningProgramTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/meterian/factory/setup/RunningProgram.java src/test/java/com/meterian/factory/setup/RunningProgramTest.java
git commit -m "Let create copy the native program of a Mac to the cache"
```

---

### Task 3: `run.sh` gives the platform `macos-arm64`

**Files:**
- Modify: `src/main/resources/setup/run.sh:33-43` and `:127`
- Test: `src/test/java/com/meterian/factory/setup/RunScriptTest.java:31` and `:154-163`

- [ ] **Step 1: Write the failing tests**

After the constant `ARM64_PROGRAM`, add:

```java
    private static final String MAC_PROGRAM = "rossumworks-" + VERSION + "-macos-arm64";
```

Replace the test `anotherSystemThanLinuxGivesTheJar` with these two tests:

```java
    @Test
    void startsTheNativeProgramOfAMacWithArm64() throws Exception {
        publishNativeProgramWithChecksum(ARM64_PROGRAM);
        publishNativeProgramWithChecksum(MAC_PROGRAM);
        publishAsset(JAR, "jar");

        ScriptResult result = runScript("arm64", Map.of("FAKE_SYSTEM", "Darwin"));

        assertThat(result.text()).contains("Download of " + MAC_PROGRAM, "native run")
                .doesNotContain(ARM64_PROGRAM, "java");
        assertThat(result.exitCode()).isEqualTo(0);
        assertThat(nativeCache().resolve(MAC_PROGRAM)).isExecutable();
    }

    @Test
    void aMacWithIntelGivesTheJar() throws Exception {
        publishNativeProgramWithChecksum(X64_PROGRAM);
        publishNativeProgramWithChecksum(MAC_PROGRAM);
        publishAsset(JAR, "jar");

        ScriptResult result = runScript("x86_64", Map.of("FAKE_SYSTEM", "Darwin"));

        assertThat(result.text()).contains("java -jar " + jarCache().resolve(JAR) + " run")
                .doesNotContain("native run");
        assertThat(result.exitCode()).isEqualTo(0);
    }
```

The test `aMachineWithoutATokenDownloadsTheJar` does not change. It publishes no native program, so a Mac with
arm64 still gets the jar.

- [ ] **Step 2: Run the tests and make sure that one fails**

Run: `./mvnw -B -ntp -q test -Dtest=RunScriptTest`
Expected: FAIL in `startsTheNativeProgramOfAMacWithArm64`. The output has `java -jar`. `aMacWithIntelGivesTheJar` passes.

- [ ] **Step 3: Change `find_platform`**

Replace the function with:

```bash
# Prints the platform name of the native programs, for example linux-x64. A machine without a program gives nothing.
find_platform() {
    case "$(uname -s)-$(uname -m)" in
        Linux-x86_64) echo "linux-x64" ;;
        Linux-aarch64 | Linux-arm64) echo "linux-arm64" ;;
        Darwin-arm64) echo "macos-arm64" ;;
        *) return 1 ;;
    esac
}
```

- [ ] **Step 4: Change the comment of `verify_native_program_starts`**

Replace the comment line above the function with:

```bash
# A native program does not start on an older system: Linux before glibc 2.34, or an older macOS. Then the script uses the jar.
```

- [ ] **Step 5: Run the tests and make sure that they pass**

Run: `./mvnw -B -ntp -q test -Dtest=RunScriptTest`
Expected: PASS for all the tests.

- [ ] **Step 6: Commit**

```bash
git add src/main/resources/setup/run.sh src/test/java/com/meterian/factory/setup/RunScriptTest.java
git commit -m "Start the native program of a Mac with arm64 in run.sh"
```

---

### Task 4: `run.sh` uses `shasum` when `sha256sum` is missing

**Files:**
- Modify: `src/main/resources/setup/run.sh:77-85`
- Test: `src/test/java/com/meterian/factory/setup/RunScriptTest.java`

- [ ] **Step 1: Write the failing test**

Add this test after `aCachedNativeProgramNeedsNoNewDownload`:

```java
    @Test
    void aMachineWithoutSha256sumChecksTheProgramWithShasum() throws Exception {
        publishNativeProgramWithChecksum(MAC_PROGRAM);
        writeExecutable(fakeCommands.resolve("shasum"), createFakeShasum(computeSha256(NATIVE_TEXT)));

        ScriptResult result = runScript("arm64", Map.of("FAKE_SYSTEM", "Darwin",
                "PATH", fakeCommands + ":" + linkSystemCommandsWithoutSha256sum()));

        assertThat(result.text()).contains("shasum -a 256 ", "native run");
        assertThat(result.exitCode()).isEqualTo(0);
    }
```

Add these helper methods after `jarCache()`:

```java
    /** A folder with a link to each command of SYSTEM_PATH, except sha256sum. Old macOS versions have no sha256sum. */
    private Path linkSystemCommandsWithoutSha256sum() throws IOException {
        Path systemCommands = Files.createDirectories(temporaryDirectory.resolve("system-without-sha256sum"));
        for (String directory : SYSTEM_PATH.split(":")) {
            try (Stream<Path> commands = Files.list(Path.of(directory))) {
                for (Path command : commands.toList()) {
                    linkCommand(systemCommands, command);
                }
            }
        }
        return systemCommands;
    }

    private static void linkCommand(Path systemCommands, Path command) throws IOException {
        String name = command.getFileName().toString();
        Path link = systemCommands.resolve(name);
        boolean isUsable = Files.isExecutable(command) && !Files.isDirectory(command);
        if (isUsable && !name.equals("sha256sum") && Files.notExists(link, LinkOption.NOFOLLOW_LINKS)) {
            Files.createSymbolicLink(link, command);
        }
    }

    /** A fake shasum shows its arguments, and prints the SHA-256 in the format of shasum. */
    private static String createFakeShasum(String sha256) {
        return "#!/bin/sh\necho \"shasum $*\" >&2\necho \"" + sha256 + "  $3\"\n";
    }
```

Add these imports:

```java
import java.nio.file.LinkOption;
import java.util.stream.Stream;
```

- [ ] **Step 2: Run the test and make sure that it fails**

Run: `./mvnw -B -ntp -q test -Dtest=RunScriptTest#aMachineWithoutSha256sumChecksTheProgramWithShasum`
Expected: FAIL. The output has `sha256sum: command not found`, and no `shasum -a 256`.

- [ ] **Step 3: Add `compute_sha256` and use it**

Replace `verify_checksum` with:

```bash
# Old macOS versions have no sha256sum, but they have shasum.
compute_sha256() {
    local file="$1"
    if command -v sha256sum > /dev/null; then
        sha256sum "${file}"
    else
        shasum -a 256 "${file}"
    fi
}

# Compares the SHA-256 of the file with the first word of the checksum file.
verify_checksum() {
    local file="$1"
    local checksum_file="$2"
    local expected actual
    expected=$(cut -d ' ' -f 1 "${checksum_file}")
    actual=$(compute_sha256 "${file}" | cut -d ' ' -f 1)
    [[ -n "${expected}" && "${expected}" == "${actual}" ]]
}
```

- [ ] **Step 4: Run all the tests of the script and make sure that they pass**

Run: `./mvnw -B -ntp -q test -Dtest=RunScriptTest`
Expected: PASS for all the tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/resources/setup/run.sh src/test/java/com/meterian/factory/setup/RunScriptTest.java
git commit -m "Check the SHA-256 with shasum when sha256sum is missing"
```

---

### Task 5: The script `collect-native-programs.sh`

**Files:**
- Create: `.github/scripts/collect-native-programs.sh`
- Create: `.github/scripts/test-collect-native-programs.sh`
- Modify: `.github/workflows/build.yml` (job `workflows`)
- Modify: `.github/workflows/release.yml` (steps "Get the native programs" and "Prepare the release files")

- [ ] **Step 1: Write the test script**

`.github/scripts/test-collect-native-programs.sh`:

```bash
#!/usr/bin/env bash
# Tests collect-native-programs.sh with a temporary folder of downloaded artifacts.
set -euo pipefail

script="$(cd "$(dirname "$0")" && pwd)/collect-native-programs.sh"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
platforms=(linux-x64 linux-arm64 macos-arm64)
fail() { echo "FAIL: $1"; exit 1; }

for platform in "${platforms[@]}"; do
    mkdir -p "$work/artifacts/rossumworks-$platform"
    echo "program $platform" > "$work/artifacts/rossumworks-$platform/rossumworks"
done

bash "$script" "$work/artifacts" "$work/release" 0.6.0 || fail "the collection fails"
for platform in "${platforms[@]}"; do
    release_file="$work/release/rossumworks-0.6.0-$platform"
    [ -x "$release_file" ] || fail "rossumworks-0.6.0-$platform is missing or not executable"
    [ "$(cat "$release_file")" = "program $platform" ] || fail "rossumworks-0.6.0-$platform has other content"
done

rm "$work/artifacts/rossumworks-macos-arm64/rossumworks"
if output=$(bash "$script" "$work/artifacts" "$work/incomplete" 0.6.0 2>&1); then
    fail "a missing program does not stop the collection"
fi
[[ "$output" == *"The native program of macos-arm64 is missing."* ]] || fail "the error does not name the platform"
[ ! -e "$work/incomplete" ] || fail "a missing program leaves a target folder"

echo "PASS: collect-native-programs.sh"
```

- [ ] **Step 2: Run the test script and make sure that it fails**

Run: `bash .github/scripts/test-collect-native-programs.sh`
Expected: FAIL with `the collection fails`, because the script does not exist.

- [ ] **Step 3: Write the script**

`.github/scripts/collect-native-programs.sh`:

```bash
#!/usr/bin/env bash
# Copies the native programs of the downloaded artifacts to one folder, with their release file names.
# Usage: collect-native-programs.sh <artifact folder> <target folder> <version>
# Each artifact folder rossumworks-<platform> has the program rossumworks.
set -euo pipefail

artifact_folder="$1"
target_folder="$2"
version="$3"
platforms=(linux-x64 linux-arm64 macos-arm64)

for platform in "${platforms[@]}"; do
    if [ ! -f "$artifact_folder/rossumworks-$platform/rossumworks" ]; then
        echo "::error::The native program of $platform is missing."
        exit 1
    fi
done

mkdir -p "$target_folder"
for platform in "${platforms[@]}"; do
    release_file="$target_folder/rossumworks-$version-$platform"
    cp "$artifact_folder/rossumworks-$platform/rossumworks" "$release_file"
    # upload-artifact does not keep the executable bit.
    chmod +x "$release_file"
done
```

Run: `chmod +x .github/scripts/collect-native-programs.sh .github/scripts/test-collect-native-programs.sh`

- [ ] **Step 4: Run the test script and make sure that it passes**

Run: `bash .github/scripts/test-collect-native-programs.sh`
Expected: `PASS: collect-native-programs.sh`

- [ ] **Step 5: Run the test script in `build.yml`**

At the end of the job `workflows`, after the step "Run actionlint", add:

```yaml
      - name: Test the script of the native programs
        run: bash .github/scripts/test-collect-native-programs.sh
```

- [ ] **Step 6: Use the script in `release.yml`**

Replace the steps "Get the native programs" and "Prepare the release files" with:

```yaml
      # The job native keeps the artifacts rossumworks-linux-x64, rossumworks-linux-arm64, and rossumworks-macos-arm64.
      - name: Get the native programs
        uses: actions/download-artifact@3e5f45b2cfb9172054b4087a40e8e0b5a5461e7c # v8.0.1
        with:
          pattern: rossumworks-*
          path: ${{ runner.temp }}/native-artifacts

      # run.sh finds the programs by these names, and checks each program with its .sha256 file.
      - name: Prepare the release files
        env:
          VERSION: ${{ steps.version.outputs.version }}
        run: |
          bash .github/scripts/collect-native-programs.sh "$RUNNER_TEMP/native-artifacts" target/release "$VERSION"
          cp "target/rossumworks-$VERSION.jar" target/release/
          cd target/release
          for file in *; do
            sha256sum "$file" > "$file.sha256"
          done
          ls -l
```

The pattern `rossumworks-*` does not match the artifacts `native-test-reports-*`.

- [ ] **Step 7: Check the workflow files**

Run: `"$(go env GOPATH 2>/dev/null)/bin/actionlint" .github/workflows/*.yml || echo "Install actionlint 1.7.12 and run it again."`
Expected: no output from actionlint. If actionlint is not on the machine, the job `workflows` of `build.yml` checks the files after the push.

- [ ] **Step 8: Commit**

```bash
git add .github/scripts/collect-native-programs.sh .github/scripts/test-collect-native-programs.sh .github/workflows/build.yml .github/workflows/release.yml
git commit -m "Move the names of the native programs to a tested script"
```

---

### Task 6: `native.yml` builds and tests on macOS

**Files:**
- Modify: `.github/workflows/native.yml` (the matrix, and a new step)

- [ ] **Step 1: Add the matrix row**

After the row `linux-arm64`, add:

```yaml
          - platform: macos-arm64
            runner: macos-latest
```

- [ ] **Step 2: Add the step that shows the minimum macOS version**

After the step "Build the native program and run the native tests", add:

```yaml
      # The documents give this minimum macOS version. See the table of programs in docs/getting-started.md.
      - name: Show the minimum macOS version of the native program
        if: runner.os == 'macOS'
        run: vtool -show-build target/rossumworks
```

- [ ] **Step 3: Commit**

```bash
git add .github/workflows/native.yml
git commit -m "Build and test the native program on macOS arm64"
```

- [ ] **Step 4: Push the branch and run `native.yml` on it**

Ask the owner before the push. Then run:

```bash
git push -u origin feature/macos-native
gh workflow run native.yml --ref feature/macos-native
gh run watch "$(gh run list --workflow native.yml --branch feature/macos-native --limit 1 --json databaseId --jq '.[0].databaseId')"
```

Expected: the three jobs pass. If the job `macos-arm64` fails, use superpowers:systematic-debugging, and stop the plan until the cause is known.

- [ ] **Step 5: Read the minimum macOS version**

Run: `gh run view <run id> --log --job <id of the job macos-arm64> | grep -A3 minos`
Expected: a line `minos <version>`, for example `minos 11.0`. Write this value down as `MINIMUM_MACOS`. Task 7 uses it.

---

### Task 7: The documents

**Files:**
- Modify: `README.md:70-72`, `README.md:91-93`
- Modify: `docs/getting-started.md:41-56`, `:140-144`, `:155-156`
- Modify: `docs/tutorial.md:77-80`
- Modify: `CONTRIBUTING.md:44-45`, `:70-78`

In each text below, replace `MINIMUM_MACOS` with the value of Task 6, Step 5.

- [ ] **Step 1: `README.md`, the requirements**

Replace the lines 70 to 72 (from "For the jar only" to "so a Mac uses the jar.") with:

```markdown
- For the jar only: a JDK 21 or later. The native programs need no JDK. The native programs for Linux x64 and
  Linux arm64 need glibc 2.34 or later, for example Ubuntu 22.04, Debian 12, or RHEL 9. The native program for
  macOS arm64 needs macOS MINIMUM_MACOS or later. A Mac with an Intel processor uses the jar.
```

- [ ] **Step 2: `README.md`, the quick start**

Replace step 3 with:

```markdown
3. Download the native program of your machine from the
   [GitHub releases](https://github.com/MeterianHQ/rossumworks/releases), for example
   `rossumworks-$VERSION-linux-x64` or `rossumworks-$VERSION-macos-arm64`, and make it executable. On a Mac,
   also remove the quarantine flag. See [The programs of a release](docs/getting-started.md#the-programs-of-a-release).
   On another machine, download `rossumworks-$VERSION.jar`.
```

- [ ] **Step 3: `docs/getting-started.md`, the table and the steps**

In the table "The programs of a release", after the row `linux-arm64`, add:

```markdown
| `rossumworks-$VERSION-macos-arm64` | macOS on arm64 (Apple silicon), version MINIMUM_MACOS or later | No |
```

Replace the four steps after "To use a native program, do these steps:" with:

```markdown
1. Download the program and its `.sha256` file into the same folder.
2. Run `sha256sum -c rossumworks-$VERSION-linux-x64.sha256`. On a Mac, run
   `shasum -a 256 -c rossumworks-$VERSION-macos-arm64.sha256`.
3. Run `chmod +x rossumworks-$VERSION-linux-x64`.
4. On a Mac, run `xattr -d com.apple.quarantine rossumworks-$VERSION-macos-arm64`. See the note below.
5. Run `./rossumworks-$VERSION-linux-x64 self-check`.

**The quarantine flag on macOS.** A browser puts the quarantine flag on each file that it downloads. The program
has no Apple signature, so macOS blocks a program with this flag. The command of step 4 removes the flag. A
file that you download with `curl` has no flag, and then `xattr` shows an error that you can ignore. `run.sh`
downloads with `curl`, so it needs no extra step.
```

- [ ] **Step 4: `docs/getting-started.md`, the section of `run.sh`**

Replace the start of item 2, "On Linux x64 and on Linux arm64, it downloads", with:

```markdown
2. On Linux x64, on Linux arm64, and on macOS arm64, it downloads
```

Replace item 3 with:

```markdown
3. It starts the native program once with `--version`. On a Linux with a glibc older than 2.34, or on a macOS
   older than MINIMUM_MACOS, the native program does not start, and the script shows a warning.
```

Replace "On Linux, it also needs `sha256sum`." with:

```markdown
It also needs `sha256sum`, or `shasum` on an older macOS.
```

- [ ] **Step 5: `docs/tutorial.md`**

Replace step 1 with:

```markdown
1. Download the native program of your machine from the
   [releases](https://github.com/MeterianHQ/rossumworks/releases), for example
   `rossumworks-<version>-linux-x64` or `rossumworks-<version>-macos-arm64`, and make it executable. On a Mac,
   also remove the quarantine flag. See [The programs of a release](getting-started.md#the-programs-of-a-release).
   On another machine, download `rossumworks-<version>.jar`.
```

- [ ] **Step 6: `CONTRIBUTING.md`**

Replace the paragraph about the workflow Native program (lines 44 and 45) with:

```markdown
The workflow [Native program](.github/workflows/native.yml) builds and tests the native program on Linux x64,
Linux arm64, and macOS arm64. You can start it by hand on a branch. GitHub counts each macOS minute 10 times,
so the workflow runs only by hand and at a release.
```

In the section Release, replace the first bullet with:

```markdown
- It builds and tests the native program on Linux x64, Linux arm64, and macOS arm64 with the workflow
  `native.yml`. A failure creates no release.
```

Replace the bullet "It publishes" with:

```markdown
- It publishes `rossumworks-<version>.jar`, `rossumworks-<version>-linux-x64`,
  `rossumworks-<version>-linux-arm64`, and `rossumworks-<version>-macos-arm64`, and a `.sha256` file for each
  file.
```

- [ ] **Step 7: Search for old text**

Run: `grep -rn "macOS has no native program\|Linux x64 and Linux arm64\|Linux x64 and on Linux arm64" README.md CONTRIBUTING.md docs --include=*.md | grep -v superpowers`
Expected: no output.

Run: `grep -rn "MINIMUM_MACOS" README.md CONTRIBUTING.md docs --include=*.md | grep -v superpowers`
Expected: no output.

- [ ] **Step 8: Commit**

```bash
git add README.md docs/getting-started.md docs/tutorial.md CONTRIBUTING.md
git commit -m "Tell how to use the native program on a Mac"
```

---

### Task 8: The checks before the pull request

- [ ] **Step 1: Run the full build**

Run: `./mvnw -B -ntp verify`
Expected: `BUILD SUCCESS`, 0 failures.

- [ ] **Step 2: Run the test script again**

Run: `bash .github/scripts/test-collect-native-programs.sh`
Expected: `PASS: collect-native-programs.sh`

- [ ] **Step 3: Ask for a check on a real Mac**

Ask the owner to download the artifact `rossumworks-macos-arm64` of the run of Task 6, and to run these
commands on a Mac:

```bash
chmod +x rossumworks
xattr -d com.apple.quarantine rossumworks
./rossumworks self-check
```

Expected: `The self-check passed.`

- [ ] **Step 4: Open the pull request**

Ask the owner before you open it. The pull request description lists the checks of this task, and ends with the
attribution line of the session.
