# Contributing to RossumWorks

This page is for the people who change the code or the documents of the factory. To use the factory on your
repository, read the [README](README.md).

## Build and test

You need a JDK 21 or later. Maven comes with the Maven wrapper `mvnw`, so you do not install it.

Run the build and all the tests:

```
./mvnw verify
```

The build writes the jar to `target/rossumworks-$VERSION.jar`. Some tests show a warning about a failed
`git push` to `missing.git`. Ignore this warning: these tests check what the worker does when a push fails.

The tests never use a real `claude`, a real GitHub repository, or the Anthropic API. They use fake programs
and local git repositories.

The tests of `page.js` need Node, and `PageScriptTest` runs them in `./mvnw verify`. Node is optional on a
local machine: a build without Node skips these tests. When the variable `CI` exists, Node is necessary. Thus
the workflow Build always runs these tests. To run the tests of `page.js` alone, use this command:

```
node --test src/test/javascript/page.test.js
```

The workflow [Build](.github/workflows/build.yml) runs the same build for each pull request and for each push
to `main`. It also checks the workflow files with `actionlint`.

GitHub does not start Build for a pull request of Autofix. Close and reopen the pull request to start Build.

## The native program

The Maven profile `native` builds the [native program](docs/glossary.md) `target/rossumworks` with GraalVM.
You need GraalVM CE 21 or later, for example `sdk install java 21.0.2-graalce`. Set `JAVA_HOME` to GraalVM,
and run:

```
./mvnw -Pnative verify
```

The profile also runs the native tests `*IT`. They start `target/rossumworks`, and run its command
`self-check`. `./mvnw verify` without the profile needs no GraalVM, and runs no native test.

A native program contains only the resources and the reflection that its configuration names. The
configuration is in `src/main/resources/META-INF/native-image/com.meterian/rossumworks`. When you add a
resource folder, add it to `resource-config.json`. When Jackson binds a new class, add the class to
`reflect-config.json`, and add a step to the self-check. A missing entry makes a step of `self-check` fail.

The workflow [Native program](.github/workflows/native.yml) builds and tests the native program on Linux x64,
Linux arm64, and macOS arm64. You can start it by hand on a branch. GitHub counts each macOS minute 10 times,
so the workflow runs only by hand and at a release.

## Release

The workflow [Release](.github/workflows/release.yml) builds and publishes a release from a tag. `main`
always has a `-SNAPSHOT` version. The tag points to a release commit outside `main`, and this commit changes
only the version.

To make a release, do these steps:

1. Run `git fetch origin`.
2. Run `git checkout --detach origin/main`.
3. Run `./mvnw versions:set -DnewVersion=<version> -DgenerateBackupPoms=false`.
4. Make sure that `pom.xml` shows the new version. No other line changes.
5. Run `./mvnw verify`.
6. Commit `pom.xml` with the message `Release <version>`.
7. Run the workflow [Native program](.github/workflows/native.yml) by hand on `main`, and make sure that it
   passes. The release runs it again, and a failure stops the release.
8. Run `git tag -a v<version> -m "RossumWorks <version>"`.
9. Run `git push origin v<version>`. Do not push the commit to `main`.
10. Run `git checkout main`.
11. Make sure that the Release workflow passes.
12. Edit the release notes on GitHub. Write the changes, the install, and the upgrade steps.

The workflow does these steps:

- It builds and tests the native program on Linux x64, Linux arm64, and macOS arm64 with the workflow
  `native.yml`. A failure creates no release.
- It runs `./mvnw verify`. A failed build creates no release.
- It reads the version from `factory-version.properties`. If the tag is not `v` + this version, the workflow
  fails and creates no release. A `-SNAPSHOT` version also fails the workflow.
- It publishes `rossumworks-<version>.jar`, `rossumworks-<version>-linux-x64`,
  `rossumworks-<version>-linux-arm64`, and `rossumworks-<version>-macos-arm64`, and a `.sha256` file for each
  file.
- A version with a suffix, for example `0.6.0-rc.1`, gives a pre-release. A version without a suffix gives a
  normal release. `run.sh` uses only the latest normal release.
- GitHub writes the first release notes from the pull requests that merged after the previous tag.

If the run fails, correct the cause and run the workflow again. Use "Re-run failed jobs": it keeps the native
programs of the first run, because a native build is not byte-identical. The native programs stay for 7 days.
After 7 days, release a new version. A rerun deletes a draft release and makes the release again. It keeps a
release that is published.

The first release with native programs needs an upgrade text in its notes. A `run.sh` of version 0.5.0 or
earlier uses only the jar. Tell the users to replace `.factory/run.sh` with the new script, because `create`
does not replace it.

## The rules of the code

The file [CLAUDE.md](CLAUDE.md) holds the rules of the project. They apply to people and to Claude. The rules
of the code are:

- The package root is `com.meterian.factory`. Do not add `module-info.java` files.
- Each class and each method has one responsibility.
- Each name tells what the thing is or does, without short forms: `directory`, not `dir`.
- A comment has one line, and only when the code is complex.

## The rules of the documents

- Write in Simplified Technical English (ASD-STE100): short sentences, the active voice, and the simple
  tenses.
- Use the terms of the [Glossary](docs/glossary.md). When a new term is necessary, add it to the glossary
  first.
- Write commit messages in the same style.

## The designs

The folder `docs/superpowers` holds the design of each feature:

- `docs/superpowers/specs` holds the designs, with the reasons for each decision.
- `docs/superpowers/plans` holds the implementation plans.

Each file describes a feature at the date of the file. Do not change a file after the work ends. A new feature
gets a new design file. Read the design of a feature before you change it.

## The security of the dependencies

| Automation | When | What it does |
|---|---|---|
| [Security](.github/workflows/security.yml) | After each push to `main`, and each Monday | Scans the dependencies with Meterian. The run fails when a score is below its threshold. |
| [Autofix](.github/workflows/autofix.yml) | Each day | Opens a pull request with the Meterian dependency fixes. |
| [Dependabot](.github/dependabot.yml) | Each week | Updates the GitHub actions and the Maven build plugins. Meterian owns the other dependencies. |

The two Meterian workflows need the Actions secret `METERIAN_API_TOKEN`. Autofix also needs the repository
setting "Allow GitHub Actions to create and approve pull requests".

## License

RossumWorks uses the MIT License. See [LICENSE](LICENSE).
