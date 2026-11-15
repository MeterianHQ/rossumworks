# claude-ai-factory
A simple AI factory using your Claude subscription, running on GitHub

## Build

Run `./mvnw verify`. The build needs a JDK 21 or later.

## Configuration

The command `create` writes the configuration file `<work folder>/worker.yaml` (see "Setup").
`factory.example.yaml` shows all the keys. The factory reads this file at start. The file holds only
the facts of this machine:

- `repository`: the repository on GitHub, as `owner/name`.
- `work-directory`: the folder of the worker. The worker clones the repository into `<work-directory>/repository`
  at the start, when this folder does not exist. A clone that exists stays as it is.
- `gatekeeper-directory` and `live-directory`: the folders of the gatekeeper and of the live files.
- `limits`: `parallel-issues`, `poll-interval`, and `usage-threshold`. An open live session counts against
  `parallel-issues`, as an issue in work. An issue that left the queue gets a slot before a queued issue, also
  after a restart of the worker.
- `build.environment`: the variables of this machine, for example `JAVA_HOME`.
- `isolation`, `claude`, `live`, and `web`.

The workflow, the build, the merge target, and the allowed tools are in the folder `.factory`
of the repository. A file with the old form (the keys `target`, `merge`, `accounts`, `prompts-directory`,
`limits.fix-loops`, or `claude.allowed-tools`) does not start. The message points to
the command `create` of the jar, which writes the new configuration.

## .factory

The folder `.factory` of the repository holds the definition of the factory: the workflow, the prompts, the
build, the merge target, and the allowed tools. The main file is `.factory/factory.yaml`.

The workflow is a state machine. An issue is always in one state. When the issue enters a state, the factory
starts the action of that state. The action ends with an event, and the state names the next state for each
event. A state without an action waits for you.

[docs/factory-definition.md](docs/factory-definition.md) is the reference of the YAML: each key, the states,
the action types and their events, the limits, the columns and the labels, the prompts, the behavior at run
time, and the rules of the loader. It also shows the default workflow and other example workflows.

The command `create` writes a default `.factory` and opens the PR `factory/setup` (see "Setup"). The default
`.factory` is a copy of [`src/main/resources/factory/`](src/main/resources/factory/), with the build command
and the merge target of the repository. You can change the files later.

How the worker reads `.factory`:

- At the start of each poll, the worker fetches the default branch in its clone. It reads `.factory` of the
  newest commit with git, never from the worktree of an issue.
- A new issue gets the newest version that loads. Its record keeps the commit of that version.
- Each issue runs with the workflow, the prompts, the build, the allowed tools, and the merge target of its
  own version. A new commit of `.factory` has no effect on the issues in work.
- A version that does not load does not replace the last good version. The page shows the reason at the top.
- Without a version that loads, the worker takes no new issue.
- A record without a commit, from before the definitions, runs with the default workflow of the jar. The
  worker reads its phase and its wait reason as a state of the default workflow.

In the configuration, `build.environment` gives the variables of this machine, for example `JAVA_HOME`. A
value of the machine replaces a value of `.factory` with the same name.

## Setup

The factory needs no GitHub Project. The worker creates its labels in the target repository: `factory`,
`factory:approve`, `factory:block`, `factory:enter`, and `factory:start-again`. It creates the label of each column,
`factory:is:<column>`, on its first use, for example `factory:is:in-review`. The label of a column is for display
only: the record in the status comment keeps the state. The worker removes the old labels `factory:status:<name>`.

1. Log in to the `claude` CLI with your Claude subscription. Do not set `ANTHROPIC_API_KEY`: the factory
   refuses to start when it is set.
