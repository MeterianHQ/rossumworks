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

Add the label `factory` to an issue to give it to the factory. The factory does not check who added a request
label. It acts on each request label that it finds on an issue. Usually, only an account with triage access or
more can add a label.

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
before the start fails. Ctrl-C stops the running `claude` sessions. The interrupted phases run again from
their start at the next run.

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

The factory checks the owner at each poll and before each step. It also checks just before each push, each
change to the pull request, and the merge. A running agent or build is never interrupted.

The gatekeeper forwards a push to GitHub only when the branch on GitHub is at the commit that the factory
expects: the commit that it forwarded last, or the commit that its continuation starts from. Thus a factory never
removes a commit of another factory. When GitHub refuses the forward and another account owns the issue, the
factory stops as for a new assignee. Otherwise the refused forward counts as a failed forward check.
When the branch is gone from GitHub, the factory makes the branch again: there is no commit to protect.

GitHub can refuse the forward for good: the branch on GitHub has commits that the factory does not know. For
example, another person pushes to the branch, or the gatekeeper folder moves. After three failed forward
checks, the factory blocks the issue and writes a note. Check the branch on GitHub. To let the factory replace
the branch, delete the branch on GitHub, and then add the label `factory:start-again`.

When another account becomes the owner, the factory starts no new step. It writes one comment: "The factory of
`<login>` stops work on this issue, because the issue has a new assignee. The branch and the pull request stay.
The factory of the new assignee continues the work." An open live session stays open. A merged issue (phase
DONE) gets no stop.

### Continuation

- The new owner continues from the branch `factory/issue-<n>` on GitHub when the branch exists. It starts a new
  agent session, builds again, reviews again, and uses the existing pull request.
- When the branch does not exist, the new owner starts the issue from the beginning.
- The work before the first push stays on the first machine. The Claude session stays there too.
- The new owner keeps a block and a wait of the earlier record.
- The new owner keeps the issue text that the earlier owner accepted. When the text changed, the issue waits for
  `factory:approve` before the next agent phase. A record without the hash of the text counts as a change.
- A wait for the merge approval stays only when the pushed branch exists. After the approval, the factory builds
  and reviews again. Then it asks for the approval a second time. Without the branch, the issue starts from the
  beginning.
- A wait for an answer to a question, or for the end of a live conversation, does not pass on. The new owner
  continues the work and writes a note: the question stays on the machine of the earlier owner.
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
A label that the page did not ask for hides the button only after the worker finds the label at a poll.
A button of an old page gets "This action is not possible now." when the card changed. Load the page again.

| Button | Label | Effect |
|---|---|---|
| Terminal, Browser | `factory:enter` | Opens live mode in the chosen place. |
| Approve | `factory:approve` | Approves a merge that waits for the owner. |
| Block | `factory:block` | Takes the issue out of the factory and stops the running `claude` process. |
| Start again | `factory:start-again` | Discards the earlier work of a Blocked issue and runs the issue from the start. |
| Continue | (none) | Ends live mode, as `/factory:continue` in the conversation. |

There are two ways to continue a Blocked issue. Enter and `/factory:continue` keep the work and start again in the
phase where the issue stopped. Start again discards the work: the worker writes a new record, removes the worktree,
resets the branch, and starts a new session. The page asks for a confirm first. An open pull request stays open and
gets the new commit. A pull request that GitHub merged moves the issue to Done, so the change never runs again.
Start again shows only on a Blocked issue without a live conversation.

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
makes only one request: it adds one label.

### Stop and start the factory

The top right corner of the page shows the state of the factory. The color tells the state at a glance.

| State | Color | Meaning |
|---|---|---|
| Running | Green | The worker starts and moves the issues. |
| Stopping | Yellow | The owner clicked **Stop**. At least one issue is still in work. |
| Stopped | Red | The owner clicked **Stop**, and no issue is in work. |

**Stop** interrupts no step. Each issue in work does these steps:

1. The current step runs to its end, for example the agent session or the build.
2. The worker publishes the record, so the issue moves to its next phase.
3. The loop of the issue stops. The card stays in its column.

When the last issue stops, the state changes from Stopping to Stopped. **Start** turns the factory on again. The
worker then continues the stopped issues and starts the waiting issues in about 2 seconds.

While the factory is stopping or stopped:

- No issue starts or continues. This includes the stopped issues and Start again.
- The worker does not touch a new issue with the `factory` label. It does not assign itself, it writes no record
  and no comment, and the issue gets no card. So another factory can take the issue. The worker takes it at the first poll after **Start**,
  if no other factory took it first.
- Block works at once. Enter opens the live conversation.
- An Approve and a `/factory:continue` wait until you click **Start**.

A stop stays after a restart of the worker. The marker file is `<work-directory>/factory-stopped`. The marker
`pick-up-paused` of the earlier "Pick up" button also keeps the factory stopped. **Start** removes both files.

### Known limits

- The page uses plain HTTP. On the LAN, another device can read the password and the cookie.
- A restart of the worker asks for the password again. It also loses the cards in "Done".
- The lock after 5 wrong passwords is for the whole worker, not for one device.
- An action acts at the early poll, 2 s after the click. If GitHub does not show the label yet, it acts at
  the next usual poll, so after one poll interval at most.
- A browser opens about 6 connections to one address. Many tabs of the page in one browser can stop the
  updates, the loads of the page, and the actions.
