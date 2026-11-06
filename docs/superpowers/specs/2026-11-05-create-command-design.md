# The create command — design

Status: draft, for the approval of the owner.

This design is plan B of `docs/superpowers/specs/2026-11-02-workflow-definitions-design.md`. Section 6 of that
design is approved, and this design keeps it. This design adds only two things. The command writes `.factory`
in the state-machine format of `docs/superpowers/specs/2026-11-05-workflow-state-machine-design.md`. It also
gives the details that section 6 leaves open.

## 1. Intent

The owner does not want to set up the factory by hand. On `main`, the owner copies `factory.example.yaml`
and edits it. On `workflow-definitions`, the owner must also copy `src/main/resources/factory/` to `.factory`
and commit it. The command `claude-ai-factory create` does this work. The owner can change the files later.

## 2. Goals, non-goals, anti-goals

Goals:

- One command writes the worker configuration and opens the PR `factory/setup` with a default `.factory`.
- Each question has a default value. The owner can press Enter for each question.
- The command finds a value by itself where it can.

Non-goals:

- `create` does not start the worker. At the end it shows the `run` command, and the owner types it (owner
  decision of 2026-11-05).
- No migration of an old configuration file (section 9 of the design of 2026-11-02).
- No change to the workflow or to the engine. The only change to the worker is the reason on the page of
  section 5, step 5.

Anti-goals:

- A push to `main` or to a tag. The rule "the factory never pushes to `main`" stays true.
- A file that the command overwrites without a question.
- A test that reaches GitHub, a real token, the real `claude`, or the default tmux server.

## 3. The questions (section 6, approved)

`create` is a new first word of the command line: `java -jar claude-ai-factory.jar create [parameters]`. The
commands `run --config <file>` and `hash-password` stay as they are (my call).

`claude-ai-factory create` asks these questions in this order. A parameter on the command line gives the
answer and removes the question.

| Value | Default | Parameter (my call) |
|---|---|---|
| The starting repository | the GitHub repository of the current folder, from `git remote` | `--repository owner/name` |
| The work folder | `~/.claude-ai-factory/<owner>-<name>` | `--work-directory <path>` |
| The web page | `127.0.0.1:8090`; the command then asks for the password and writes only its hash | `--web <address:port>` or `--no-web` |
| The terminal command | `gnome-terminal --` when it is installed | `--terminal-command "<command>"` |

No question for:

- the GitHub account: from `FACTORY_GITHUB_TOKEN`, else from `gh auth token`.
- the `claude` command: from the `PATH`.

My calls:

- The password follows the rules of `hash-password`: 12 characters or more, asked two times, not shown on
  the screen.
- Without a terminal (no console), the command asks no question. It uses the parameters and the defaults.
  It then needs `--no-web`, or the password from the variable `FACTORY_WEB_PASSWORD`. Otherwise it stops
  with a message that names them.
- `--yes` gives the confirmation of step 3 of section 5.
- Without a console, `--yes` is necessary when the command must open the PR. Without it, the command stops
  before it writes a file, and the message names `--yes`.
- Without a console, a build command that the command cannot find needs `--build-command "<command>"`.
  Without it, the command stops with a message.
- When `gnome-terminal` is not installed, the file has no key `live.terminal-command`, and the command asks no
  question.

## 4. The worker configuration (my calls)

- The command writes the file `<work folder>/worker.yaml`.
- The gatekeeper is `<work folder>/gatekeeper.git`.
- `build.command` is only in `.factory/factory.yaml`. `worker.yaml` has only `build.environment`, the
  variables of this machine.
- The command adds `build.environment.JAVA_HOME` to `worker.yaml` when the variable `JAVA_HOME` is set and
  the repository has `mvnw`, `gradlew`, or `pom.xml`.
- The other keys stay at their defaults, and the file does not contain them. The file has a comment line for
  each optional section, as `factory.example.yaml` has.
- When the file exists, the command stops and names the file. It never overwrites the file.
- When the variable `ANTHROPIC_API_KEY` is set, the command stops with the message of `run`.
- Before it writes files, the command checks the programs that `run` needs on the `PATH`: `claude`, `git`,
  `setsid`, `kill`, `tmux`, and `jq`. A missing program stops the command. The message names it.

## 5. The `.factory` folder (section 6, approved, in the new format)