2. Log in with `gh auth login`. Or set `FACTORY_GITHUB_TOKEN` to a GitHub token of the worker account.
3. Download `claude-ai-factory-<version>.jar` from the
   [GitHub releases](https://github.com/MeterianHQ/claude-ai-factory/releases). Or build the jar with
   `./mvnw verify`.
4. Go to a clone of the target repository. Run `java -jar <path of the jar> create`, for example
   `java -jar ~/claude-ai-factory/target/claude-ai-factory-0.2.0-SNAPSHOT.jar create`. Answer the questions.
   Enter takes the default answer.
5. Merge the PR `factory/setup`.
6. In the same clone, run the command that `create` shows, for example `.factory/run.sh`.

`run` without `--config` reads the owner and the name of the GitHub repository of the clone, for example
`MeterianHQ/heidi-fix`. It then reads `~/.claude-ai-factory/MeterianHQ-heidi-fix/worker.yaml`. When the file
does not exist, the worker does not start, and the message tells you to run `create`. For a work folder that is
not the default, give the file: `run --config <work folder>/worker.yaml`. `create` then shows this command.

`create` checks that `claude`, `git`, `setsid`, `kill`, `tmux`, and `jq` are on the `PATH`. It clones the
repository into `<work folder>/repository`, shows a summary, and asks for a confirmation. The default answer is
"no". After a yes, it writes `<work folder>/worker.yaml`. When the default branch has no `.factory`, it then
pushes the branch `factory/setup` through the gatekeeper. It opens a PR and assigns it to the login of the
token. When the assignment fails, `create` shows the problem, keeps `worker.yaml`, and ends with success.
Then assign the PR by hand. Until the merge of the PR, the worker takes no issue, and the page shows the reason.
`create` never pushes to the default branch, and it never overwrites a file.

The default `.factory` holds `factory.yaml` and the prompt files that it uses. `create` does not copy
`prompts/resume.md` of the jar, because the default workflow does not use it.

### The run script

The default `.factory` also holds the script `run.sh`. `create` commits it with the execute permission. The
script starts the worker with the latest release of the factory:

1. It asks the GitHub API for the latest release of `MeterianHQ/claude-ai-factory`. It sends the token of
   `FACTORY_GITHUB_TOKEN`, or else the token of `gh`, because the repository is private.
2. It downloads the jar of that release into `~/.claude-ai-factory/jars`. A jar in this folder is not
   downloaded again.
3. It goes to the root of the clone and runs `java -jar <the jar> run`. It gives its arguments to `run`, for
   example `.factory/run.sh --config <work folder>/worker.yaml`.

`create` copies its own jar into `~/.claude-ai-factory/jars`, with the same file name. Then the first run of the
script needs no download when that jar is the latest release. `create` does not overwrite a jar in the folder.
When the copy fails, `create` shows the problem and ends with success.

When the download fails, the script uses the newest jar in `~/.claude-ai-factory/jars`. When that folder has no
jar, the script stops with the exit code 1. The script needs `bash`, `curl`, and `jq`. When the repository
already has a `.factory`, `create` adds no script, and it shows the `java -jar` command.

When `worker.yaml` exists in the work folder, `create` stops with the message "The file ... exists" and the
exit code 1. A second run after a successful run also stops there, because the first run wrote `worker.yaml`.
When `worker.yaml` does not exist and a PR from `factory/setup` is open, `create` shows the PR link and writes
no file. When the branch `factory/setup` exists on GitHub without an open PR, `create` stops and writes no
file. This can occur when the push succeeds and the creation of the PR fails. The gatekeeper refuses a
delete, so the message gives a `gh api` command that deletes the branch on GitHub. Then run `create` again.
When the default branch has `.factory/factory.yaml`, `create` writes only `worker.yaml` and opens no
PR.

A parameter gives the answer and removes the question:

| Parameter | Value | Default |
|---|---|---|
| `--repository owner/name` | The starting repository on GitHub. | The GitHub repository of `git remote get-url origin` in the current folder. |
| `--work-directory <path>` | The work folder. | `~/.claude-ai-factory/<owner>-<name>` |
| `--web <address:port>` | The address of the web page. `create` then asks for the password two times, and writes only its hash. | `127.0.0.1:8090` |
| `--no-web` | No web page. | |
| `--terminal-command "<command>"` | The terminal command of the live mode. | `gnome-terminal --` when it is installed, else none. |
| `--build-command "<command>"` | The build command of `.factory`. | `./mvnw verify` with `mvnw`, `./gradlew build` with `gradlew`, `npm test` with `package.json`, else a question. |
| `--yes` | The confirmation. | No. |

`create` adds `build.environment.JAVA_HOME` to `worker.yaml` when `JAVA_HOME` is set and the repository has
`mvnw`, `gradlew`, or `pom.xml`.

Without a terminal, `create` asks no question. It uses the parameters and the defaults. It then needs
`--no-web`, or the password in the variable `FACTORY_WEB_PASSWORD`. It also needs `--yes` to open the PR, and
`--build-command` when it cannot find the build.

Add the label `factory` to an issue to give it to the factory. The factory does not check who added a request
label. It acts on each request label that it finds on an issue: `factory`, `factory:block`, `factory:enter`,
`factory:approve`, and `factory:start-again`. GitHub permissions control the labels: usually, only an account with
triage access or more can add a label. Neither `.factory` nor `worker.yaml` has a list of accounts.

Warning: two other paths add labels. They can give work to the factory from any account:

- An issue template or an issue form with `labels:` adds its labels for each author of an issue.
- A GitHub Actions workflow adds labels with its own token.

Do not put `factory` or a `factory:` label in an issue template, an issue form, or a workflow.

The account of the token must be able to assign issues in the target repository. Without this access, GitHub
ignores the assignee, and the factory does not work on the issue. The factory writes a warning in the log.

Note: the comments of the worker mention nobody. If the token belongs to the owner, GitHub sends the owner no
notification for these comments. With a separate worker account, the owner gets a notification only for the
issues that the owner watches. With a separate worker account, assign the issues to the worker account or to
nobody. An issue that is assigned to a human gets no work.

The worker exits with code 2 when a `claude` session uses an API key. It exits with code 1 when a check
before the start fails. Ctrl-C stops the running `claude` sessions. The interrupted actions run again from
their start at the next run.

### Retries

Two retries of a technical failure stay inside an action. Set them with a system property of Java:

| Retry | System property | Default number of tries |
|---|---|---|
| A review session fails, for example `claude` stops. The reviews run again. | `factory.retries.review-session` | `2` |
| After a push, GitHub does not show the commit yet. The next poll checks again. | `factory.retries.forward-check` | `3` |

For example: `java -Dfactory.retries.review-session=4 -jar target/claude-ai-factory-0.2.0-SNAPSHOT.jar run`.
The value is the number of tries, and the first try counts. So the value `2` gives one try and one retry.
After the last try, the issue goes to the `block` target of its workflow. A value that is not a whole number
of 1 or more stops the worker at the start, with exit code 1. The message names the property.

## More than one factory on one repository

Several people can run a factory on the same repository. Each person uses a token of their own account. The
assignee of an issue decides which factory owns the issue. The factory assigns the issue to the account of its
token.

### Pick up

- The factory picks up an issue that has the label `factory` and no assignee.
- The factory assigns the issue to itself. Then it reads the issue again.
- A stopped factory does not pick up a new issue. It does not assign itself, and it writes nothing on the issue.
  An issue with a record of this factory is not new: the factory assigns itself again.
- When the issue has more than one assignee, the earliest "assigned" event among the current assignees wins.
  The factory that loses removes its own assignee.
- When an assignee has no event, the factory gives way.
- An issue of another account gets no work and no comment. It gets no card on the board of this factory.

### Stop

The factory checks the owner at each poll and before each action. It also checks just before each push, each
change to the pull request, the merge, and the removal of an approval. A running agent or build is never
interrupted.

The gatekeeper forwards a push to GitHub only when the branch on GitHub is at the commit that the factory
expects: the commit that it forwarded last, or the commit that its continuation starts from. Thus a factory never
removes a commit of another factory. When GitHub refuses the forward and another account owns the issue, the
factory stops as for a new assignee. Otherwise the refused forward counts as a failed forward check.
When the branch is gone from GitHub, the factory makes the branch again: there is no commit to protect.

GitHub can refuse the forward for good: the branch on GitHub has commits that the factory does not know. For
example, another person pushes to the branch, or the gatekeeper folder moves. After three failed forward
checks, the factory blocks the issue and writes a note. Check the branch on GitHub. To let the factory replace
the branch, delete the branch on GitHub, and then add the label `factory:start-again`.

When another account becomes the owner, the factory starts no new action. It writes one comment: "The factory of
`<login>` stops work on this issue, because the issue has a new assignee. The branch and the pull request stay.
The factory of the new assignee continues the work." An open live session stays open. A merged issue (in the
state with the action `finish`) gets no stop.

