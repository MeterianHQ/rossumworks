# The workflow of `.factory` as a state machine: design

Status: approved by the owner on 2026-11-05, after the brainstorming of 2026-11-03 to
2026-11-05. The owner approved each decision in this document one at a time. The
points marked "(my call)" are decisions of the writer. The owner approved them with
the whole document.

This document replaces section 5 ("The workflow definition") of
`2026-11-02-workflow-definitions-design.md`. The other sections of that
document stay valid, except where this document is different.

## 1. Intent

The folder `.factory` lets each person configure the workflow of the factory
for a repository. The owner cannot know these workflows before. So the YAML
must describe any workflow that the actions of the factory can do, not only
new names for one fixed workflow.

Version 1 of the format (PR #13) did not do this. It had exactly one state
for each step type, one loop, and fixed outcomes. It also had traps: waits
that no action of the owner ends. The owner judged it "NOT good enough to be
merged". This design replaces that format.

The model is a state machine:

- An issue is always in one **state**.
- When the issue enters a state, the factory starts the **action** of that
  state automatically. A state can also have no action. Then the issue waits.
- The action ends with an **event**, for example `passed`.
- The `on:` of the state names the next state for each event.

The words are state, action, and event. The YAML and the documents use only
these words. They do not use "step", "phase", or "status".

## 2. Goals, non-goals, anti-goals

Goals:

- Any number of states, in any order, with the names that the user chooses.
- Optional actions. Only `prepare` and `finish` are required.
- User-defined reviews: any number, each with a name and a prompt.
- A separate limit on each state, so a user can have a fix cycle of 2 and a
  review cycle of 10.
- A merge with three modes: `risk`, `auto-merge`, and `always-ask`.
- A default workflow that does what `main` does today (section 9).
- The loader refuses a workflow with a trap, before an issue meets the trap.

Non-goals:

- New action types. The actions are the actions of the factory today, plus
  `approval` and `conversation`.
- Code from the repository that runs in the worker.
- A graphical editor of workflows.
- The `create` command (plan B).

Anti-goals:

- Implicit blockers. The owner said: "If I decide I want auto merge, it's my
  decision." The loader refuses only a workflow that can trap an issue. It
  does not refuse a workflow because it is unsafe or unusual.
- A state that an issue can enter and never leave.
- Two words for the same thing.

## 3. Constraints

- All the constraints of `2026-11-01-factory-design.md` section 3 still
  apply: the subscription `claude` CLI only, never an API key, never the
  Agent SDK. Fail closed. The factory never pushes to `main` or to a tag.
  Tests never reach GitHub, a real token, the real `claude`, or the default
  tmux server.
- This is a personal tool. Do not add security rules that the owner did not
  ask for.
- The work goes to the long-lived branch `workflow-definitions`, not to
  `main`.
- The key `version` stays `1`. The format of PR #13 goes away. Nobody used it:
  it was never merged to `main` and never deployed. A `.factory` with the old
  keys gets the normal error of an unknown key, for example
  `factory.yaml: the state build has the unknown key step`.

## 4. The YAML of the workflow

```yaml
version: 1
workflow:
  start: prepare
  on-any-state: { block: blocked, enter: live, issue-text-changed: text-changed }
  states:
    <state name>:
      column: <column name>
      action: { type: <action type>, ... }
      limit: { entries: <N>, then: <state name> }
      on: { <event>: <state name> | previous }
```

### 4.1 `start`

The name of the first state. Its action must be `prepare`.

### 4.2 A state

| Key | Required | Description |
|-----|----------|-------------|
| `column` | yes | The column of the board. See section 4.6. |
| `action` | no | The action that starts when the issue enters the state. Without an action, the issue waits in the state. |
| `limit` | no | The maximum number of entries. See section 4.5. |
| `on` | yes, except with `finish` | The next state for each event. See section 4.3. |

Each action type is in one state at most. The exception is `approval`: it
can be in more than one state, because it only waits for you.

### 4.3 Events and `on:`

Each action type has a fixed list of events. The Java code of the action
sends them. The YAML cannot add an event. It only maps each event to a
state.

A value of `on:` is the name of a state, or the word `previous`.

An event only moves the issue. The work is in the actions. An event can
carry data to the next action: for example, `build` `failed` carries the
errors of the build, and the next `coding` action gives them to the agent as
`{{findings}}` (section 5.1). Section 6.6 lists the rules of the engine for
each move.

`previous` is the last state with an action that does not wait for you. So
`previous` skips the states without an action (`blocked`), and the states
with the action `approval` or `conversation` (`live` and the approval
states).

`previous` is the last state with an action that the issue moved toward,
also when its action did not run (owner decision). The action does not run
when a `limit` refuses the entry, when the issue text changed, or when you
block or click Enter before the action starts. Example: the reviews reject
at the limit of `implement`, so the issue goes to `blocked`. Then
`continue: previous` goes to `implement`, and the agent fixes the findings.
It does not run the reviews again.

An entry counts for `limit` only when the action of the state starts (my
call). So a return after a text change or a block does not count two times.

### 4.4 `on-any-state`

Three things can move an issue from any state. The YAML names their target
states once, in `on-any-state`:

| Key | What moves the issue | Required |
|-----|----------------------|----------|
| `block` | The label `factory:block`, an unexpected failure (section 6.3), or a refused merge. | yes |
| `enter` | Enter (the label `factory:enter`, or the button of the page). | yes |
| `issue-text-changed` | The issue text changed after the label `factory` (section 6.5). | yes |

Each target must be a state of the workflow. In the target state itself, the
same event does nothing. For example, `factory:block` on a blocked issue has
no effect.

The target of `issue-text-changed` must have the action `approval`. The
target of `enter` must have the action `conversation`. The target of `block`
must be a state with no action (owner decision).

### 4.5 `limit`

```yaml
limit: { entries: 3, then: blocked }
```

- `entries` counts each entry of the issue into the state, also the first
  entry. So `entries: 3` on a coding state is the first implementation and
  two fixes.
- At entry `entries + 1`, the issue does not enter the state. It goes to the
  `then` state. `then` is a state name. The word `previous` is not valid
  there.
- Each limit has its own count.
- When the issue leaves the `block` target state with the event `continue`,
  the counts of all the limits go back to 0. (On `main`, the count of the fix
  loop never goes back to 0. So after a block at the limit,
  `/factory:continue` gives no new fix. This design removes that defect.)
- An Enter from the `block` target also sets the counts of all the limits
  to 0, the same as `continue` (owner decision).

### 4.6 Columns and labels

- Each state must have `column`. A state without `column` is a load error.
- The names of the columns are free. The page shows them in the order of
  their first use in the YAML. The page writes them with a capital letter and
  with spaces, for example `waits-for-you` becomes "Waits for you".
- The column "Queued" is always first. The name `queued` is reserved.
- The GitHub label of the issue is `factory:is:<column>`, for example
  `factory:is:in-review`. The label is for display only.
- A GitHub label has 50 characters or less. So a column name has 39
  characters or less. A longer column name is a load error.
- The record in the status comment of the worker keeps the state. The label
  does not keep it.
- Issues can run on different versions of `.factory` (my call). The page
  shows the columns of the newest good version. A card whose column is not
  in that version goes to an extra column at the end, with the name of its
  column.

## 5. The action types

| Type | What the action does | Events | Prompts |
|------|----------------------|--------|---------|
| `prepare` | Creates the branch and the worktree of the issue. | `done` | none |
| `coding` | Runs a `claude` session that changes the code. | `changed`, `no-change`, `question`, `conflict-left`, `failed` | `implement`, `fix`, `continue`, `resume` |
| `build` | Runs the build command in the worktree. | `passed`, `failed` | none |
| `reviews` | Runs the reviews of the change. | `approved`, `rejected` | one for each review |
| `risk` | Runs a `claude` session that gives the risk and the size. | `assessed` | `risk` |
| `merge-gate` | Merges the change, or asks for your approval. | `merged`, `needs-approval`, `needs-new-reviews`, `conflict`, `factory-changed` | none |
| `approval` | Waits for the label `factory:approve`. | `approved` | none |
| `conversation` | Opens a live conversation with the agent, and waits for `/factory:continue`. | `continue`, `changed` | none |
| `finish` | Sets the column done, removes the worktree, and removes the label `factory`. | none | none |
| no action | The issue waits for `/factory:continue`. | `continue` | none |

### 5.0 The action `conversation` (owner decision)

The state `live` is, by definition, an open conversation. So the
conversation is the action of that state, and not the work of an event.

- When the issue enters the state, the action opens the live conversation:
  the hidden tmux session with `claude --resume` and Remote Control, and the
  comment with the link on the issue. It does not matter which event moves
  the issue there: Enter, `question`, or another event.
- The action ends when you type `/factory:continue`. It sends `continue` when
  the conversation did not change the code. When the conversation changed
  the code, the action commits the change, and then it sends `changed`. This
  is `LiveMode.chooseNextStep` on `main`.
- The `enter` target of `on-any-state` must have the action `conversation`.
  The action `conversation` is in one state at most.

### 5.1 The prompts of `coding`

The factory chooses the prompt from the event that moves the issue into the
`coding` state:

| The issue enters the `coding` state | Prompt | Placeholders |
|-------------------------------------|--------|--------------|
| for the first time | `implement` | `{{issue}}` |
| with an event that has findings: `build` `failed`, `reviews` `rejected`, `merge-gate` `conflict`, `coding` `conflict-left` | `fix` | `{{findings}}` |
| from the `enter` or `block` target, with `continue` | see below | |
| with each other event | `resume` | `{{findings}}` |

The factory keeps the pending prompt of each entry, with its findings, as on
`main` (`ImplementPrompt.choose`). This decides the prompt after a return
with `continue` (owner decision):

1. From the `block` target: the pending prompt runs again (`implement`,
   `fix`, or `resume`, with the same findings).
2. From the `enter` target: the prompt `continue`, and then the pending
   prompt when it is `fix` or `resume`.
3. When the agent session did not start yet: always `implement`.
4. When the findings of a pending `fix` or `resume` are missing: the issue
   goes to the `block` target, and the pending prompt becomes `implement`. So
   the next `continue` runs `implement`.

- `conflict-left` is in the list of events with findings (my call). The
  findings are the paths with conflict markers, as on `main`.
- `resume` tells the agent: "Continue the work of the issue." Its
  `{{findings}}` is the result of the action that sends the issue back. For
  `risk`, it is the risk, the size, and the reasons.
- The prompt `continue` is wrong for these other entries, because
  `continue.md` starts with "The owner discussed the issue with you in a live
  conversation".
- The loader requires the file of `resume` only when the workflow has an
  entry of this kind.

### 5.2 Reviews

```yaml
review:
  column: in-review
  action:
    type: reviews
    prompts:
      correctness: prompts/review-correctness.md
      security: prompts/review-security.md
```

- Each key of `prompts` is the name of one review. The user chooses the names
  and the number of reviews. One review or more.
- The event is `approved` when all the reviews approve. It is `rejected` when
  one review or more has findings. The findings of all the reviews go to
  `{{findings}}`.
- The placeholders of a review prompt are `{{issue}}`, `{{tree}}`, and
  `{{base}}`, as in version 1.
- The four reviews of `main` are files. The `create` command writes them into
  the default `.factory`. The factory has no built-in review.

### 5.3 Risk

The action `risk` gives the risk, `small`, and the reasons. The status
comment of the issue shows all three, for example:

```
risk: high, not small
- The change edits the authentication filter.
- The change has no test for the expired token.
```

Each new risk result replaces the reasons. The page shows the same reasons
on the card. (On `main`, the factory drops the reasons, and the status
comment shows only the risk.)

### 5.4 The merge gate

```yaml
merge:
  column: in-review
  action: { type: merge-gate, mode: risk }
```

`mode` is `risk` (the default), `auto-merge`, or `always-ask`.

| Mode | Checks | Then |
|------|--------|------|
| `risk` | The four checks below. | An automatic merge when the risk is `low` and the change is small. Else `needs-approval`. Without a `risk` state, always `needs-approval`. |
| `always-ask` | The four checks below. | `needs-approval`, until you approve. |
| `auto-merge` | Only the check of an open question. | The merge. |

The four checks of `main` (`MergeGate.decide`):

1. No question of the agent is open.
2. Each review approves.
3. The reviews are for the current files.
4. The build is for the current files.

The checks 2, 3, and 4 apply only when the workflow has the state of that
action. Without a `build` state, there is no build check.

A failed check refuses the merge. The issue goes to the `block` target, with
the reason, for example "the files changed after the reviews".

A pull request that changes `.factory` always gives `factory-changed`, also
with `auto-merge`.

### 5.5 The approval (owner decision)

The label `factory:approve` does two different things. The state decides
which one:

1. **The approval of a merge.** This is an approval state whose `approved`
   goes to the merge gate. When you add the label, the factory writes "the
   merge is approved for these files" in the record (the approved tree). The
   merge gate then accepts both `needs-approval` and `factory-changed` for
   these files, so it does not ask again. A change of the code before the
   merge removes the approval, as on `main`: a fix, a merge with the target,
   or a change in a live conversation. The merge gate then asks again for the
   new files.
2. **The approval of a new issue text.** This is the `issue-text-changed`
   target. The approval only accepts the new text. The factory then removes
   the label at once, as on `main` (`IssueTextCheck.acceptNewText`). It does
   not approve the merge.

Without this rule, the issue waits for ever: the gate gives `factory-changed`
again after each approval.

The loader does not check the paths to the merge gate. The owner wants few
implicit blockers. So a workflow can go from `coding` directly to the merge.

## 6. The behavior at run time

### 6.1 Enter while an action runs

The new model keeps the behavior of `main` since the commit ccfc2e1
("Stop the current step when the owner asks to enter", owner decision of
2026-11-05):

1. You click Enter. The worker sees the accepted label `factory:enter`.
2. The worker stops the running agent session or build at once, and drops
   the result of the action. A stopped session is not a failed session: the
   issue does not go to the `block` target, and a review round does not
   count as failed.
3. The issue moves to the `enter` target state. Its action `conversation`
   opens the live conversation.
4. `previous` is the state of the stopped action. So `continue: previous`
   runs that action again from the start.

When no action runs, the issue moves to the `enter` target at once.

### 6.2 `factory:block` while an action runs

The new model keeps the behavior of `main` (`Worker.requestBlock`):

1. The worker stops the action at once and drops its result.
2. The issue moves to the `block` target state.
3. `previous` is the state of the stopped action. So `continue: previous`
   runs that action again from the start.

Exception: when the merge is already done, the worker does not block. The
issue continues to `finish`.

### 6.3 An unexpected failure

An unexpected failure has no event, because the action did not end.
Examples: git fails in the worktree, the disk is full, or a defect in the
factory code. As on `main` (`Worker.blockOnFailure`):

1. The issue moves to the `block` target state.
2. The note on the issue gives the failure, for example "The factory stopped
   because of a failure: git worktree add failed".
3. `previous` is the state of the failed action.

The YAML does not write this transition.

### 6.4 Retries inside an action

Two retries of a technical failure stay inside the action, in the Java code.
The YAML does not show them.

| Retry | System property | Default number of tries |
|-------|-----------------|-------------------------|
| A review session fails, for example `claude` stops. The reviews run again. | `factory.retries.review-session` | `2` |
| After a push, GitHub does not show the commit yet. The next poll checks again. | `factory.retries.forward-check` | `3` |

The value is the number of tries, and the first try counts. So the value
`2` gives one try and one retry. After the last try, the action has an
unexpected failure (section 6.3). The count of the tries belongs to one run
of the action. Each new run of the action starts at 0. So after
`continue: previous`, the action has all its tries again.

A value that is not a whole number of 1 or more stops the worker at the
start, with a message that names the property. Example:

```
java -Dfactory.retries.review-session=4 -jar claude-ai-factory.jar worker
```

### 6.5 A change of the issue text

As on `main` (`IssueTextCheck`):

1. The factory keeps a hash of the issue text when it takes the issue.
2. Before each action that uses `{{issue}}` (`coding`, `reviews`, and
   `risk`), the factory compares the hash.
3. When the text changed, the issue moves to the `issue-text-changed` target.
   The action `approval` of this state starts. First it removes an older
   `factory:approve`, because only an approval after the change counts.
4. You add `factory:approve`. The factory accepts the new text. The event
   `approved` goes to `previous`, so the action runs with the new text.

### 6.7 Start again (owner decision)

The new model keeps the button "Start again" and the label
`factory:start-again` of `main` (commit f53b174):

1. The button and the label work only when the issue is in the `block`
   target state. In the other states, the worker removes the label and does
   nothing.
2. The worker deletes the earlier work of the issue. It writes a new record
   in the `start` state, with a new agent session. All the limit counts are
   0.
3. The action `prepare` makes the worktree and the branch again.
4. Exception: when the pull request of the issue is already merged, the
   issue goes to the `finish` state. So the factory never makes the change
   two times.

The YAML does not name this move.

### 6.6 What the engine does on a move

An event only moves the issue (section 4.3). But the engine applies these
rules on a move. The YAML does not write them.

1. **The label and the record.** Each move changes the label
   `factory:is:<column>`, and writes the record in the status comment
   (sections 4.6 and 8).
2. **`previous`.** Each move toward a state with an action that does not
   wait for you sets `previous` (section 4.3).
3. **The limit.** At the limit, the issue goes to the `then` state, and not
   into the state (section 4.5).
4. **The reset of the limits.** When the issue leaves the `block` target with
   `continue` or with an Enter, the counts of all the limits go back to 0
   (section 4.5).
5. **The moves of `on-any-state`.** `factory:block`, Enter, and a change of
   the issue text move the issue from any state (section 4.4).
6. **`factory:block` stops the action.** The running action stops, and its
   result goes away. When the merge is already done, the issue does not move
   (section 6.2).
7. **Enter stops the action.** The running action stops, and its result
   goes away. The issue goes to the `enter` target (section 6.1).
8. **The moves without an `on:` entry.** A refused merge and an unexpected
   failure move the issue to the `block` target, with the reason in a note
   (sections 5.4 and 6.3).
9. **A change of the code makes the results old.** After a fix, a merge with
   the target, or a change in a live conversation, the factory removes the
   results of the build, the reviews, and the risk, and the approval of the
   merge (sections 5.0 and 5.5), as on `main`.

## 7. The loader rules

The loader refuses a definition that breaks one of these rules. Each message
names the file, the state, and the key.

1. `version` is `1`. No key is unknown, at each level.
2. `start` exists, and its action is `prepare`. A state with `finish` exists.
3. Each action type is in one state at most, except `approval`.
4. Each state has `column`. No column is `queued`.
5. `on-any-state` has `block`, `enter`, and `issue-text-changed`. Each one
   names a state that exists. The target of `issue-text-changed` has the
   action `approval`. The target of `enter` has the action `conversation`.
   The target of `block` is a state with no action. Example:
   `factory.yaml: the block target build has the action build; the block target must have no action`.
6. Each `on:` maps each event of the action, and no other event. Example:
   `factory.yaml: state build has no next state for the event failed`.
7. Each value of `on:` is a state that exists, or `previous`. Each
   `limit.then` is a state that exists. `previous` is not valid there: at
   the limit, `previous` is the state itself. Example:
   `factory.yaml: the state implement has the limit then previous; the then of a limit must be a state name`.
8. Each state is reachable from `start`, through `on:` and `limit.then`. The
   targets of `on-any-state` count as reachable. Example:
   `factory.yaml: no state goes to the state security-scan`.
9. From each state, a path goes to the `finish` state. `limit.then` counts as
   a path. `previous` counts as a path back to each state with an action.
   Example: `factory.yaml: the state review has no path to the state done`.
10. Each cycle has a state with a `limit`. A cycle through a state that waits
    for you (a state without an action, or with `approval` or
    `conversation`) needs no limit.
    Example: `factory.yaml: the cycle implement, build has no limit`.
11. Each action has exactly the prompts of its type, and each prompt file
    exists. `resume` is required only when the workflow has an entry of the last row of the table of section 5.1.
12. Each prompt uses only the placeholders of its prompt (my call). This is
    the minor M2 of the review of PR #13: a typo such as `{{isue}}` then
    fails at the load, not at the first run.
13. `build.command`, `claude.allowed-tools`, and `merge.target-branch` are
    present. These sections do not change in this design.
14. The root key `limits` of PR #13 goes away. Each limit is in its state.

## 8. The record

The record in the status comment keeps (my call for the names):

- `state`: the name of the current state.
- `previousState`: the value of `previous`.
- `entries`: the count of each state with a `limit`.
- the results that the merge gate checks: the reviewed tree, the built tree,
  the risk, `small`, and the risk reasons.

The record no longer has `phase`, `status`, `waitReason`, or `fixLoops`.

A record of `main` in a fix loop gets the pending prompt `implement`.
`main` keeps its fix prompt in `.factory/fix-prompt.md`, not in the file of
the findings.

## 9. The default workflow

The default workflow does what `main` does today. The `create` command
writes it into a new `.factory`. The jar has a copy in
`src/main/resources/factory/factory.yaml`.

```yaml
version: 1
workflow:
  start: prepare
  on-any-state: { block: blocked, enter: live, issue-text-changed: text-changed }
  states:
    prepare:
      column: in-progress
      action: { type: prepare }
      on: { done: implement }
    implement:
      column: in-progress
      action:
        type: coding
        prompts: { implement: prompts/implement.md, fix: prompts/fix.md, continue: prompts/continue.md }
      limit: { entries: 3, then: blocked }
      on:
        changed: build
        no-change: blocked
        question: live
        conflict-left: implement
        failed: blocked
    build:
      column: in-progress
      action: { type: build }
      on: { passed: review, failed: implement }
    review:
      column: in-review
      action:
        type: reviews
        prompts:
          correctness: prompts/review-correctness.md
          security: prompts/review-security.md
          architecture: prompts/review-architecture.md
          quality: prompts/review-quality.md
      limit: { entries: 10, then: blocked }
      on: { approved: risk, rejected: implement }
    risk:
      column: in-review
      action: { type: risk, prompts: { risk: prompts/risk.md } }
      on: { assessed: merge }
    merge:
      column: in-review
      action: { type: merge-gate, mode: risk }
      on:
        merged: done
        needs-approval: waits-for-approval
        needs-new-reviews: build
        conflict: implement
        factory-changed: factory-change-approval
    waits-for-approval:
      column: waits-for-you
      action: { type: approval }
      on: { approved: merge }
    factory-change-approval:
      column: waits-for-you
      action: { type: approval }
      on: { approved: merge }
    text-changed:
      column: waits-for-you
      action: { type: approval }
      on: { approved: previous }
    live:
      column: waits-for-you
      action: { type: conversation }
      on: { continue: previous, changed: build }
    blocked:
      column: blocked
      on: { continue: previous }
    done:
      column: done
      action: { type: finish }
build:
  command: ./mvnw verify
  environment: { LANG: C.utf8 }
claude:
  allowed-tools: [Read, Edit, Write, Glob, Grep, "Bash(git status*)", "Bash(git diff*)", "Bash(git log*)",
                  "Bash(git show*)", "Bash(./mvnw *)", "Bash(mvn *)"]
merge:
  target-branch: main
repositories: []
```

### 9.1 The map from `main` to the default workflow

| On `main` | In the default workflow |
|-----------|-------------------------|
| Phase PREPARE | state `prepare` |
| Phase IMPLEMENT, with the prompts implement, fix, continue | state `implement` |
| Phase BUILD | state `build` |
| Phase REVIEW, four reviews | state `review` |
| Phase RISK | state `risk` |
| Phase MERGE | state `merge` |
| Phase DONE | state `done` |
| The fix loop, limit 2, shared by build, review, and conflict | `limit: { entries: 3, then: blocked }` on `implement`: all three events go to `implement` |
| Wait reason QUESTION | `question: live` |
| Wait reason MERGE_NEEDS_APPROVAL | state `waits-for-approval` |
| (not on `main`: a repository on `main` has no `.factory`; PR #13 added this wait) | state `factory-change-approval` |
| Wait reason ISSUE_TEXT_CHANGED | state `text-changed` |
| Wait reason LIVE_CONVERSATION | state `live` |
| Wait reasons BLOCKED_BY_OWNER, FIX_LOOP_LIMIT, FAILURE | state `blocked`, with the reason in the note |
| `/factory:continue` resumes in the phase where it stopped | `continue: previous` |
| Start again on a Blocked issue (`factory:start-again`) | the same, in the `block` target only (section 6.7) |
| A change in the live conversation goes to BUILD | `changed: build` |
| Status "In progress", "In review", "Waits for you", "Blocked", "Done" | the columns `in-progress`, `in-review`, `waits-for-you`, `blocked`, `done` |

The loader rules of section 7 accept this workflow:

- The cycles `implement`/`build`, `implement`/`review`, and the self-cycle of
  `implement` pass through `implement`, which has a limit.
- The cycle `merge`, `build`, `review`, `risk` passes through `review`, which
  has a limit.
- The cycles through `waits-for-approval`, `factory-change-approval`,
  `text-changed`, `live`, and `blocked` wait for you.

### 9.2 The differences from `main`

These differences are intended. The owner must check them.

1. The count of the limits goes back to 0 on `continue` from `blocked`
   (owner decision, section 4.5).
2. The status comment and the card show the risk reasons (owner decision,
   section 5.3).
3. The retries of section 6.4 are system properties (owner decision).
4. **The limit of 10 on `review` (my call).** On `main`, the round
   "the target changed, so the reviews run again" has no limit. Rule 10 of
   section 7 needs a limit on the cycle `merge`, `build`, `review`, `risk`.
   I chose 10 entries, so a normal issue never meets it.
5. **The column after `needs-new-reviews` (my call).** On `main`, the issue
   stays "In review" while the build runs again. In the new model, the issue
   enters `build`, so the card moves to "In progress". A state has one
   column, and one action type is in one state only, so the YAML cannot keep
   "In review" here.
6. **`limit.entries` counts also the entries from `previous` (my call).** On
   `main`, only a fix counts. In the new model, a return to `implement` after
   a live conversation also counts one entry. Section 4.5 says "each entry".

## 10. Other workflows (examples)

A workflow without reviews and without risk, with an automatic merge. The state
`build` has a limit, because rule 10 of section 7 refuses the cycle `build`,
`merge` without a limit:

```yaml
workflow:
  start: prepare
  on-any-state: { block: blocked, enter: live, issue-text-changed: text-changed }
  states:
    prepare: { column: doing, action: { type: prepare }, on: { done: code } }
    code:
      column: doing
      action: { type: coding, prompts: { implement: prompts/implement.md, fix: prompts/fix.md, continue: prompts/continue.md } }
      limit: { entries: 5, then: blocked }
      on: { changed: build, no-change: blocked, question: live, conflict-left: code, failed: blocked }
    build:
      column: doing
      action: { type: build }
      limit: { entries: 10, then: blocked }
      on: { passed: merge, failed: code }
    merge:
      column: doing
      action: { type: merge-gate, mode: auto-merge }
      on: { merged: done, needs-approval: approval, needs-new-reviews: build, conflict: code, factory-changed: approval }
    approval: { column: needs-me, action: { type: approval }, on: { approved: merge } }
    text-changed: { column: needs-me, action: { type: approval }, on: { approved: previous } }
    live: { column: needs-me, action: { type: conversation }, on: { continue: previous, changed: build } }
    blocked: { column: stuck, on: { continue: previous } }
    done: { column: done, action: { type: finish } }
```

A review cycle that runs up to ten times, with one security review only:

```yaml
    review:
      column: review
      action: { type: reviews, prompts: { security: prompts/review-security.md } }
      limit: { entries: 10, then: blocked }
      on: { approved: merge, rejected: code }
```

## 11. The documents

- Rewrite `docs/factory-definition.md` for this format. It describes only
  this format.
- Update the section ".factory" of `README.md` if it changes.
- These two changes give the reference only. They are not a full guide for
  the user.
- A follow-up issue writes an extensive user guide (the owner decision). The
  guide explains the model, each action type, and each event. It also walks
  through the default workflow, the example workflows, and the loader
  messages.

## 12. Left to the builder

- The Java types of the model, of the loader, and of the engine.
- The JSON names of the record (section 8 gives the content).
- The exact text of the load errors, in the style of the examples.
- The algorithm of the cycle check of rule 10 (for example, the strongly
  connected components of the graph).
- The text of the new prompt `resume`. The default `.factory` does not need
  it, but the jar can have an example file.
