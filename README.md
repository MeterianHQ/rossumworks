# claude-ai-factory
A simple AI factory using your Claude subscription, running on GitHub

## Build

Run `./mvnw verify`. The build needs a JDK 21 or later.

## Configuration

Copy `factory.example.yaml` and change the values. The factory reads this file at start. The file holds only
the facts of this machine:

- `repository`: the repository on GitHub, as `owner/name`.
- `work-directory`: the folder of the worker. The worker clones the repository into `<work-directory>/repository`
  at the start, when this folder does not exist. A clone that exists stays as it is.
- `gatekeeper-directory` and `live-directory`: the folders of the gatekeeper and of the live files.
- `limits`: `parallel-issues`, `poll-interval`, and `usage-threshold`.
- `build.environment`: the variables of this machine, for example `JAVA_HOME`.
- `isolation`, `claude`, `live`, and `web`.

The workflow, the build, the merge target, the accounts, and the allowed tools are in the folder `.factory`
of the repository. A file with the old form (the keys `target`, `merge`, `accounts`, `prompts-directory`,
`limits.fix-loops`, or `claude.allowed-tools`) does not start. The message points to
`claude-ai-factory create`, which writes the new configuration.

## .factory

The folder `.factory` of the repository holds the definition of the factory: the workflow, the prompts, the
build, the limits, the merge target, the allowed tools, and the allowed accounts. The main file is
`.factory/factory.yaml`. The file [`src/main/resources/factory/factory.yaml`](src/main/resources/factory/factory.yaml)
is the example of the format.

Until the command `claude-ai-factory create` exists, the owner commits a `.factory` to the repository by hand.
Copy the folder `src/main/resources/factory/` to `.factory` and fill in `accounts.allowed`.

## The workflow definition

The folder `.factory` of the target repository holds the workflow, the prompts, the build, the limits, the
merge target, the allowed tools, and the allowed accounts. The main file is `.factory/factory.yaml`. The jar
contains the built-in definition in `src/main/resources/factory`, as an example of the format.

- At the start of each poll, the worker fetches the default branch in its clone. It reads `.factory` of the
  newest commit with git, never from the worktree of an issue.
- A new issue gets the newest version that loads. Its record keeps the commit of that version.
- Each issue runs with the workflow, the prompts, the build, the allowed tools, the limits, and the merge
  target of its own version. A new commit of `.factory` has no effect on the issues in work.
- The accounts always come from the newest version that loads. So a removed account stops at once.
- A version that does not load does not replace the last good version. The page shows the reason at the top.
- Without a version that loads, the worker takes no new issue.
- A record without a commit, from before the definitions, runs with the built-in definition.

In the configuration, `build.environment` gives the variables of this machine, for example `JAVA_HOME`. A
value of the machine replaces a value of `.factory` with the same name.

## Setup

The factory needs no GitHub Project. The worker creates its labels in the target repository: `factory`,
`factory:approve`, `factory:block`, `factory:enter`, and the six status labels `factory:status:<slug>`.

1. Log in to the `claude` CLI with your Claude subscription. Do not set `ANTHROPIC_API_KEY`: the factory
   refuses to start when it is set.
2. Copy `factory.example.yaml` to a new file and change the values.
3. Set `FACTORY_GITHUB_TOKEN` to a GitHub token of the worker account. If it is not set, the factory
   runs `gh auth token`, so you can also log in with `gh auth login`.
4. Make sure that `claude`, `git`, `setsid`, `kill`, `tmux`, and `jq` are on the `PATH`.
5. Build the jar with `./mvnw verify`.
6. Run the worker: `java -jar target/claude-ai-factory-0.1.0-SNAPSHOT.jar run --config <file>`.

Add the label `factory` to an issue to give it to the factory. Only the accounts in `accounts.allowed` of
`.factory/factory.yaml` on the default branch can do this.

Note: if the token belongs to the owner, GitHub sends the owner no notification for the comments of the
worker. A separate GitHub account for the worker gives the notifications.

The worker exits with code 2 when a `claude` session uses an API key. It exits with code 1 when a check
before the start fails. Ctrl-C stops the running `claude` sessions. The interrupted phases run again from
their start at the next run.

## The language of the agents

Each agent session, in auto mode and in live mode, gets the extra system prompt "When you write to a human,
write in Simplified Technical English (ASD-STE100): short sentences, active voice, simple tenses, and one
word for one thing." This applies to the questions, the descriptions of the pull requests, and the live
conversations, also for a target that has no such rule in its `CLAUDE.md`.

