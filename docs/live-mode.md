# Live mode

In live mode, you talk to the agent of an issue in a live conversation, through Remote Control of Claude Code.
Then you give the issue back to the factory. This page tells how you open, use, and end a live conversation.

Only the subscription has the live mode. The API key provider has none. See [Providers](providers.md).

The live mode needs `tmux` and `jq`. The worker does not start without them, also with an API key.

## Open a live conversation

There are two ways to open a live conversation:

- You ask for it: you add the label `factory:enter` to the issue, or you click Terminal or Browser on its card.
  In these documents, this is an **Enter**.
- The agent asks you a question. In the default workflow, the question moves the issue to the state `live`.

After an Enter, the worker stops the running action at once. Only one process can use the session of the agent. The agent loses its current turn. Its changes stay in the worktree, and the action runs again after
the conversation. Then the worker removes the label, and moves the issue to the
[enter state](glossary.md). In the default workflow, this is the state `live`.

The state `live` has the action `conversation`. This action starts the session in tmux, with the same session ID as the agent:
`claude --resume <session ID> --remote-control "Fix #<n>"`. The worker answers the trust prompt on the tmux
screen. It never edits `~/.claude.json`.

Some cases need attention:

- The worker does not stop an action with no agent and no build, for example the merge call to GitHub. The
  conversation opens after this action ends.
- An Enter in the first seconds of a new session opens no conversation. Claude Code has no session file yet,
  so the session cannot resume. The worker writes a note in the status comment, and the agent starts again in a
  new session. Enter again when the card shows the buttons.
- The worker sees a click on the board in about 2 seconds. It sees a label that you add on GitHub at the next
  poll.

The link of the conversation goes into a comment on the issue. The status comment also shows the line
`Live conversation: <link>`. While the conversation is open, the worker starts no other action for the issue.

### The slots

An open live conversation holds the [slot](glossary.md) of its card, the same as each card that waits for you. Thus the factory takes no
new issue while you talk to the agent. See [Configuration](configuration.md).

- A question of the agent also opens a live conversation. An open question holds a slot until you answer it.
- After `/factory:continue`, the issue gets its slot again before any other issue.
- A blocked card holds no slot. Thus `factory:enter` on a blocked card waits for a free slot. The label stays,
  and the worker opens the conversation at a later poll.
- A card in Queued has no agent session yet. Thus an Enter on this card opens no conversation and needs no slot.

## Where the conversation opens

You choose where the live conversation opens. The card has one button for each place. Each button has a tooltip
that tells what it does.

| Place | Where the conversation opens | Who opens it |
|---|---|---|
| Terminal | A terminal window on the machine of the worker, attached to the tmux session | The worker |
| Browser | A new tab of the browser that clicked, on the claude.ai conversation | The browser |

### Terminal

After the session shows its link, the worker runs the terminal command. The worker adds the tmux attach
command at the end of the terminal command.

- The command comes only from the configuration, never from a request. The default is `gnome-terminal --`.
- When the command does not start, the worker writes a note in the status comment and a warning in the log. The
  Remote Control link still works.
- Terminal needs a desktop session with `DISPLAY` for the user of the worker.
- To close the window, type `Ctrl-b d`. Do not type `/exit`: it ends the session, and the issue does not
  continue.

To change the terminal command, set it in `worker.yaml`:

```yaml
live:
  terminal-command: [gnome-terminal, --]   # optional; the worker adds the tmux attach command at the end
```

The board shows Terminal only to a browser on the machine of the worker. The worker decides this from the
remote address of the request: `127.0.0.1` or `::1`. The worker applies the same rule when you click
the button.

> **Warning.** A reverse proxy or a tunnel on the same machine makes every device look local, for example
> `ssh -L`, `tailscale serve`, or `cloudflared`. Then Terminal opens windows on your desktop for a remote
> viewer. Do not use Terminal behind such a proxy.

### Browser

The click opens a new tab with a wait page: "The live conversation of #<n> opens…". The wait page asks the
worker for the link. When the link is ready, the tab goes to the claude.ai conversation. The tab goes only to a
link of Remote Control.

The first time, claude.ai can ask for a sign-in of the device. Browser also works on a phone.

### A label that you add on GitHub

When you add the label `factory:enter` on GitHub, no window and no tab opens. Open the link
in the comment of the issue. On a phone, tap the link in the comment. The worker always writes the comment with
the link, because the comment keeps the link on GitHub.

### See a session in tmux

The factory uses its own tmux server, never your default tmux server. Thus the token of the worker never goes
into the environment of your tmux server.

To see a session, run `tmux -L rossumworks attach -t factory-issue-<n>`. The factory turns on the mouse of each
session, so the mouse wheel scrolls the history. To select text for the clipboard, hold Shift. When two
terminals of different sizes attach to one session, tmux uses the smaller size.

## Give the issue back to the factory

Type `/factory:continue` in the conversation. Do not type `/continue`: it is a command of Claude Code, and it
never reaches the factory.

A hook of the factory sees `/factory:continue` and writes the marker `<live-directory>/issue-<n>.continue`. The
marker is outside the worktree. The worker watches the live folder. It sees the marker in about 2
seconds, closes the session, and continues the work.

After `/factory:continue`:

- The action `conversation` sends the event `changed` when the files changed in live mode. Else, it sends
  `continue`.
- In the default workflow, `changed` goes to `build`, so the build and the reviews run again. `continue` goes
  to `previous`. This word means the last state with an action that does not wait for you. Thus the issue
  continues in the action that the Enter stopped.
- In a `coding` state, the agent continues with the prompt `continue`.
- `/factory:continue` is never a merge approval. Only the label `factory:approve` approves a merge.
- The workflow can limit how many times an issue enters a state. The worker counts these entries. A return from
  the enter state does not set the counts to 0. A `continue` or an Enter from the [block state](glossary.md) sets
  them to 0. See [Factory definition](factory-definition.md).

The button Continue of the board does the same as `/factory:continue`.

## The live files and their security

The live folder is `live-directory` in `worker.yaml`. The default is `<work folder>/live`. The worker creates
it with the mode `0700`.

The worker stops with the exit code 1 when one of these rules fails:

- The live folder must be a real folder. The worker refuses a symbolic link.
- The user of the worker must own the live folder. The group and the other users must not write to it. To fix
  this, run `chmod 700 <live-directory>`.
- Each folder above the live folder must be safe. A folder is safe in these cases:
  - The group and the other users cannot write to it.
  - Only the private group of the user can write to it. The private group has the name of the user, and the
    folder belongs to the user.
  - It is a sticky folder of root or of the user, for example `/tmp`.
- For a symbolic link above the live folder, the worker checks the target of the link.
- To fix a folder above, run `chmod go-w <folder>`. You can also put the live folder in a private folder.

The worker writes the launch script `<live-directory>/issue-<n>.launch.sh` with the mode `0700`. The script
starts `claude` with `env -i` and `TERM=tmux-256color`. Thus the environment of the tmux server adds no
credential to the session.

## Limits

- A stop of the worker does not end the live conversation. The worker finds it again at its next start.
- A block ends the live conversation.
- A stop or a crash while a conversation opens can leave a session that is not in any status comment. The worker closes
  it before the next action of the issue, and writes a note in the status comment.
- A Remote Control session can reconnect only for about 4 hours. It ends after about 10 minutes without a network.
- When the session ends without `/factory:continue`, the issue stays in the state with the action
  `conversation`. The worker writes a note in the status comment. To open a new session, add the label
  `factory:enter`.