### Continuation

- The new owner writes a new record in the `start` state of the current version of `.factory`.
- The new owner continues from the branch `factory/issue-<n>` on GitHub when the branch exists. The action
  `prepare` makes the worktree from `origin/factory/issue-<n>`. Then the issue enters the first state with the
  action `build`, in the order of the moves from the `start` state. The new owner starts a new agent session,
  builds again, reviews again, and uses the existing pull request. A workflow without a `build` state continues
  with the event `done` of `prepare`.
- When the branch does not exist, the new owner starts the issue from the beginning.
- The work before the first push stays on the first machine. The Claude session stays there too.
- The new owner keeps a block and an approval wait of the earlier record, in the matching state of its workflow.
  An earlier record in a state without an action, for example `blocked`, goes to the `block` target. An earlier
  record that waits for the approval of a new issue text goes to the `issue-text-changed` target.
- The new owner keeps the issue text that the earlier owner accepted. When the text changed, the issue waits for
  `factory:approve` before the next action. A record without the hash of the text counts as a change.
- A wait for the merge approval stays only when the pushed branch exists. The issue goes to the state where the
  same event of the `merge-gate` state goes, for example `waits-for-approval`. After the approval, the issue goes
  to the `start` state: the factory makes the worktree from the branch, builds, and reviews again. Then it asks
  for the approval a second time. Without the branch, the issue starts from the beginning.
