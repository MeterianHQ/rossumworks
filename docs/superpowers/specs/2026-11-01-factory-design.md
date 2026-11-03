# Software factory: design

Date: 2026-11-01. Status: approved by the owner on 2026-11-01.

The owner decided on 2026-11-01: no GitHub Project; the status is a label
(goal: no or minimal human intervention).

## 1. Intent

The owner wants to move development into "factory mode". In this mode,
agents fix the GitHub issues of a repository with little human work. When
an issue is complex, the owner enters the conversation with the agent in
charge. The owner and the agent then discuss and brainstorm, and the
factory continues.

A first experiment used Fabro (report: branch `factory-main-fabro`, file
`factory/fabro/REPORT.md` in `MeterianHQ/heidi-fix`). Fabro merged 8 of 10
issues without a human. But Fabro lets the owner give only one answer at a
fixed gate. It does not let the owner talk with the agent. This factory
keeps the unattended work and adds the live conversation.

## 2. Goals, non-goals, and anti-goals

Goals:

- The factory fixes labeled issues without a human for most issues.
- The owner can enter the conversation of any issue, discuss with the
  agent, and give the issue back with one command.
- The owner sees the state of the factory on one web page, also on the
  phone.
- The factory uses only the Claude subscription of the owner.

Non-goals for the first version:

- More than one primary target for each factory.
- Releases. The factory never creates a release or pushes a tag.
- Its own CI. The local build replaces CI.

