# Getting started

This page tells how you install the factory on one of your GitHub repositories, and how you start the worker.
The [README](../README.md) has a short version of the same procedure.

## Before you start

You need the items in the "Requirements" section of the README. You also need these items:

- A clone of your repository on the machine that runs the worker.
- A GitHub account that can assign issues in your repository. The worker uses the token of this account.
  Without this permission, GitHub ignores the assignment, and the factory does not work on the issue. The
  worker then writes a warning in its log.

## Install the factory

1. Prepare the provider of the `claude` sessions:
   - For your Claude subscription, log in with `claude`. Do not set `ANTHROPIC_API_KEY`.
   - For an Anthropic API key, set the variable `ANTHROPIC_API_KEY` to the key.

   See [Providers](providers.md).
2. Give the worker a GitHub token. Log in with `gh auth login`, or set `FACTORY_GITHUB_TOKEN` to a token of
   the account of the worker.
3. Download `rossumworks-$VERSION.jar` from the
   [GitHub releases](https://github.com/MeterianHQ/rossumworks/releases). You can also build the jar from the
   source. See [Contributing](../CONTRIBUTING.md). In these documents, `$VERSION` is the version of the jar,
   for example `0.2.0`.
4. Go to the folder of the clone of your repository.
5. Run `java -jar <path of the jar> create`, for example
   `java -jar ~/Downloads/rossumworks-$VERSION.jar create`.
6. Answer the questions. When you push Enter, `create` uses the default answer.
7. Check the summary that `create` shows, and type `y` to confirm.
8. `create` opens the pull request `factory/setup` on your repository. Merge it.
9. In the same folder, pull the default branch, to get `.factory/run.sh`.
10. Run the command that `create` shows. Usually, it is `.factory/run.sh`.

The worker takes no issue until you merge the pull request `factory/setup`. Until then, the board shows the
reason.

## What `create` does

`create` does these steps in this order:

1. It checks that `claude`, `git`, `setsid`, `kill`, `tmux`, and `jq` are on the `PATH`.
2. It asks for the name of your GitHub repository and for the work folder. The **work folder** is the folder of the worker. The
   default is `~/.rossumworks/<owner>-<name>`.
3. It asks for the address of the board, the terminal command of the live mode, and the provider. Then it
   checks that `ANTHROPIC_API_KEY` agrees with the provider: set for an API key, and not set for the
   subscription.
4. It clones your repository into `<work folder>/repository`. When this clone exists, it fetches the default
   branch.
5. When your default branch has no `.factory/factory.yaml`, it asks for the build command. The default answer
   is the command that it finds in your repository, if it finds one. The build command is the first check of
   each change, so it must run your tests. See [Factory definition](factory-definition.md), section 12.1.
6. It shows a summary and asks for a confirmation. Only `y` or `n` is an answer. Enter or another answer asks
   again.
7. After `y`, it writes the configuration of the worker to `<work folder>/worker.yaml`. See
   [Configuration](configuration.md).
8. When your default branch has no `.factory/factory.yaml`, `create` adds the default definition to the branch
   `factory/setup`. It pushes the branch through the **gatekeeper**, a local git repository that protects your
   branches. Then it opens a pull request, and assigns it to the account of the token.

`create` never pushes to your default branch, and it never overwrites a file.

The default folder `.factory` is a copy of
[`src/main/resources/factory/`](../src/main/resources/factory/), with the build command and the target branch
of your repository. It holds `factory.yaml`, the prompt files of the default workflow, and the script `run.sh`.
`create` does not copy `prompts/resume.md`, because the default workflow does not use it. You can change the
files later. See [Factory definition](factory-definition.md).

When `JAVA_HOME` is set and your repository has `mvnw`, `gradlew`, or `pom.xml`, `create` adds
`build.environment.JAVA_HOME` to `worker.yaml`.

### Create with a definition folder

With `--definition`, `create` checks the definition folder before it clones the repository. It asks "Write the
files? [y/n]" before it writes. It writes the default definition to the definition folder, and it opens no pull
request. When the write of the folder fails, `create` deletes `worker.yaml` again.

### Special situations of `create`

| Situation | What `create` does |
|---|---|
| `worker.yaml` exists in the work folder | It stops with the message "The file ... exists" and the exit code 1. A second run after a successful run also stops here. |
| A pull request from `factory/setup` is open | It shows the link of the pull request, and writes no file. |
| The branch `factory/setup` exists on GitHub, without an open pull request | It stops, and writes no file. This occurs when the push succeeds and the creation of the pull request fails. The gatekeeper refuses to delete a branch, so the message gives a `gh api` command that deletes it on GitHub. Then run `create` again. |
| Your default branch has a folder `.factory` without `factory.yaml` | It stops. Correct the folder, and run `create` again. |
| Your default branch has `.factory/factory.yaml` | It writes only `worker.yaml`, and opens no pull request. It shows the `java -jar` command to start the worker, because the repository has no `run.sh` of `create`. |
| The assignment of the pull request fails | It shows the problem, keeps `worker.yaml`, and ends with success. Assign the pull request yourself. |
| With `--definition`, the folder has `factory.yaml` | It keeps the folder, and writes only `worker.yaml`. |
| With `--definition`, the folder has other files, but no `factory.yaml` | It stops, and writes no file. Hidden entries, for example `.git`, do not count. |
| With `--definition`, the path is a file | It stops, and writes no file. |

### The parameters of `create`

A parameter gives the answer to a question, so `create` does not ask it:

| Parameter | Value | Default |
|---|---|---|
| `--repository owner/name` | Your repository on GitHub. | The GitHub repository of `git remote get-url origin` in the current folder. |
| `--work-directory <path>` | The work folder. | `~/.rossumworks/<owner>-<name>` |
| `--web <address:port>` | The address of the board. `create` then asks for the password two times, and writes only its hash. Push Enter at the first question for no password. | `127.0.0.1:8090` |
| `--no-web` | No board. | |
| `--terminal-command "<command>"` | The terminal command of the live mode. See [Live mode](live-mode.md). | `gnome-terminal --` when it is installed. Else, no command. |
| `--build-command "<command>"` | The build command of `.factory`. | `./mvnw verify` with `mvnw`, `./gradlew build` with `gradlew`, `npm test` with `package.json`. Else, no default. |
| `--provider <name>` | `subscription` or `anthropic-api-key`. See [Providers](providers.md). | `subscription` |
| `--daily-budget-usd <amount>` | The daily budget in US dollars, from 0.01 to 1000000. Only for `anthropic-api-key`. | No default |
| `--definition <folder>` | The definition folder. `create` writes the default definition there, and opens no pull request. See [Factory definition](factory-definition.md), section 17. | No folder: `create` opens the pull request `factory/setup`. |
| `--yes` | The confirmation. | No |

Without a terminal, `create` asks no question. It uses the parameters and the defaults. It then needs these
parameters:

- `--no-web`, or the password of the board in the variable `FACTORY_WEB_PASSWORD`.
- `--yes`, to open the pull request.
- `--build-command`, when it cannot find the build command.
- `--daily-budget-usd`, with `--provider anthropic-api-key`.

## Start the worker

### The script `run.sh`

The default `.factory` holds the script `run.sh`. `create` commits it with the execute permission. The script starts the worker with the latest release of the factory:

1. It asks the GitHub API for the latest release of `MeterianHQ/rossumworks`. The repository is private, so
   the script sends the token of `FACTORY_GITHUB_TOKEN`, or else the token of `gh`.
2. It downloads the jar of that release into `~/.rossumworks/jars`. It does not download a jar that is
   already in this folder.
3. It goes to the root of the clone, and runs `java -jar <the jar> run`. It gives its own arguments to `run`,
   for example `.factory/run.sh --config <work folder>/worker.yaml`.

When the download fails, the script uses the newest jar in `~/.rossumworks/jars`. When that folder has no jar,
the script stops with the exit code 1. The script needs `bash`, `curl`, and `jq`.

`create` copies its own jar into `~/.rossumworks/jars`, with the same file name. Thus the first run of the
script needs no download when this jar is the latest release. `create` does not overwrite a jar in that folder.
When the copy fails, `create` shows the problem and ends with success.

### The configuration file

The command `run` of the jar starts the worker. Without `--config`, it finds the configuration from the clone. It reads the owner and the name of the GitHub
repository of the clone, for example `octocat/hello-world`. Then it reads
`~/.rossumworks/octocat-hello-world/worker.yaml`.

When this file does not exist, the worker does not start, and the message tells you to run `create`. For a work
folder that is not the default, give the file: `run --config <work folder>/worker.yaml`. For
such a work folder, `create` shows the command with `--config`.

With a definition folder, give it with `--definition`, for example
`java -jar <path of the jar> run --config <work folder>/worker.yaml --definition <folder>`. The order of the
options has no effect.

### When the worker starts

At start, the worker writes one line that names the provider. When the board is on, it also writes the address
of the board, for example `The web page is at http://127.0.0.1:8090/`.

The worker creates its labels in your repository at its first poll. See [Issues and labels](issues-and-labels.md).

## Stop the worker

Push Ctrl-C to stop the worker. The worker stops the running `claude` sessions. At the next start, each
interrupted action runs again from its start.

To stop the work but not the worker, use the button Stop of the board. See [Web page](web-page.md).

## The exit codes

| Code | Meaning |
|---|---|
| 0 | The worker stopped normally. |
| 1 | A check before the start failed. The message tells you what to change. |
| 2 | A `claude` session did not use the login or the API key of the provider that you selected. See [Providers](providers.md). |