- A wait in a state with the action `conversation`, for an answer to a question or for the end of a live
  conversation, does not pass on. The new owner continues the work and writes a note: the question stays on the
  machine of the earlier owner.
- A status comment of an account that was never an assignee of the issue never sets the state.
- A factory that gets an issue back continues as a new owner. This is also true when the factory was off
  while the issue had another owner.

### Give an issue to another person

Change the assignee in one edit: remove the old assignee and add the new assignee in the same change. On the
GitHub page, open the "Assignees" menu, clear the old account, select the new account, and then close the menu.

Do not change the assignee in two separate edits:

- If you add the new assignee first, the issue has two assignees for a short time. The old assignee has the
  earlier "assigned" event, so it stays the owner. The factory of the new assignee removes its own assignee. Then
  you remove the old assignee, and the issue has no assignee.
- If you remove the old assignee first, the issue has no assignee. Any factory can then pick up the issue.

An issue without assignee goes to the first factory that polls. Assign the issue again in one edit.

### Upgrade

An older factory ignores the assignees. Thus upgrade all the factories of a repository together. Before the
upgrade, look at the issues with the label `factory`. Remove their human assignees, or assign them to the
account of the token.

The labels are always orders. The factory does not check who added a label. An assignee change from any account
counts.

## The language of the agents

Each agent session, in auto mode and in live mode, gets the extra system prompt "When you write to a human,
write in Simplified Technical English (ASD-STE100): short sentences, active voice, simple tenses, and one
word for one thing." This applies to the questions, the descriptions of the pull requests, and the live
conversations, also for a target that has no such rule in its `CLAUDE.md`.

## Live mode

In live mode the owner talks to the agent of an issue through Remote Control. The factory needs `tmux` and
`jq` for this. The factory refuses to start without them.

### Enter

- The owner adds the label `factory:enter` to the issue.
- The agent can also ask a question. The event `question` of the `coding` action then moves the issue to a
  state with the action `conversation`, in the same way.

The worker stops the running action at once, because only one process can use the session of the agent. The
agent loses its current turn. Its edits stay in the worktree, and the action runs again after the conversation.
Then the worker removes the label and moves the issue to the `enter` target of its workflow. The action
`conversation` of this state starts the session in tmux. The session uses the
same session UUID as the agent: `claude --resume <uuid> --remote-control "Fix #<n>"`. The worker answers the trust
prompt on the tmux screen. It never edits `~/.claude.json`.

An action with no agent and no build, for example the merge call to GitHub, ends first. An Enter in the first seconds of a new session
opens no conversation: Claude Code has no session file yet, so the session cannot resume. The issue gets a note and
the agent starts again in a new session. Use Enter again when the card shows the button.

The worker sees a label that the page adds in about 2 seconds. It sees a label from the GitHub app at the next poll.

The factory uses its own tmux server, never your default tmux server. So the token of the worker never goes
into the environment of your tmux server. To see a session, run
`tmux -L claude-ai-factory attach -t factory-issue-<n>`. The factory turns on the mouse of each session, so
the mouse wheel scrolls the history. Hold Shift to select text for the clipboard.