Anti-goal (the owner's words: "did not produce working or decent code"):

- A merged change that does not work, or that is not decent code, is a
  failure. It is a failure even when every phase passes. Section 7.3 gives
  the quality review that prevents it.

## 3. Constraints

These constraints come from the owner. They are not open to change.

1. **Subscription only.** The factory runs the unmodified `claude` CLI
   with the login of the owner. It never uses an API key. It never uses
   the Claude Agent SDK, or an ACP adapter for Claude
   (`@zed-industries/claude-code-acp`, `@agentclientprotocol/claude-agent-acp`).
   These adapters run on the Agent SDK, and the Anthropic policy forbids
   the Agent SDK with subscription credentials.
2. **No API key in the sessions.** The worker removes `ANTHROPIC_API_KEY`
   and `ANTHROPIC_AUTH_TOKEN` from the environment of each `claude`
   process. The `init` event of stream-json has the field `apiKeySource`.
   If its value is not `"none"`, the worker stops the session and stops
   the factory (fail closed).
3. **No internal endpoints.** The worker never calls an Anthropic endpoint
   with the login token from `~/.claude`. It gets the usage data only from
   the output of the CLI.
4. **GitHub is the backend.** The factory has no database and no server of
   its own for the state. The worker keeps no state that it cannot read
   again from GitHub.
5. **Java.** Java 21, Maven. The factory is in its own repository, not in
   Heidi Fix.
6. **Fail closed.** When a check cannot give a clear result, the factory
   stops the issue and does not merge.
7. **No push to a protected place.** The factory never pushes directly to
   the merge target branch, to another branch outside `factory/`, or to a
   tag. It pushes only branches that start with `factory/`. Changes reach
   the merge target branch only as a merged pull request. The merge
   target branch is configurable. It can be `main`.

## 4. Terms

- **Worker:** the Java program that runs on the machine of the owner. It
  reads GitHub, runs the `claude` sessions, and serves the web page.
- **Target:** the primary repository of a factory, for example
  `MeterianHQ/heidi-fix`.
- **Related repository:** a repository that an issue of the target can
  also change, for example a `lib-*` library. The configuration lists
  them.
- **Session:** one `claude` conversation, with a UUID.
- **Agent in charge:** the session that implements the issue. It is the
  session that the owner enters.
- **Phase:** one step of the work on an issue, for example "implement" or
  "review: security".
- **Auto mode:** the worker drives a session with `claude -p --resume`.
- **Live mode:** the worker opens the same session with
  `claude --resume <uuid> --remote-control`. The owner talks with the
  agent in claude.ai/code or in the Claude phone app.
- **Status:** one label on the issue, with the name `factory:status:<slug>`.
  It holds the state of the issue. The worker creates the labels.

## 5. Architecture

```
GitHub (issues, labels with the status, pull requests)
        ^   |
        |   v
     Worker (Java, this machine) ---- serves ---> web page (view only)
        |
        +-- auto mode: claude -p --resume <uuid> --output-format stream-json
        +-- live mode: tmux: claude --resume <uuid> --remote-control "Fix #N"
                              |
                              v
                 claude.ai/code or the phone app (owner)
```

- GitHub holds all the state. The owner can use the GitHub web page, the
  GitHub phone app, and `gh` as clients. The web page of the worker is a
  better view of the same state.
- The worker checks GitHub at a fixed interval (configurable, default 60
  seconds). This interval is the delay of each reaction.
- The worker uses one GitHub token. The token stays in the worker. It
  never goes to the browser or to an agent session.
- The worker respects the GitHub rate limit. When less than 100 requests
  remain, the poll starts no work until the reset time, but a running step
  can still publish its result. When GitHub refuses a request with a
  rate-limit header, the worker sends no request until the reset time. It
  logs one line for each wait. A step that meets the refusal runs again at
  the first poll after the reset. It is not a failure of the issue.
- At the end of each poll, the worker logs one line with the number of its
  GitHub requests: the total and the 10 largest counts by path template. The
  requests of the issue threads count in the line of the next poll.
- The worker reads little. It sends the last `ETag` for the issue list and
  for the comments of an issue, and a `304 Not Modified` answer reuses the
  cached body. It reads the events of an issue at most once in each check,
  and only for a request label. The status label changes without a second
  read of the issue.
- The worker can stop and start again at any time. At start, it reads the
  state from GitHub and continues. A phase that ran when the worker
  stopped runs again from its start.

### 5.1 Where the state is

| Data | Place |
|---|---|
| Which issues go into the factory | The label `factory` on the issue |
| The state of an issue | One label `factory:status:<slug>` (section 5.2) |
| A request to enter | The label `factory:enter` |
| A merge approval | The label `factory:approve` |
| A request to block | The label `factory:block` |
| The session UUIDs, the phase, the review results, the fix-loop count, the risk | A hidden JSON block in the status comment of the worker |
| A question of the agent | A comment of the worker |
| The changes | The branches `factory/issue-N` and their pull requests |

The **status comment** is one comment that the worker writes on the issue
and then edits in place. It shows the progress to a human, and it holds a
hidden JSON block for the worker (in an HTML comment). The worker reads
the block only from a comment that its own account wrote. It ignores a
block in any other comment.

The status label is only a display of the state. The worker never reads
it as a request or as a proof.

Labels need "triage" access on GitHub, but comments do not. For this
reason, a request from the owner is always a label, never a comment.

### 5.2 Status values

| Status | Label | Meaning |
|---|---|---|
| Queued | `factory:status:queued` | The issue has the label `factory`. No work started. |
| In progress | `factory:status:in-progress` | The agent in charge implements or fixes. |
| In review | `factory:status:in-review` | The four reviews and the risk check run. |
| Waits for you | `factory:status:waits-for-you` | The factory needs the owner. The status comment gives the reason. |
| Blocked | `factory:status:blocked` | The factory cannot continue, for example after the fix-loop limit. |
| Done | `factory:status:done` | The pull request is merged. The card shows the date. |

The worker creates the six status labels when they do not exist. Each issue
has exactly one status label. When the worker sets a status, it removes the
other status labels from the issue.

An issue in "Waits for you" or "Blocked" counts as "not in work" for the
parallel limit.

## 6. The life of an issue

1. **Intake.** The owner adds the label `factory`. At its next check, the
   worker sets "Queued" (the status label). The worker
   takes the "Queued" issues in the order of their issue number, up to
   the parallel limit (configurable, default 2).
2. **Prepare.** The worker creates one git worktree for each repository
   that the issue can change. Each worktree has the branch
   `factory/issue-N`. The base is the merge target branch.
3. **Implement.** The worker starts the agent in charge with a new session
   UUID and the implement prompt. The prompt contains the issue text
   inside a fence, as data and not as instructions.
4. **Build.** The worker runs the build command of each changed
   repository. The worker runs the build, not the agent. A failed build
   goes back to the agent in charge (a fix loop).
5. **Review.** Four reviews run, each in a new session, so that no review
   is anchored on the agent in charge: correctness, security,
   architecture, and quality.
6. **Fix loop.** If a review is negative, the worker gives all the
   findings to the agent in charge, in its own session. Then steps 4 and 5
   run again. The fix-loop limit is configurable (default 2). After the
   limit, the issue goes to "Blocked".
7. **Risk.** A risk session reads the change and gives a risk level: low,
   medium, or high. It also says if the change is small.
8. **Merge.** The worker merges automatically only when all these are
   true: the four reviews are positive, the risk is low, the change is
   small, the change touches only the target, and the build passed on the
   same git tree. Otherwise, the issue goes to "Waits for you" with the
   reason "merge needs approval". The owner adds `factory:approve`, or
   enters the conversation.
9. **Done.** The worker merges the pull request (squash) into the merge
   target branch. It sets "Done", deletes the worktrees, and removes the
   label `factory`. My decision on the issue: the pull request text has
   "Fixes #N". GitHub then closes the issue when the merge target branch
   is the default branch. For another merge target branch, the issue stays
   open, because the fix is not in the default branch yet.

The worker does not wait for GitHub Actions checks. The local build
replaces CI (owner decision).

### 6.0 Who can give a request (owner decision, changed on 2026-11-03)

The worker obeys the labels `factory`, `factory:enter`, `factory:approve`,
and `factory:block` from any account. It does not check who added the label.
The reason: GitHub permissions control the labels. Only an account with
triage access or more can add a label. The first design had a list of
allowed accounts. The owner removed it because it added no protection.

### 6.1 Proofs before a merge

The merge step checks these proofs and fails closed when one is missing.
The checks come from `factory/fabro/bin/merge-pr.sh` of the Fabro
experiment:

- Four positive reviews, each for the same git tree hash as the pull
  request head. The tree hash, not the commit, because the commit can
  change without a change of the files.
- A passed build for the same tree hash.
- No open question.
- The pull request is mergeable. After a rebase, GitHub can answer "not
  mergeable" for some seconds, so the check tries again 3 times.

My decision on a moved base, the same rule as `merge-pr.sh`: when the
merge target branch moves, the worker rebases the branch. If the
`git patch-id` of the diff does not change, the reviews stay valid. The
worker runs the build again and records the new tree hash. If the
patch-id changes, the four reviews and the risk check run again. A rebase
conflict goes back to the agent in charge as a fix loop.

The factory merges one issue of a target at a time. Just before the squash
merge, the worker fetches the merge target branch again. If the branch
moved after the rebase, the merge starts again from the rebase, at most 3
times. A push by a person in the short time between this check and the
merge can still pass. After the merge, the worker compares the tree of the
merge commit with the built tree and writes a note when they differ. The
owner can close the window with the GitHub branch protection "require
branches to be up to date before merging".

### 6.2 Changes across repositories

An issue of the target can also change a related repository.

- The agent in charge changes all the repositories in the same session.
  It builds and installs the library locally, and then builds the target
  against the local version.
- The worker opens one pull request in each changed repository. Each pull
  request links to the others and to the issue.
- Such a change always goes to "Waits for you", because the library needs
  a release before the target can use it. The factory does not release.
  The owner merges the library, releases it, and then approves the
  target. The target change must then use the released version. My guess
  at this flow is in section 12, point 3.

## 7. Agents

### 7.1 Sessions and permissions

- Each phase is one `claude -p` run with `--output-format stream-json
  --verbose`. The agent in charge uses `--session-id <uuid>` the first
  time and `--resume <uuid>` after that.
- The working directory is the worktree of the target. The worktrees of
  the related repositories are added with `--add-dir`.
- The agent can edit files, run the build, and commit. It cannot push and
  it cannot use `gh`. Only the worker pushes.

### 7.1.1 Isolation mode (owner decision)

The configuration value `isolation.mode` sets how strong the push guard
is. The first version builds only `same-user`. The code must keep the
mode as one place of change, so that `separate-user` can come later.

| Mode | Agents run as | Guard | Stops |
|---|---|---|---|
| `same-user` (first version, default) | `bbossola` | The gatekeeper, a fixed list of allowed tools (`--allowedTools`), denied tools (`--disallowedTools` for `git push`, `gh`, and `ssh`), and no GitHub token in the environment | A confused agent |
| `separate-user` (later) | A separate Linux user, for example `factory`, with no SSH key, no GitHub login, its own `claude` login, and its own JDK and Maven | The operating system | Also a hostile agent |

In `same-user` mode, the agents never use
`--dangerously-skip-permissions`. A hostile agent can still read the SSH
key of the owner and push directly to GitHub. The owner accepts this risk
for the first version. The status of the worker and the page header show
the active mode.
- The worker reads each line of the stream. It keeps the context in use
  (section 9.2), the usage data (section 9.3), and the result.

### 7.2 Questions

The prompts tell the agent: "If you must ask the owner, write the question
to `.factory/question.md` and stop." The rules for a question come from
the Fabro experiment and from the owner:

- Write in Simplified Technical English (ASD-STE100).
- Make the question complete. The owner must not need to read other
  material.
- Give only the context that is necessary for this question. Do not tell
  the history.
- Give numbered options with names, and a recommendation.

When the file exists after a phase, the worker posts the question as a
comment, sets "Waits for you" with the reason "question", and opens live
mode (section 8). The comment contains the Remote Control link and no
mention.

### 7.3 The four reviews

The prompts of the Fabro experiment are the start for correctness,
security, and architecture. The new **quality** review reads the
`CLAUDE.md` of each changed repository and checks the change against its
rules. For Heidi Fix, these rules include clean code, full names, small
methods, the code comments, the architecture document in step with the
code, and tests that check real behavior. A violation makes the review
negative.

Each review writes a result file with: positive or negative, the tree
hash that it reviewed, and the findings. The worker checks the tree hash.

### 7.4 Usage limits

All sessions share the 5-hour and the weekly windows of the subscription.
The worker does not start a new phase when a window is above a threshold
(configurable, default 90%), or when the last `rate_limit_event` has a
status that is not `allowed`. It starts again after the reset time of the
window.

## 8. Live mode

### 8.1 Enter

There are two ways to enter:

- The agent has a question (section 7.2).
- The owner adds the label `factory:enter`.

The worker never stops a phase in the middle, because a stopped phase can
leave changes that are not complete. If a phase runs, the worker waits
until it ends. Then the worker:

1. Removes the label `factory:enter`. This shows the owner that the worker
   took the request.
2. Sets "Waits for you" with the reason "live conversation".
3. Writes the launch script `<live-directory>/issue-<n>.launch.sh` with the
   mode 0700. The script runs `env -i` with the allowed variables and
   `TERM=tmux-256color`, and then `claude --resume <uuid> --remote-control
   "Fix #N" --settings <hook file> --plugin-dir <factory plugin>`. The
   filter of the variables is the same as for the auto mode, so the
   environment of the tmux server adds no credential variable.
4. Starts the script in a tmux session, in the worktree of the target. The
   factory uses its own tmux server (`tmux -L claude-ai-factory`), never the
   default server of the owner. So the token of the worker never goes into
   the environment of the server of the owner. To see a session: `tmux -L
   claude-ai-factory attach -t factory-issue-<n>`.
5. Writes the Remote Control link in a comment of the issue. The status
   comment shows the line "Live conversation: <link>".

The live folder is `<live-directory>`. The default is
`<work-directory>/live`. The worker creates it with the mode 0700. The worker
fails closed when the folder exists and the group or others can write to it,
or when another user owns it: another user could replace a launch script.
The same check applies to every folder above it, except a sticky folder (for
example `/tmp`) of root or of the current user, and except a folder that only
the private group of the user can write to. A symbolic link above is judged by
its target. A symbolic link is refused.
Then the worker stops with exit code 1 and the message names the fix
(`chmod 700`). The worker checks this before the first poll.

The worker needs `tmux` and `jq` on the `PATH` (`jq` is for the hook). The
start checks both.

Spike results (section 13):

- At the first interactive start in a folder, `claude` asks "Is this a
  project you created or one you trust?". The worker must answer this
  prompt, or mark the worktree as trusted before the start. Otherwise the
  session waits for ever.
- The worker answers the trust prompt on the tmux screen. It never edits
  `~/.claude.json` or another configuration file of Claude Code.
- The worker reads the link from the tmux screen (`tmux capture-pane`).
  The screen has the line `/remote-control is active · Continue here, on
  your phone, or at https://claude.ai/code/session_<id>`.

Only one of the worker and the owner talks to a session at a time. While
the tmux session runs, the worker starts no phase for this issue.

### 8.2 Continue

The owner types `/factory:continue` in the conversation, or clicks
"Continue" on the page. The owner never changes the "Status" by hand.

The command is not `/continue`. The spike showed that `/continue` is a
built-in command of Claude Code (the same as `/resume`). Over Remote
Control it gives "/resume isn't available over Remote Control", and it
never reaches a hook. A command that does not exist also stops before the
hook ("Unknown command").

The mechanism, verified in the spike from the phone:

- The worker has a small Claude Code plugin with the name `factory`. It
  has one command, `commands/continue.md`. The worker loads it with
  `--plugin-dir`. So the command is `/factory:continue`.
- The worker also starts the live session with `--settings <file>`. This
  file adds a `UserPromptSubmit` hook. The hook sees a prompt that starts
  with `/factory:continue` (the phone can add a space at the end), writes
  the marker file `<live-directory>/issue-<n>.continue`, and blocks the
  prompt with exit code 2. The marker is outside the worktree, so the agent
  does not see it. The hook needs `jq` to read the prompt. The owner sees
  the message of the hook. The worker sees the marker, ends the tmux
  session, and continues in auto mode with the same session UUID. The conversation has
the full discussion, so the agent continues with what the owner agreed.

My decisions on the next phase after `/factory:continue` or "Continue":

- `/factory:continue` or "Continue" is never a merge approval. Only `factory:approve` approves
  a merge.
- If the files changed in live mode, the build, the four reviews, and the
  risk check run again. A live conversation cannot take a change past the
  reviews.
- If the files did not change, the issue goes back to the phase where it
  stopped.
- The fix-loop count does not go back to 0.
- An issue that waited for a merge approval or for a changed issue text
  waits again for the same label. A blocked issue starts again in the phase
  where it stopped.
- In the phase IMPLEMENT, the agent continues with the continue prompt.

The shutdown of the worker keeps a live session: tmux keeps it, and the next
worker finds it again. A block closes it.

A shutdown, a crash, or a kill during an open can leave a tmux session that
no record knows. Before each phase of a record without a live state, the
worker closes such a session and writes a note in the status comment. When
the close fails, no phase starts, and the next poll tries again.

### 8.3 Known limits of Remote Control

From the research of 2026-11-01:

- A Remote Control session reconnects only in about 4 hours.
- The session exits after about 10 minutes without network.

When the tmux session exits without `/factory:continue`, the worker keeps "Waits
for you" and writes a note in the status comment. A new `factory:enter`
opens a new live session.

### 8.4 Where the conversation opens (owner decision)

The owner chooses the place of the live conversation when they click
Enter. There are three places:

| Place | Where the conversation opens | Who opens it |
|---|---|---|
| Terminal | A terminal window on the machine of the worker, attached to the tmux session | The worker, with the terminal command |
| Browser | A new tab of the browser that clicked, on the claude.ai conversation | The browser |
| Phone | The phone of the owner, from the GitHub notification of the comment | GitHub |

The owner decided on the page as follows. The mock of 2026-11-02 shows it.

- **The Enter buttons.** The single Enter button becomes three buttons,
  "Terminal", "Browser", and "Phone". They are one joined row of three
  parts with no icons, because a card is 240 px wide. Approve and Block go
  on the next row. After one Enter, the card shows only the request line
  of the chosen place.
- **Tooltips.** Each button has a tooltip that tells what happens. The
  tooltip uses only CSS and a `data-tip` attribute, so the CSP does not
  change. It shows after a short delay on a mouse hover or a keyboard
  focus. It stays inside the card. The same text is in `aria-label`.
  A phone shows no tooltip, so the names must be clear alone.
- **Terminal only on this machine.** The page shows Terminal only when
  the browser is on the machine of the worker: the remote address of the
  request is `127.0.0.1` or `::1`. The server applies the same rule to
  the POST.
- **The open conversation.** While the session is open, the card shows a
  green box "Live conversation open", a line that names the chosen
  place, and one group of three joined buttons: Terminal, Browser, and
  Phone. The chosen place is blue. Each button opens the conversation in
  its place, also the blue one, for example a new terminal window after
  the owner closed the first one. The first place always stays open.
- **No arrow.** Browser has no "new tab" arrow. Its tooltip says that it
  opens a new tab.

My decisions on the details:

- **The choice travels with the page request.** The label is still
  `factory:enter`. The page remembers the chosen place with the page
  request of the label (section 9.4). An Enter with no page request, for
  example a label from the GitHub app, is Phone.
- **The place goes into the record.** The live state of the record gets
  the field `place`, so that the card shows the chosen place also after
  a restart of the worker.
- **The comment with the link is always posted**, because it is the
  record on GitHub. The comment has no mention, because the worker writes it
  with the account of the owner and GitHub sends no notification for it.
- **Terminal.** After the session shows its link, the worker runs the
  terminal command with the tmux attach command as its last arguments:
  `tmux -L claude-ai-factory attach -t factory-issue-<n>`. The command
  comes only from the configuration (section 10), never from the
  request. The issue number is an integer. The default is
  `gnome-terminal --`. A command that does not start gives a note in the
  record and a warning in the log. The Remote Control link still works.
- **Browser.** The Enter button of Browser is a form with
  `target="_blank"`. Its POST adds the label as the other forms do. The
  answer is a wait page in the new tab: "The live conversation of #<n>
  opens…". The wait page reads the state of the card from the server.
  When the link is ready, the page goes to the link. The page goes only
  to a link that matches the Remote Control pattern. The tab opens on the
  click, so the browser does not block it. The wait page uses an external
  script, because the CSP forbids inline scripts.
- **The open buttons.** "Terminal" and "Phone" in the group are POST
  forms with the same login, same-origin, and fingerprint checks as the
  other actions. "Terminal" makes no GitHub request. "Phone" posts one
  more comment with the link. "Browser" is a plain link to the Remote
  Control link, with `target="_blank"` and `rel="noopener noreferrer"`.
- **Close a terminal window** with `Ctrl-b d`. `/exit` ends the session
  without a continue (section 8.3).

Known limits:

- Terminal needs a desktop session with `DISPLAY` for the user of the
  worker. On a server with no display, Terminal gives the note of the
  failed command.
- When two terminals of different sizes attach, tmux uses the smaller
  size.
- The first time, claude.ai can ask the browser for a new sign-in
  ("Sign in again to verify your device") before it shows a Remote
  Control session. The owner signs in. The factory never does.

Test of 2026-11-02, with `claude` 2.1.316: one Remote Control session
showed in two browser tabs and in the tmux terminal at the same time. A
message sent from one tab, and its answer, showed in the other tab and in
the terminal. So the open buttons can add a place while the first place
stays open.

Not in scope: a terminal inside the web page. It needs a shell in the
browser, a new channel, and a weaker CSP.

## 9. Web page

The worker serves the page. The page shows the state and has four action
buttons (section 9.4). Without a `web` section in the configuration, the
worker serves no page.

The page makes no GitHub request of its own. The account has a shared
limit of 5,000 requests for each hour, and the pilot hit it. The worker
keeps the board in memory. Each poll and each change of an issue record
updates it. The page shows only this board and the data from the agent
runs (the context and the usage). The classes of the page never get the
GitHub client. They get only a label writer, for the actions.

### 9.1 Content

- **Header:** the worker health and the usage. The health shows that the
  worker runs, the time of its last GitHub check, and the issues in work
  against the limit, for example "2 of 2". The usage shows each limit
  window that the CLI reported, with its percent and the time of the
  value.
- **Kanban board:** one column for each status label (section 5.2), in
  the order of the progress in time: Queued, In progress, In review,
  Waits for you, Blocked, Done. On the phone, "Waits for you" is at the
  top.
  The cards in "Done" show the date of the merge.
- **Done cards in memory:** the merge date is the time when the worker
  first saw the status Done. The column keeps the 20 newest cards. A
  restart loses them, because a list of the closed issues costs requests.
- **Card:** the issue number and title, and the current phase. The card
  also shows the four review results (positive or negative), the fix-loop
  count, the risk level, and a context bar (for example "62%"). It has
  links to the issue and to the pull requests.
- **"Waits for you" first:** a card that waits for the owner has a clear
  color and stays at the top of its column. It shows the reason in one
  line and the Remote Control link.
- **Automatic refresh:** the page updates itself when the state changes.
  My decision: Server-Sent Events from the worker.

### 9.2 Context bar

Each assistant turn in stream-json reports `input_tokens`,
`cache_read_input_tokens`, and `cache_creation_input_tokens`. The
`result` event reports `modelUsage.<model>.contextWindow`. The context in
use is the sum of the three counts of the last turn. The bar shows this
sum as a percent of the context window. In live mode, the worker reads
the same counts from the local session file. Verified on `claude`
2.1.316: a short run gave 26,262 tokens of 1,000,000.

The session file is
`<claude-home>/projects/<folder>/<session-uuid>.jsonl`. The claude home
is `$CLAUDE_CONFIG_DIR` when it is set, else `~/.claude`. The folder is
the absolute worktree path, with each character that is not a letter or
a digit changed to `-`. The worker reads only the end of the file and
takes the newest assistant turn. The window is the context window of the
newest auto run. Without a known window, the card shows no bar.

### 9.3 Usage

The stream-json output has a `rate_limit_event` with
`rate_limit_info.unifiedWindows`. Each window has `utilization` (0 to 1),
`resetsAt`, and `status`. Verified on `claude` 2.1.316: a run gave only
the `seven_day` window, at 0.37. The worker keeps the last value of each
window, with its time. The page shows no dollar cost, because the
subscription has no cost for each run.

Usage probe (owner decision, 2026-11-02): at its start, the worker runs
one very short `claude -p` session with no tools, no MCP servers, and
`--no-session-persistence`, and keeps its `rate_limit_event`. Thus the
page and the usage threshold have a value before the first phase. The
probe fails closed: a credential source that is not `none`, a session
that fails, or a session longer than 2 minutes gives no value.

### 9.4 Actions (owner decision)

The page has four action buttons. The Java worker does the work of each
button. Each button has a label with the same effect, so that the owner
can also use the GitHub phone app. The worker treats a button and a label
the same way.

| Button | Label | Effect |
|---|---|---|
| Continue | (none; also `/factory:continue` in the conversation) | Ends live mode and continues in auto mode (section 8.2). |
| Enter | `factory:enter` | Opens live mode (section 8.1). |
| Approve | `factory:approve` | Approves a merge that waits for the owner. |
| Block | `factory:block` | Takes the issue out of the factory and sets "Blocked". |

My decisions on "Block":

- Block does not wait for the phase to end. It stops the running `claude`
  process at the next poll, at most one poll interval later, because the
  owner can use Block to stop a wrong change. The worktree keeps the files
  as they are. A blocked issue never
  merges, so a phase that is not complete does no harm.
- To start a blocked issue again, the owner clicks "Enter", discusses with
  the agent, and then clicks "Continue".

A button shows only when its action is possible, for example "Approve"
only for the reason "merge needs approval". The worker checks the same
rule again when it gets the action. An action that is no longer possible
adds no label. Each form sends a fingerprint of the card: the status, the
wait reason, the phase, the fix loops, the reviewed tree, and the issue
text. A form from an old page gets "This action is not possible now."
A label that is already on the issue does not hide the button; the button
removes the label and adds it again.

An action costs only the requests that it needs. Enter, Approve, and
Block add one label. Continue writes the local marker of section 8.2 and
makes no request. A label that is already on the issue costs two requests:
the page removes it and adds it again. After each action, the page asks the
worker for an early poll, 2 s later, so the worker acts in seconds. If
GitHub does not show the label yet, the worker acts at the next usual
poll, so Block stops the `claude` process after one poll interval at most.

My decisions on the feedback of an action:

- After a click, the button shows a turning wheel, or "…" when the browser
  asks for less motion. The button is disabled, so a second click is not
  possible.
- The card shows a line for each request of the page that has no effect
  on the record yet, for example "Approval sent. The worker takes it in a
  few seconds." The line replaces the reason of the wait.
- After a live conversation with no change to the code, the record gets
  the note "The live conversation ended with no change to the code. The
  issue waits for you again." Without this note, the card looks the same
  as before Enter.

The page adds the label with the token of the worker. The worker obeys
the label like a label from any other account.

### 9.5 Access

- The worker listens on `127.0.0.1` by default. A configuration value
  opens it to the LAN.
- The action buttons need a login (section 9.6).

### 9.6 Login (owner decision: password)

The page asks for a password once on each device. The code keeps the
login in one place, so that another method can replace it later.

My decisions on the details:

- The configuration holds a hash of the password (PBKDF2), never the
  password. A command of the worker makes the hash.
- After a correct password, the worker sets a random session cookie with
  `HttpOnly` and `SameSite=Strict`, valid for 30 days.
- The page itself also needs the login, because it shows repository data.
- Each action is a `POST` request. With `SameSite=Strict`, another site
  cannot send an action in the name of the owner.
- After 5 wrong passwords, the worker refuses new attempts for 15
  minutes. The lock is for the whole worker, not for one device.
- The sessions are in memory. A restart of the worker asks for the
  password again.
- Known limit: on the LAN the traffic is plain HTTP. Another device on
  the LAN can read the password and the cookie. The owner accepts this
  for the first version.

## 10. Configuration

One file for each factory. My decision: YAML, read with Jackson. It holds:

- The target repository, its local clone, and its build command, for
  example `LANG=C.utf8 ./mvnw verify` with `JAVA_HOME` for Heidi Fix.
- The related repositories, each with its clone and its build and install
  command.
- The merge target branch.
- The isolation mode (section 7.1.1).
- The parallel limit, the fix-loop limit, the poll interval, and the
  usage threshold.
- The web page address and port, and the hash of the login password,
  in the optional section `web`:
  - `address`: the default is `127.0.0.1`. The value `0.0.0.0` opens the
    page to the LAN, and the worker writes a warning about plain HTTP.
  - `port`: from 1 to 65535. The default is 8090.
  - `password-hash`: required in the section. The command
    `hash-password` makes it.
- The terminal command of live mode (section 8.4), in the optional
  section `live`: `terminal-command`, a list of arguments. The default is
  `[gnome-terminal, --]`. The worker adds the tmux attach command after
  these arguments.
- The place of the prompts.

## 11. Reuse from the Fabro experiment

These parts are on the branch `factory-main-fabro` of
`MeterianHQ/heidi-fix`, in `factory/fabro/`. They are the start, not a
copy to keep as it is:

- `bin/merge-pr.sh`: the proofs of section 6.1.
- `bin/open-pr.sh`: the pull request text.
- `bin/setup-gatekeeper.sh`, `bin/guard-pre-push`, `bin/install-guard.sh`:
  the push guard. A local bare repository with a `pre-receive` hook
  accepts only `refs/heads/factory/*`. The worker pushes through it. A
  `--no-verify` cannot pass this guard.
- `bin/run-issue.sh`: the fence around the issue text.
- `workflows/fix-issue/`: the prompts for implement, the reviews, and the
  risk.
- `test/`: the tests of these scripts.

## 12. Left to the builder

1. The Java structure of the worker, inside the clean-code rules of the
   factory repository.
2. The GitHub API calls (REST).
3. The exact flow for a change across repositories after the library
   release, as long as it keeps the owner gate.
4. The prompt texts, as long as they keep the rules of sections 7.2
   and 7.3.

## 13. First task: the spike

Before the build, a throwaway spike of 1 or 2 days must prove three
points:

1. A tmux session that a script starts with `claude --resume <uuid>
   --remote-control "Fix #N"` shows in the Claude phone app, and the
   worker can get its link.
2. The context survives the change from `-p` to live mode and back.
3. The `UserPromptSubmit` hook of section 8.2 sees `/continue`.

If a point fails, the design comes back to the owner before the build.

Result of the spike on 2026-11-01 (`claude` 2.1.316):

1. Passed. The session from a script showed in the phone app. The link
   is on the tmux screen. A trust prompt needs an answer (section 8.1).
2. Passed. A code word from `-p` was known in live mode. A second code
   word from the phone was known in `-p` after live mode.
3. Passed, with a change. `/continue` is a built-in command, so the
   command is `/factory:continue` from a plugin (section 8.2).

Not tested: a long idle time of a Remote Control session. Section 8.3
handles an exit.
