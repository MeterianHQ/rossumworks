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

The workflow [Build](.github/workflows/build.yml) runs the same build for each pull request and for each push
to `main`. It also checks the workflow files with `actionlint`.

GitHub does not start Build for a pull request of Autofix. Close and reopen the pull request to start Build.

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