An open live session holds its slot of `parallel-issues`. Thus the factory takes no new issue while you talk to
the agent. A question of the agent also opens a live session, so an open question holds a slot until you answer
it. After `/factory:continue`, the issue gets its slot again before any other issue.

The link of the conversation appears in a comment of the issue. The status comment also shows the line
`Live conversation: <link>`. While the live session is open, the worker starts no other action for the issue.

### The two places

The owner chooses where the live conversation opens. The page has one Enter button for each place. Each button
has a tooltip that tells what happens.

| Place | Where the conversation opens | Who opens it |
|---|---|---|
| Terminal | A terminal window on the machine of the worker, attached to the tmux session | The worker |
| Browser | A new tab of the browser that clicked, on the claude.ai conversation | The browser |

- **Terminal.** After the session shows its link, the worker runs the terminal command. The last arguments are
  the tmux attach command. The command comes only from the configuration, never from a request. The default is
  `gnome-terminal --`. To change it, set `live.terminal-command` (see below). A command that does not start
  gives a note in the record and a warning in the log. The Remote Control link still works. Terminal needs a
  desktop session with `DISPLAY` for the user of the worker. Close the window with `Ctrl-b d`. Do not type
  `/exit`: it ends the session without a continue.
- **Browser.** The click opens a new tab with a wait page: "The live conversation of #<n> opens…". The wait
  page asks the worker for the link. When the link is ready, the tab goes to the claude.ai conversation. The
  tab goes only to a link of Remote Control. The first time, claude.ai can ask for a device sign-in. On a
  phone, Browser also works.

An Enter with no page request, for example a label from the GitHub app, opens no window and no tab. Open the
link in the comment of the issue. On a phone, tap the link in the comment. The worker always posts the comment
with the link, because it is the record on GitHub.

Terminal shows only to a browser on the machine of the worker. The worker decides this from the remote address
of the request: `127.0.0.1` or `::1`. The server applies the same rule to the POST. A reverse proxy or a tunnel
on the same host, for example `ssh -L`, `tailscale serve`, or `cloudflared`, makes every device look local.
Then Terminal opens windows on the desktop for a remote viewer. Do not use Terminal behind such a proxy.

The terminal command is an optional setting:

```yaml
live:
  terminal-command: [gnome-terminal, --]   # optional; the worker adds the tmux attach command at the end
```

When two terminals of different sizes attach to one session, tmux uses the smaller size.

### Continue

Type `/factory:continue` in the conversation. Do not type `/continue`: it is a command of Claude Code and
it never reaches the factory. A hook of the factory sees `/factory:continue` and writes the marker
`<live-directory>/issue-<n>.continue`. The marker is outside the worktree. The worker sees the marker,
closes the session, and continues.

After `/factory:continue`:

- The action `conversation` sends the event `changed` when the files changed in live mode. Else it sends
  `continue`. In the default workflow, `changed` goes to `build`, so the build and the reviews run again, and
  `continue` goes to `previous`.
- `/factory:continue` is never a merge approval. Only the label `factory:approve` approves a merge.
- `previous` is the last state with an action that does not wait for you. So the issue continues in the
  action that the Enter stopped.
- In a `coding` state, the agent continues with the prompt `continue`.
- A return from the `enter` target does not set the counts of the limits to 0. A `continue` from the `block`
  target and an Enter from the `block` target do that.

### Files and security

The live folder is `live-directory` of the configuration. The default is `<work-directory>/live`. The
worker creates it with the mode `0700`. The worker stops with exit code 1 when one of these rules fails:

- The live folder must be a real folder. A symbolic link is refused.
- The user of the worker must own the live folder. The group and others must not write to it.
  Run `chmod 700 <live-directory>` to fix this.
- Each folder above the live folder must be safe. A folder is safe when the group and others cannot write
  to it. A folder is also safe when only the private group of the user can write to it. The private group has
  the name of the user and the folder belongs to the user. A sticky folder of root or of the user, for
  example `/tmp`, is also safe.
- A symbolic link above the live folder is judged by its target.
- To fix a folder above, run `chmod go-w <folder>`. You can also choose a `live-directory` in a private folder.

