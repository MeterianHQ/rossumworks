# claude-ai-factory
A simple AI factory using your Claude subscription, running on GitHub

## Build

Run `./mvnw verify`. The build needs a JDK 21 or later.

## Configuration

Copy `factory.example.yaml` and change the values. The factory reads this file at start.

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

Add the label `factory` to an issue to give it to the factory. Only the accounts in `accounts.allowed`
can do this.

Note: if the token belongs to the owner, GitHub sends the owner no notification for the comments of the
worker. A separate GitHub account for the worker gives the notifications.

The worker exits with code 2 when a `claude` session uses an API key. It exits with code 1 when a check
before the start fails. Ctrl-C stops the running `claude` sessions. The interrupted phases run again from
their start at the next run.

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

The link of the conversation appears in a comment of the issue. The status comment also shows the line
`Live conversation: <link>`. While the live session is open, the worker starts no phase for the issue.

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
