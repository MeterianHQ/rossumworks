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
- `limits`: `parallel-issues`, `poll-interval`, and `usage-threshold`.
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
3. Build the jar with `./mvnw verify`.
4. Go to a clone of the target repository. Run `java -jar <path of the jar> create`, for example
   `java -jar ~/claude-ai-factory/target/claude-ai-factory-0.1.0-SNAPSHOT.jar create`. Answer the questions.
   Enter takes the default answer.
5. Merge the PR `factory/setup`.
6. Run the `run` command that `create` shows, for example
   `java -jar ~/claude-ai-factory/target/claude-ai-factory-0.1.0-SNAPSHOT.jar run --config <work folder>/worker.yaml`.

`create` checks that `claude`, `git`, `setsid`, `kill`, `tmux`, and `jq` are on the `PATH`. It clones the
repository into `<work folder>/repository`, shows a summary, and asks for a confirmation. The default answer is
"no". After a yes, it writes `<work folder>/worker.yaml`. When the default branch has no `.factory`, it then
pushes the branch `factory/setup` through the gatekeeper. It opens a PR and assigns it to the login of the
token. When the assignment fails, `create` shows the problem, keeps `worker.yaml`, and ends with success.
Then assign the PR by hand. Until the merge of the PR, the worker takes no issue, and the page shows the reason.
`create` never pushes to the default branch, and it never overwrites a file.

The default `.factory` holds `factory.yaml` and the prompt files that it uses. `create` does not copy
`prompts/resume.md` of the jar, because the default workflow does not use it.

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

Add the label `factory` to an issue to give it to the factory. The worker obeys the labels `factory`,
`factory:block`, `factory:enter`, and `factory:approve` from any account. GitHub permissions control the labels:
only an account with triage access or more can add a label. The `.factory` has no list of accounts, and the
loader refuses the key `accounts`.

Note: if the token belongs to the owner, GitHub sends the owner no notification for the comments of the
worker. A separate GitHub account for the worker gives the notifications.

The worker exits with code 2 when a `claude` session uses an API key. It exits with code 1 when a check
before the start fails. Ctrl-C stops the running `claude` sessions. The interrupted actions run again from
their start at the next run.

### Retries

Two retries of a technical failure stay inside an action. Set them with a system property of Java:

| Retry | System property | Default number of tries |
|---|---|---|
| A review session fails, for example `claude` stops. The reviews run again. | `factory.retries.review-session` | `2` |
| After a push, GitHub does not show the commit yet. The next poll checks again. | `factory.retries.forward-check` | `3` |

For example: `java -Dfactory.retries.review-session=4 -jar target/claude-ai-factory-0.1.0-SNAPSHOT.jar run --config <file>`.
The value is the number of tries, and the first try counts. So the value `2` gives one try and one retry.
After the last try, the issue goes to the `block` target of its workflow. A value that is not a whole number
of 1 or more stops the worker at the start, with exit code 1. The message names the property.

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
`tmux -L claude-ai-factory attach -t factory-issue-<n>`.

The link of the conversation appears in a comment of the issue. The status comment also shows the line
`Live conversation: <link>`. While the live session is open, the worker starts no other action for the issue.

### The three places

The owner chooses where the live conversation opens. The page has one Enter button for each place. Each button
has a tooltip that tells what happens.

| Place | Where the conversation opens | Who opens it |
|---|---|---|
| Terminal | A terminal window on the machine of the worker, attached to the tmux session | The worker |
| Browser | A new tab of the browser that clicked, on the claude.ai conversation | The browser |
| Phone | The phone of the owner, from the GitHub notification of the mention | GitHub |

- **Terminal.** After the session shows its link, the worker runs the terminal command. The last arguments are
  the tmux attach command. The command comes only from the configuration, never from a request. The default is
  `gnome-terminal --`. To change it, set `live.terminal-command` (see below). A command that does not start
  gives a note in the record and a warning in the log. The Remote Control link still works. Terminal needs a
  desktop session with `DISPLAY` for the user of the worker. Close the window with `Ctrl-b d`. Do not type
  `/exit`: it ends the session without a continue.
- **Browser.** The click opens a new tab with a wait page: "The live conversation of #<n> opens…". The wait
  page asks the worker for the link. When the link is ready, the tab goes to the claude.ai conversation. The
  tab goes only to a link of Remote Control. The first time, claude.ai can ask for a device sign-in.