The worker writes the launch script `<live-directory>/issue-<n>.launch.sh` with the mode `0700`. The script
starts `claude` with `env -i` and `TERM=tmux-256color`, so the environment of the tmux server adds no
credential variable.

### Limits

- A stop of the worker keeps the live session. The next worker finds it again.
- A block closes the live session.
- A stop or a crash during an open can leave a session that no record knows. The worker closes it before
  the next action of the issue and writes a note in the status comment.
- A Remote Control session reconnects only in about 4 hours. It exits after about 10 minutes without
  network.
- When the session ends without `/factory:continue`, the issue stays in the state with the action
  `conversation`. The worker
  writes a note in the status comment. Add the label `factory:enter` to open a new session.

## Web page

The worker can serve a web page with the board of the factory. Without a `web` section in the
configuration, the worker serves no page.

### Setup

The command `create` writes the section `web` for you. To set the password by hand, do these steps:

1. Make the hash of a password: `java -jar target/claude-ai-factory-0.2.0-SNAPSHOT.jar hash-password`.
   The command asks for the password two times. The password must have 12 characters or more.
2. Copy the output line `password-hash: pbkdf2-sha256:...` into the `web` section of the configuration:

```yaml
web:
  address: 127.0.0.1    # optional; 0.0.0.0 opens the page to the LAN
  port: 8090            # optional
  password-hash: "pbkdf2-sha256:600000:..."
```

3. Start the worker. It writes the address of the page, for example `The web page is at http://127.0.0.1:8090/`.
   An address that is not a loopback address also gives a warning about plain HTTP.

The configuration holds only the hash, never the password. When the port is in use, the worker stops with
exit code 1.

### Login

The page asks for the password once on each device. A correct password gives a session cookie for 30 days.
The page, the board, and the events all need the login. After 5 wrong passwords, the worker refuses new
attempts for 15 minutes, also the correct password.

### Content

The header shows the health of the worker: its start time, the time of the last GitHub check, the issues in
work against the limit, and the isolation mode. It also shows each usage window of the subscription with its
percent. The tooltip of a usage window tells the time of the value. A phone shows no tooltip. When `.factory` of
the default branch does not load, a warning line at the top of the board gives the reason.

At its start, the worker runs one very short `claude -p` session to read the usage, so that the page shows it
before the first action. This session has no tools, no MCP servers, and no saved session, and it uses about
4,000 tokens. The worker keeps the values only when the session uses the subscription and ends with success.
After each action, the page shows the newest values. `claude` 2.1.316 gives only the weekly window.

The board has the column Queued, and then the columns of the workflow, in the order of their first use in the
YAML. For the default workflow, the columns are Queued, In progress, In review, Waits for you, Blocked, and
Done. The page shows the columns of the newest version of `.factory` that loads. A card whose column is not in
that version goes to an extra column at the end.

A card shows the issue, its state, the result of each review of its workflow, the entries against each limit (for example "implement 1/3"),
the risk in one line, the context of the agent, and the newest note. The reasons of the risk check are in the
status comment of the issue. A card in a state with the action
`approval` or `conversation` waits for you. It shows the reason and the Remote Control link.
After a click on a button, the card shows the sent request. Until the worker acts on it, the card is gray and
all its buttons are disabled.
The column of the `finish` state keeps the 20 newest cards.
The page updates itself when the state changes.
The page uses a dark theme. On a narrow screen, the columns go one under the other, and "Waits for you" is at
the top.

### Actions

A button shows only when its effect is possible. The worker checks the rule again when you click.
A label that the page did not ask for hides the button only after the worker finds the label at a poll.
A button of an old page gets "This action is not possible now." when the card changed. Load the page again.

| Button | Label | Effect |
|---|---|---|
| Terminal, Browser | `factory:enter` | Opens live mode in the chosen place. |
| Approve | `factory:approve` | Shows in a state with the action `approval`. Approves the merge, or accepts a new issue text. |
| Block | `factory:block` | Stops the running action and moves the issue to the `block` target. It does not show in a state without an action. |
| Start again | `factory:start-again` | Discards the earlier work of an issue in the `block` target and runs the issue from the `start` state. |
| Continue | (none) | Ends live mode, as `/factory:continue` in the conversation. In a state without an action, for example `blocked`, it sends the event `continue`. |

