# Tutorial

In this tutorial, you give a small project to RossumWorks. The factory fixes a bug, reviews the fix, and merges it.
[Getting started](getting-started.md) tells how you use the factory on your own project. The
[Glossary](glossary.md) gives the meaning of the terms.

## Before you start

**Warning: the agents run commands on your machine, with the rights of your user.**

**Warning: each issue uses your subscription, or costs money with an API key.** An issue needs the session of the
agent, four reviews, and a risk check. A review that rejects the change causes more sessions.

**Warning: the Anthropic documents do not agree about the use of a subscription from a script.** Read
[Legal considerations](legal-considerations.md) before you select a provider.

You need:

- Linux.
- A JDK 21 or later.
- The `claude` CLI.
- A provider. The provider gives the `claude` sessions their access to Claude, and pays for them. See
  [Providers](providers.md). The default is your Claude subscription, with a `claude` login.
- The `gh` CLI. Run `gh auth login` to log in.
- `git`, `setsid`, `kill`, `tmux`, `jq`, and `curl` on the `PATH`.

For the subscription, do not set `ANTHROPIC_API_KEY`. For an API key, set `ANTHROPIC_API_KEY` to the key.

## Make your repository

1. Open the [template repository](https://github.com/MeterianHQ/rossumworks-tutorial).
2. Select "Use this template", and then "Create a new repository".
3. Select the owner, type a name, and select "Private". GitHub can select an organization as the owner. Your account
   must be able to write to the repository and to delete it.
4. Select "Create repository".
5. Clone your repository, and go to the folder of the clone. Replace `<owner>` and `<name>`:

```
gh repo clone <owner>/<name>
cd <name>
```

6. Run the build. It must pass:

```
./mvnw verify
```

7. Run the program:

```
m=com.meterian.checkout.CheckoutCommand
java -cp target/classes $m book:19.95:1 --discount=10
```

The program shows `Total: 17.96`. The correct total is 17.95. This is the bug.

## Set up the factory

The factory reads its workflow from the folder `.factory` of your repository. The command `create` makes this folder
in a pull request (PR).

1. Download `rossumworks-<version>.jar` from the
   [releases](https://github.com/MeterianHQ/rossumworks/releases).
2. Run `java -jar <path of the jar> create` in the clone.
3. Push Enter at each question to take the default answer, but not at the confirmation. For an API key, type `anthropic-api-key` at the question
   about the provider, and then type a daily budget.
4. Read the summary. Type `y` to confirm: Enter is not an answer.
5. Open the PR `factory/setup` on GitHub, and merge it.
6. Run `git pull` in the clone.
7. Run `.factory/run.sh`. The worker is the program that does the work of the factory. Do not close this terminal.
8. Open <http://127.0.0.1:8090/>. This page is the board. It shows one card for each issue of the factory.

An empty password gives a page without a login. Each program on your machine can then use the page.

## Give an issue to the factory

1. Open a second terminal, and go to the folder of the clone.
2. Run `tutorial/create-issues.sh first-fix`.
3. Read the repository and the title, and answer `y`.
4. Open the issue on GitHub, and read it.
5. Add the label `factory` to the issue.

The worker polls GitHub each 60 seconds, so the card comes after about one minute. A button on the board is faster: the
worker sees it in about 2 seconds.

The worker reads `.factory` of the newest commit at each poll. A change of `.factory` needs no restart, and it applies
to new issues. A change of `worker.yaml` needs a restart.

## Watch the board

| Column | What the factory does |
|---|---|
| Queued | The issue waits for a free slot. |
| In progress | The agent changes the code. Then the worker runs the build. |
| In review | Four reviews check the change. Then the risk check gives the risk of the change. |
| Waits for you | The factory needs your decision. |
| Blocked | The factory cannot continue. |
| Done | The change is in the default branch. |

A card can go back from "In review" to "In progress". Then a review found a problem, and the agent corrects it.

The risk decides the merge:

- The risk is low and the change is small: the factory merges the PR.
- Else, the card goes to "Waits for you". Read the PR, and then push "Approve" on the card. Do not merge the PR on
  GitHub.

When the card is in "Done", do these steps:

1. Run `git pull` in the clone.
2. Run `./mvnw verify`.
3. Run the program again. It shows `Total: 17.95`.
4. Open the PR on GitHub. Read the description and the reviews.

## Time and use

A person did this tutorial on 2026-11-25, with RossumWorks 0.5.0 and the Claude plan Ultra:

| Value | Result |
|---|---|
| From the label to "Done" | 3 minutes 51 seconds |
| The wait for the poll, a part of this time | 67 seconds |
| The weekly usage on the board before the issue | 26% |
| The weekly usage on the board after the issue | 26% |

The board shows whole percents, so the use of one issue can be too small to see. Each run is different. The plugins of
your Claude Code also apply to the agents, and can change the time and the use.

## Problems

| Problem | Solution |
|---|---|
| The message tells you to remove `ANTHROPIC_API_KEY`. | To use the subscription, run `unset ANTHROPIC_API_KEY`. Then run the command again. |
| The message tells you to set `ANTHROPIC_API_KEY`. | Set the variable to your key. Then run the command again. |
| The message tells you that the factory needs commands on the `PATH`. | Install the commands that the message names. |
| "Done" is empty after a restart of the worker. | This is normal. The board removes these cards at a restart. The issues and the PRs stay on GitHub. |
| A terminal opens. | You pushed "Terminal", or the agent asks a question. Type `/factory:continue` to end the conversation. Do not close the window first. |
| No card comes on the board. | Make sure that you merged the PR `factory/setup`. Make sure that the issue has no assignee. Read the top of the board and the worker log. |
| The worker log has a warning about the assignee. | Use an account that can write to the repository. |
| The work waits for the usage window or for the daily budget. | Do not stop the worker. It continues when the limit permits it. See [Providers](providers.md). |
| The card is in "Blocked". | Read the note on the card. Push "Continue" to keep the work, or "Start again" to discard it. |

## Clean up

**Warning: a delete is final. Check the name before each delete.**

1. Push Ctrl-C in the terminal of the worker.
2. Delete your repository in its settings on GitHub.
3. Delete the folder of the clone.
4. Delete the work folder `~/.rossumworks/<owner>-<name>`. Use the owner and the name of your repository.