The order of the steps (my call):

1. The command makes all the checks of section 4 and asks all the questions of section 3.
2. It clones the repository into `<work folder>/repository`, the same clone that the worker uses. When the
   clone exists, it fetches. It reads the newest commit of the default branch.
3. It shows the summary and asks for the confirmation.
4. After a yes, it writes `worker.yaml`, and then it pushes and opens the PR.

After a "no" or a failure before step 4, only the clone stays on the disk. The command writes no
`worker.yaml`. A later `create` uses the same clone.

When the default branch has `.factory/factory.yaml`, the command does not write `.factory` and opens no PR.
It says that the repository already has a `.factory`. It still writes `worker.yaml` after the confirmation,
and it prints the `run` command.

When the default branch has a folder `.factory` without `factory.yaml`, the command stops. The message names
the folder. It never overwrites a file of the repository.

When the default branch has no `.factory`:

1. The command writes a default `.factory`: a copy of `src/main/resources/factory/`. This copy is the
   default workflow of the state machine, with its prompts and limits.
2. It fills in the values that it can find:
   - `merge.target-branch`: the default branch of the repository.
   - `build.command` and the matching `Bash(...)` entries of `claude.allowed-tools`:

     | The repository has | `build.command` (my call) |
     |---|---|
     | `mvnw` | `./mvnw verify` |
     | `gradlew` | `./gradlew build` |
     | `package.json` | `npm test` |
     | none of these | a question with no default |
3. It shows a summary of the files and asks for a confirmation. The default answer is "no".
4. After a yes, it commits `.factory` on the branch `factory/setup`, on top of the newest commit of the default
   branch. It pushes the branch through the gatekeeper. It opens a PR to the default branch and assigns the PR to the owner.
5. The worker starts after the merge of the PR. Until then, the worker runs but takes no issue, and the page
   shows the reason.

The worker already takes no new issue when no version of `.factory` loads. The builder must make sure that
a default branch without `.factory` gives a reason at the top of the page, for example "The default branch
has no .factory. Merge the PR factory/setup." Today the read of a missing file can count as a failure of git.
A failure of git shows no reason on the page. A test must prove the reason.

My calls:

- The owner of the PR is the login of the GitHub token.
- When a PR from `factory/setup` is already open, the command shows its link. It does not push again.
- When the branch `factory/setup` exists on GitHub without an open PR, the command stops with a message that
  names the branch. The message says that the command wrote no `worker.yaml`, and it gives the `gh api`
  command that deletes the branch on GitHub (owner decision of 2026-11-06).
- The gatekeeper forwards the branch to GitHub over the remote of the clone. After the push, the command
  checks that GitHub has the branch. If GitHub does not have it, the command stops with exit code 1 and the
  message of git, and it opens no PR.
- The PR description says what the files are, and it points to `docs/factory-definition.md`.

## 6. The end of the command

The command prints:

- the files that it wrote,
- the link of the PR, if it opened one,
- the `run` command with the path of `worker.yaml`.

The exit code is 0 on success and 1 on a failure. A failure before the push leaves no branch on GitHub, and
no `worker.yaml`.

## 7. Documentation

- The section "Setup" of `README.md` uses `create`. It no longer tells the owner to copy
  `factory.example.yaml` or `src/main/resources/factory/` by hand.
- The usage text of the jar names the command `create` and its parameters.

## 8. Proof

- Tests of each default and each parameter, with a fake console and a fake `GitHubClient`.
- Tests of the files: the written `worker.yaml` loads with `ConfigurationLoader`. The written `.factory` loads
  with the loader of the definitions, for each build command of the table.
- A test with a local bare repository as the remote: the branch `factory/setup` arrives through the
  gatekeeper, and no other branch changes.
- Tests of the stops: an existing `worker.yaml`, a `.factory` without `factory.yaml`, an open PR, an existing
  branch, a missing program, `ANTHROPIC_API_KEY`, no console without `--yes`, and a failed forward to GitHub.
- A test of the existing `.factory`: no PR, and `worker.yaml` is written.
- A test of the worker: a default branch without `.factory` gives the reason on the page and no new issue.

## 9. Left to the builder

- The names of the classes and the split of the code.
- The texts of the questions and of the messages, in Simplified Technical English.
- How the fake console reads the answers in the tests.
