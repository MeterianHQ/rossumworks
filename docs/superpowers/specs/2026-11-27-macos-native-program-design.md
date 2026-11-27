# The native program for macOS — design

Status: approved by the owner on 2026-11-27.

PR #77 makes the factory work on macOS, but a Mac uses the jar. This design adds a native program for macOS
on arm64 to each release. The design follows the native program of heidi-fix.

## 1. Intent

A Mac user must not need a JDK. The native program for Linux needs no JDK, and the native program for macOS
gives the same result to a Mac user. `run.sh` downloads the program of the machine, so a Mac with `run.sh`
gets the native program without a manual step.

## 2. Goals, non-goals

Goals:

- Each release publishes `rossumworks-<version>-macos-arm64` and its `.sha256` file.
- The workflow `native.yml` builds and tests the program on a macOS arm64 runner. A failure stops the release.
- `run.sh` uses the program on a Mac with arm64. A Mac with an Intel processor uses the jar.
- The documents tell how to download, check, and start the program on a Mac.

Non-goals:

- A native program for macOS on x64. GitHub plans to remove the Intel images of macOS in 2027, and nobody
  tested the factory on an Intel Mac.
- The Apple signature and the notarization. After the merge, Claude proposes a follow-up issue, and asks the
  owner first.

## 3. The platform name

The new platform is `macos-arm64`, as in heidi-fix. The release file is `rossumworks-<version>-macos-arm64`.

| Platform | Runner | `uname -s` and `uname -m` | Release file |
|---|---|---|---|
| `linux-x64` | `ubuntu-latest` | `Linux`, `x86_64` | `rossumworks-<version>-linux-x64` |
| `linux-arm64` | `ubuntu-24.04-arm` | `Linux`, `aarch64` or `arm64` | `rossumworks-<version>-linux-arm64` |
| `macos-arm64` | `macos-latest` | `Darwin`, `arm64` | `rossumworks-<version>-macos-arm64` |

## 4. The workflow `native.yml`

The matrix gets a third row: `macos-arm64` on `macos-latest`. The steps do not change. The step
"Build the native program and run the native tests" runs `./mvnw -B -ntp -Pnative verify`, and
`NativeProgramIT` tests the program. The artifact is `rossumworks-macos-arm64`.

GitHub counts each macOS minute 10 times. `native.yml` runs only by hand and at a release, so the cost stays
small.

## 5. The script `collect-native-programs.sh`

The release step "Prepare the release files" has a loop in the workflow file. The loop moves to the new
script `.github/scripts/collect-native-programs.sh`, as in heidi-fix.

Usage: `collect-native-programs.sh <artifact folder> <target folder> <version>`.

The script does these steps:

1. It checks that each artifact folder `rossumworks-<platform>` has the file `rossumworks`. A missing file
   stops the script with an error that names the platform. The script then copies nothing.
2. It copies each program to `<target folder>/rossumworks-<version>-<platform>`.
3. It makes each program executable, because `upload-artifact` does not keep the executable bit.

The workflow step copies the jar and writes the `.sha256` files for all the files, as before.

The test script `.github/scripts/test-collect-native-programs.sh` runs the script on a temporary folder. It
checks the names, the content, and the executable bit of the three programs. It also checks that a missing
program stops the script. `build.yml` runs the test script in the job "Check the workflow files".

## 6. The script `run.sh`

1. `find_platform` gives `macos-arm64` for `Darwin` and `arm64`. For `Darwin` and another processor, it gives
   nothing, so the script uses the jar.
2. `verify_checksum` uses `sha256sum` when the command exists. Else it uses `shasum -a 256`. Recent macOS
   versions have `sha256sum` in `/sbin`, but older versions have only `shasum`.
3. The comment of `verify_native_program_starts` changes to: "A native program does not start on an older
   system: Linux before glibc 2.34, or an older macOS. Then the script uses the jar." The code does not change.

The plan finds the minimum macOS version of the program from the `macos-latest` runner. The documents give
this version.

## 6a. The class `RunningProgram`

`create` copies its own native program into `~/.rossumworks/native`, but only when the file name matches the
pattern of `RunningProgram.Kind.NATIVE`. The pattern is `rossumworks-.+-linux-(x64|arm64)` now. It changes to
`rossumworks-.+-(linux-x64|linux-arm64|macos-arm64)`, so a Mac also gets the copy. `RunningProgramTest` checks
the new name.

## 7. The tests of `run.sh`

`RunScriptTest` uses a fake `uname`. These tests change or come in:

- `anotherSystemThanLinuxGivesTheJar` changes to `aMacWithIntelGivesTheJar`: `Darwin` and `x86_64` give the jar.
- The new test `aMacWithArm64GivesTheMacProgram`: `Darwin` and `arm64` give the native program
  `rossumworks-<version>-macos-arm64`.
- The new test `aMachineWithoutSha256sumUsesShasum`: without `sha256sum` on the `PATH`, the script checks the
  program with a fake `shasum`.

`aMachineWithoutATokenDownloadsTheJar` uses `Darwin` and `arm64` and publishes no native program. It still
gives the jar, so it does not change.

## 8. The documents

- `README.md`: the requirements name the program for macOS arm64. The sentence "macOS has no native program,
  so a Mac uses the jar" changes: only a Mac with an Intel processor uses the jar. The quick start names the
  program of a Mac.
- `docs/getting-started.md`: the table "The programs of a release" gets the row `macos-arm64`. The steps
  give `shasum -a 256 -c` for a Mac. A note tells that a browser download gets the quarantine flag, and that
  `xattr -d com.apple.quarantine <program>` removes it. The program has no Apple signature.
- `docs/tutorial.md`: the download step names the program of a Mac.
- `CONTRIBUTING.md`: the section Release lists the three programs, and the three platforms of `native.yml`.
- `docs/glossary.md`: the term "quarantine flag" comes in before the other documents use it.

## 9. Error handling

- A failed macOS build or test stops the release, as for Linux.
- A missing macOS artifact stops `collect-native-programs.sh`, so the release has no missing program.
- On a Mac where the program does not start, `run.sh` uses the jar, as on an old Linux.
- A wrong SHA-256 stops `run.sh` with the code `CHECKSUM_FAILURE`, as before.

## 10. The checks before the merge

1. `./mvnw verify` passes on Linux.
2. The test script of `collect-native-programs.sh` passes.
3. A manual run of `native.yml` on the branch passes on the three platforms.
4. The owner, or a person with a Mac, starts the artifact `rossumworks-macos-arm64` with `self-check`.