- **Phone.** The worker adds a comment with the link and the mention of the owner. GitHub sends the
  notification to the phone. Only Phone sends a notification. For Terminal and Browser, the comment has no
  mention.

An Enter with no page request, for example a label from the GitHub app, is Phone. The comment with the link is
always posted, because it is the record on GitHub.

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

1. Make the hash of a password: `java -jar target/claude-ai-factory-0.1.0-SNAPSHOT.jar hash-password`.
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
work against the limit, and the isolation mode. When `.factory` of the default branch does not load, a
warning line at the top of the board gives the reason. It also shows each usage window of the subscription with its
percent and its time.

At its start, the worker runs one very short `claude -p` session to read the usage, so that the page shows it
before the first action. This session has no tools, no MCP servers, and no saved session, and it uses about
4,000 tokens. The worker keeps the values only when the session uses the subscription and ends with success.
After each action, the page shows the newest values. `claude` 2.1.316 gives only the weekly window.

The board has the column Queued, and then the columns of the workflow, in the order of their first use in the
YAML. For the default workflow, the columns are Queued, In progress, In review, Waits for you, Blocked, and
Done. The page shows the columns of the newest version of `.factory` that loads. A card whose column is not in
that version goes to an extra column at the end.

A card shows the issue, its state, the result of each review of its workflow, the entries against each limit,
the risk with its reasons, the context of the agent, and the newest note. A card in a state with the action
`approval` or `conversation` waits for you. It shows the reason and the Remote Control link.
The column of the `finish` state keeps the 20 newest cards.
The page updates itself when the state changes.
The page uses a dark theme. On a narrow screen, the columns go one under the other, and "Waits for you" is at
the top.

### Actions

A button shows only when its effect is possible. The worker checks the rule again when you click.
A label that the worker ignored does not hide the button; the button removes the label and adds it again.
A button of an old page gets "This action is not possible now." when the card changed. Load the page again.

| Button | Label | Effect |
|---|---|---|
| Terminal, Browser, Phone | `factory:enter` | Opens live mode in the chosen place. |
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

The three Enter buttons are one joined row of three parts. They have no icons, because a card is 240 px wide.
Each part has a tooltip. A phone shows no tooltip, so the names are clear alone. Approve and Block go on the
next row. The three Enter buttons add the same label. Approve and Block add their label too, with the token of
the worker. The worker accepts the label because the page asked for it. Continue writes the local marker and
adds no label.

After one Enter, the card shows only the request line of the chosen place. For Terminal, the line says that a
terminal window opens on this machine. For Browser, it says that the new tab goes to the conversation. For
Phone, it says that the link goes to the phone.

While the session is open, the card shows a green box "Live conversation open". The box names the chosen place.
It also has one joined group of three parts: Terminal, Browser, and Phone. The chosen place is blue. Each part
opens the conversation again in its place, also the blue one. For example, Terminal opens a new window after
the owner closed the first one. Terminal in the group makes no GitHub request. Phone posts one more comment
with the mention. Browser is a link to the Remote Control link. It opens a new tab.

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
usually makes only one request: it adds one label. When the label is already on the issue, the button makes
two requests: it removes the label and adds it again.

### Pick up

The header has a tile "Pick up" with one button. **Pause** stops the pick up, and **Start** turns it on again. The
worker then starts the waiting issues in about 2 seconds.

While the pick up is paused:

- A new `factory` label still puts the issue in Queued, but the agent does not start. You can prepare a set of issues.
- An issue that the worker started continues through its states. Enter, Approve, Block, and Continue still work.
- Start again also waits, because it makes the issue new. Its card stays in Queued until you click **Start**.

A pause stays after a restart of the worker. The marker file is `<work-directory>/pick-up-paused`.

### Known limits

- The page uses plain HTTP. On the LAN, another device can read the password and the cookie.
- A restart of the worker asks for the password again. It also loses the cards in "Done".
- The lock after 5 wrong passwords is for the whole worker, not for one device.
- A button acts at the early poll, 2 s after the click. If GitHub does not show the label yet, it acts at
  the next usual poll, so after one poll interval at most.
- A browser opens about 6 connections to one address. Many tabs of the page in one browser can stop the
  updates, the loads of the page, and the actions.
- A label that the page adds just before a restart is ignored with a note. Click the button again.