## Live mode

In live mode the owner talks to the agent of an issue through Remote Control. The factory needs `tmux` and
`jq` for this. The factory refuses to start without them.

### Enter

- The owner with an account of `accounts.allowed` adds the label `factory:enter` to the issue.
- The agent can also ask a question. The issue then waits for the owner in the same way.

The worker never stops a phase in the middle. It waits until the phase ends. Then it removes the label,
sets the status "Waits for you", and starts the session in tmux. The session uses the same session UUID as
the agent: `claude --resume <uuid> --remote-control "Fix #<n>"`. The worker answers the trust prompt on
the tmux screen. It never edits `~/.claude.json`.

The factory uses its own tmux server, never your default tmux server. So the token of the worker never goes
into the environment of your tmux server. To see a session, run
`tmux -L claude-ai-factory attach -t factory-issue-<n>`.

The link of the conversation appears in a comment of the issue. The status comment also shows the line
`Live conversation: <link>`. While the live session is open, the worker starts no phase for the issue.

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

- If the files changed in live mode, the build and the four reviews run again.
- `/factory:continue` is never a merge approval. Only the label `factory:approve` approves a merge.
- A blocked issue starts again in the phase where it stopped.
- In the state `implement`, the agent continues with the continue prompt.
- The fix-loop count does not go back to 0.

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
  the next phase of the issue and writes a note in the status comment.
- A Remote Control session reconnects only in about 4 hours. It exits after about 10 minutes without
  network.
- When the session ends without `/factory:continue`, the issue keeps the status "Waits for you". The worker
  writes a note in the status comment. Add the label `factory:enter` to open a new session.

## Web page

The worker can serve a web page with the board of the factory. Without a `web` section in the
configuration, the worker serves no page.

### Setup

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
before the first phase. This session has no tools, no MCP servers, and no saved session, and it uses about
4,000 tokens. The worker keeps the values only when the session uses the subscription and ends with success.
After each phase, the page shows the newest values. `claude` 2.1.316 gives only the weekly window.

The board has one column for each status, in the order of the progress in time: Queued, In progress,
In review, Waits for you, Blocked, Done. A card shows the issue, the phase, the four review results, the fix loops
against the limit of the definition of the issue, the risk, the context of the agent, and the newest note. A card that waits for you shows the reason and the Remote Control link.
The column "Done" keeps the 20 newest cards.
The page updates itself when the state changes.
The page uses a dark theme. On a narrow screen, the columns go one under the other, and "Waits for you" is at
the top.

### Actions

A button shows only when its action is possible. The worker checks the rule again when you click.
A label that the worker ignored does not hide the button; the button removes the label and adds it again.
A button of an old page gets "This action is not possible now." when the card changed. Load the page again.

| Button | Label | Effect |
|---|---|---|
| Terminal, Browser, Phone | `factory:enter` | Opens live mode in the chosen place. |
| Approve | `factory:approve` | Approves a merge that waits for the owner. |
| Block | `factory:block` | Takes the issue out of the factory and stops the running `claude` process. |
| Continue | (none) | Ends live mode, as `/factory:continue` in the conversation. |

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
less motion, the button shows "…". After the action, the card shows a blue line, for example "Approval sent.
The worker takes it in a few seconds." This line replaces the reason of the wait. It goes away when the
record of the issue shows the effect of the request.

After each action, the page asks the worker for an early poll. The worker polls 2 s later and does not wait
for the end of the poll interval. So Enter opens the conversation, and Approve, Block, and Continue take
effect, in a few seconds. The 2 s give GitHub time to show the new label in the list of issues. If GitHub
does not show the label yet, the worker acts at the next usual poll.

The page makes no GitHub request of its own. It shows the state from the last poll of the worker. An action
usually makes only one request: it adds one label. When the label is already on the issue, the action makes
two requests: it removes the label and adds it again.

### Known limits

- The page uses plain HTTP. On the LAN, another device can read the password and the cookie.
- A restart of the worker asks for the password again. It also loses the cards in "Done".
- The lock after 5 wrong passwords is for the whole worker, not for one device.
- An action acts at the early poll, 2 s after the click. If GitHub does not show the label yet, it acts at
  the next usual poll, so after one poll interval at most.
- A browser opens about 6 connections to one address. Many tabs of the page in one browser can stop the
  updates, the loads of the page, and the actions.
- A label that the page adds just before a restart is ignored with a note. Click the button again.
