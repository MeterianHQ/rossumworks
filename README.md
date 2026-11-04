# claude-ai-factory
A simple AI factory using your Claude subscription, running on GitHub

## Build

Run `./mvnw verify`. The build needs a JDK 21 or later.

## Configuration

Copy `factory.example.yaml` and change the values. The factory reads this file at start.

## Setup

The factory needs no GitHub Project. The worker creates its labels in the target repository: `factory`,
`factory:approve`, `factory:block`, `factory:enter`, `factory:start-again`, and the six status labels `factory:status:<slug>`.

1. Log in to the `claude` CLI with your Claude subscription. Do not set `ANTHROPIC_API_KEY`: the factory
   refuses to start when it is set.
2. Copy `factory.example.yaml` to a new file and change the values.
3. Set `FACTORY_GITHUB_TOKEN` to a GitHub token of the worker account. If it is not set, the factory
   runs `gh auth token`, so you can also log in with `gh auth login`.
4. Make sure that `claude`, `git`, `setsid`, `kill`, `tmux`, and `jq` are on the `PATH`.
5. Build the jar with `./mvnw verify`.
6. Run the worker: `java -jar target/claude-ai-factory-0.1.0-SNAPSHOT.jar run --config <file>`.

Add the label `factory` to an issue to give it to the factory. Only the accounts in `accounts.allowed`
can do this.

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

The worker stops the running step at once, because only one process can use the session of the agent. The agent
loses its current turn. Its edits stay in the worktree, and the step runs again after the conversation. Then the
worker removes the label, sets the status "Waits for you", and starts the session in tmux. The session uses the
same session UUID as the agent: `claude --resume <uuid> --remote-control "Fix #<n>"`. The worker answers the trust
prompt on the tmux screen. It never edits `~/.claude.json`.

A step with no agent and no build, for example the merge call to GitHub, ends first. An Enter in the first seconds of a new session
opens no conversation: Claude Code has no session file yet, so the session cannot resume. The issue gets a note and
the agent starts again in a new session. Use Enter again when the card shows the button.

The worker sees a label that the page adds in about 2 seconds. It sees a label from the GitHub app at the next poll.

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
- In the phase IMPLEMENT, the agent continues with the continue prompt.
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
work against the limit, and the isolation mode. It also shows each usage window of the subscription with its
percent and its time.

At its start, the worker runs one very short `claude -p` session to read the usage, so that the page shows it
before the first phase. This session has no tools, no MCP servers, and no saved session, and it uses about
4,000 tokens. The worker keeps the values only when the session uses the subscription and ends with success.
After each phase, the page shows the newest values. `claude` 2.1.316 gives only the weekly window.

The board has one column for each status, in the order of the progress in time: Queued, In progress,
In review, Waits for you, Blocked, Done. A card shows the issue, the phase, the four review results, the fix loops, the risk, the context
of the agent, and the newest note. A card that waits for you shows the reason and the Remote Control link.
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
| Start again | `factory:start-again` | Discards the earlier work of a Blocked issue and runs the issue from the start. |
| Continue | (none) | Ends live mode, as `/factory:continue` in the conversation. |

There are two ways to continue a Blocked issue. Enter and `/factory:continue` keep the work and start again in the
phase where the issue stopped. Start again discards the work: the worker writes a new record, removes the worktree,
resets the branch, and starts a new session. The page asks for a confirm first. An open pull request stays open and
gets the new commit. A pull request that GitHub merged moves the issue to Done, so the change never runs again.
Start again shows only on a Blocked issue without a live conversation.

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
record of the issue shows the effect of the request. For example, the line of Block stays until the card moves to
Blocked.

The same line shows for `/factory:continue` in the conversation. The worker watches the live folder, so it tells
the page and starts a poll in about 2 seconds. The line also shows for a label from the GitHub app, when the worker
accepts the label at a poll.

After each action, the page asks the worker for an early poll. The worker polls 2 s later and does not wait
for the end of the poll interval. So Enter opens the conversation, and Approve, Block, and Continue take
effect, in a few seconds. The 2 s give GitHub time to show the new label in the list of issues. If GitHub
does not show the label yet, the worker acts at the next usual poll.

The page makes no GitHub request of its own. It shows the state from the last poll of the worker. An action
usually makes only one request: it adds one label. When the label is already on the issue, the action makes
two requests: it removes the label and adds it again.

### Pick up

The header has a tile "Pick up" with one button. **Pause** stops the pick up, and **Start** turns it on again. The
worker then starts the waiting issues in about 2 seconds.

While the pick up is paused:

- A new `factory` label still puts the issue in Queued, but the agent does not start. You can prepare a set of issues.
- An issue that the worker started continues through its phases. Enter, Approve, Block, and Continue still work.
- Start again also waits, because it makes the issue new. Its card stays in Queued until you click **Start**.

A pause stays after a restart of the worker. The marker file is `<work-directory>/pick-up-paused`.

### Known limits

- The page uses plain HTTP. On the LAN, another device can read the password and the cookie.
- A restart of the worker asks for the password again. It also loses the cards in "Done".
- The lock after 5 wrong passwords is for the whole worker, not for one device.
- An action acts at the early poll, 2 s after the click. If GitHub does not show the label yet, it acts at
  the next usual poll, so after one poll interval at most.
- A browser opens about 6 connections to one address. Many tabs of the page in one browser can stop the
  updates, the loads of the page, and the actions.
- A label that the page adds just before a restart is ignored with a note. Click the button again.
