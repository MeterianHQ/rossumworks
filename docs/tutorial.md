# Tutorial

In this tutorial, you give a small project to RossumWorks. The factory fixes a bug, reviews the fix, and merges it.

## Before you start

**Warning: the agents run commands on your machine, with the rights of your user.**

**Warning: each issue uses your Claude subscription.** An issue needs a coding session, four reviews, and a risk
session. A review that rejects the change causes more sessions.

You need:

- Linux.
- A JDK 21 or later.
- The `claude` CLI, with a login of your Claude subscription.
- The `gh` CLI. Run `gh auth login` to log in.
- `git`, `setsid`, `kill`, `tmux`, `jq`, and `curl` on the `PATH`.

Do not set `ANTHROPIC_API_KEY`. The factory does not start when it is set.

## Make your repository

1. Open the [template repository](https://github.com/MeterianHQ/rossumworks-tutorial).
2. Select "Use this template", and then "Create a new repository".
3. Select your account, type a name, and select "Private".
4. Select "Create repository".
5. Clone your repository with `gh repo clone <owner>/<name>`.
6. Go to the folder of the clone.
7. Run `./mvnw verify`. The build must pass.
8. Run this command to see the bug:

   ```
   java -cp target/classes com.meterian.checkout.CheckoutCommand book:19.95:1 --discount=10
   ```

The program shows `Total: 17.96`. The correct total is 17.95.

## Set up the factory

The factory reads its workflow from the folder `.factory` of your repository. The command `create` makes this folder
in a pull request (PR).

1. Download `rossumworks-<version>.jar` from the
   [releases](https://github.com/MeterianHQ/rossumworks/releases).
2. Run `java -jar <path of the jar> create` in the clone.
3. Push Enter at each question to take the default answer.
4. Read the summary, and answer `y`.
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

The worker checks GitHub each 60 seconds. Then the card comes on the board.

## Watch the board

| Column | What the factory does |
|---|---|
| Queued | The issue waits for a free slot. |
| In progress | An agent changes the code. Then the factory runs the build. |
| In review | Four agents review the change. Then an agent assesses the risk. |
| Waits for you | The factory needs your decision. |
| Blocked | The factory cannot continue. |
| Done | The change is in the default branch. |

A card can go back from "In review" to "In progress". Then a review found a problem, and the agent corrects it.

The risk decides the merge:

- The risk is low and the change is small: the factory merges the PR.
- Else, the card goes to "Waits for you". Read the PR, and then push "Approve" on the card.

When the card is in "Done", do these steps:

1. Run `git pull` in the clone.
2. Run `./mvnw verify`.
3. Run the `java -cp` command again. The program shows `Total: 17.95`.
4. Open the PR on GitHub. Read the description and the reviews.

## Problems

| Problem | Solution |
|---|---|
| The worker tells you to remove `ANTHROPIC_API_KEY`. | Run `unset ANTHROPIC_API_KEY`, and start the worker again. |
| The worker tells you that it needs commands on the `PATH`. | Install the commands that the message names. |
| No card comes on the board. | Make sure that you merged the PR `factory/setup`. Make sure that the issue has no assignee. Read the top of the board and the worker log. |
| The worker log has a warning about the assignee. | Use an account that can write to the repository. |
| The work waits for the usage limit. | Do not stop the worker. It continues when the limit permits it. |
| The card is in "Blocked". | Read the note on the card. Push "Continue" to keep the work, or "Start again" to discard it. |

## Clean up

**Warning: a delete is final. Check the name before each delete.**

1. Push Ctrl-C in the terminal of the worker.
2. Delete your repository in its settings on GitHub.
3. Delete the folder of the clone.
4. Delete the work folder `~/.rossumworks/<owner>-<name>`. Use the owner and the name of your repository.