There are two ways to continue a blocked issue. Continue keeps the work. In the default workflow, `blocked` maps
`continue` to `previous`, so the issue continues in the action where it stopped. Enter and then
`/factory:continue` also keep the work. Start again discards the work: the worker writes a new record, removes the worktree,
resets the branch, and starts a new session. The issue takes the current version of `.factory`. The page asks for a confirm first. An open pull request stays open and
gets the new commit. A pull request that GitHub merged moves the issue to Done, so the change never runs again.
Start again shows only on an issue in the `block` target without a live conversation.

The two Enter buttons are one joined row of two parts. They have no icons, because a card is 240 px wide.
Each part has a tooltip. A phone shows no tooltip, so the names are clear alone. Approve and Block go on the
next row. The two Enter buttons add the same label. Approve and Block add their label too, with the token of
the worker. Continue writes the local marker and adds no label.

After one Enter, the card shows only the request line of the chosen place. For Terminal, the line says that a
terminal window opens on this machine. For Browser, it says that the new tab goes to the conversation. For a
label from the GitHub app, it says that the link goes to a comment of the issue.

While the session is open, the card shows a green box "Live conversation open". The box names the chosen place.
It also has one joined group of two parts: Terminal and Browser. The chosen place is blue. Each part opens the
conversation again in its place, also the blue one. For example, Terminal opens a new window after the owner
closed the first one. Terminal in the group makes no GitHub request. Browser is a link to the Remote Control
link. It opens a new tab.

After a click, the button shows a turning wheel and you cannot click it again. When you ask the browser for
less motion, the button shows "…". After the click, the card shows a blue line, for example "Approval sent.
The worker takes it in a few seconds." This line replaces the reason of the wait. It goes away when the
record of the issue shows the effect of the request. For example, the line of Block stays until the card moves to
the column of the `block` target.

The same line shows for `/factory:continue` in the conversation. The worker watches the live folder, so it tells
the page and starts a poll in about 2 seconds. The line also shows for a label from the GitHub app, when the worker
accepts the label at a poll.

After each click, the page asks the worker for an early poll. The worker polls 2 s later and does not wait
for the end of the poll interval. So Enter opens the conversation, and Approve, Block, and Continue take
effect, in a few seconds. The 2 s give GitHub time to show the new label in the list of issues. If GitHub
does not show the label yet, the worker acts at the next usual poll.

The page makes no GitHub request of its own. It shows the state from the last poll of the worker. A button
makes only one request: it adds one label.

### Stop and start the factory

The top right corner of the page shows the state of the factory. The color tells the state at a glance.

| State | Color | Meaning |
|---|---|---|
| Running | Green | The worker starts and moves the issues. |
| Stopping | Yellow | The owner clicked **Stop**. At least one issue is still in work. |
| Stopped | Red | The owner clicked **Stop**, and no issue is in work. |

**Stop** interrupts no action. Each issue in work does these steps:

1. The current action runs to its end, for example the agent session or the build.
2. The worker publishes the record, so the issue moves to its next state.
3. The loop of the issue stops. The card stays in its column.

When the last issue stops, the state changes from Stopping to Stopped. **Start** turns the factory on again. The
worker then continues the stopped issues and starts the waiting issues in about 2 seconds.

While the factory is stopping or stopped:

- No issue starts or continues. This includes the stopped issues and Start again.
- The worker does not touch a new issue with the `factory` label. It does not assign itself, it writes no record
  and no comment, and the issue gets no card. So another factory can take the issue. The worker takes it at the
  first poll after **Start**, if no other factory took it first.
- Block works at once. Enter opens the live conversation.
- An Approve and a `/factory:continue` wait until you click **Start**.

A stop stays after a restart of the worker. The marker file is `<work-directory>/factory-stopped`. The marker
`pick-up-paused` of the earlier "Pick up" button also keeps the factory stopped. **Start** removes both files.

### Known limits

- The page uses plain HTTP. On the LAN, another device can read the password and the cookie.
- A restart of the worker asks for the password again. It also loses the cards in "Done".
- The lock after 5 wrong passwords is for the whole worker, not for one device.
- A button acts at the early poll, 2 s after the click. If GitHub does not show the label yet, it acts at
  the next usual poll, so after one poll interval at most.
- A browser opens about 6 connections to one address. Many tabs of the page in one browser can stop the
  updates, the loads of the page, and the actions.
