# The factory definition

This page is the reference of the **definition**: the folder `.factory` of a repository. The definition tells the
worker how to work on the issues of the repository. It holds the workflow, the prompts, the build, the tools of
the agent, and the target branch.

Read this page when you change the definition. The [glossary](glossary.md) explains the terms.

A change of `.factory` has no effect on the issues in work (see section 13). A pull request that changes `.factory`
always needs your approval before the merge.

## 1. The folder

```
.factory/
  factory.yaml          the main file
  prompts/              the prompt files that factory.yaml names
    implement.md
    fix.md
    ...
  run.sh                the script that starts the worker with the latest release
```

The main file is `.factory/factory.yaml`. The paths of the prompt files are relative to the folder `.factory`.
You can put the prompt files in any subfolder of `.factory`. A path cannot leave the folder `.factory`: the
loader refuses a path that starts with `/` or that has the segment `..`.

The jar of the factory contains a complete example:
[`src/main/resources/factory/`](../src/main/resources/factory/). Section 15 shows its `factory.yaml`. To start,
run the command `create` of the factory (see [Getting started](getting-started.md)). It writes this folder as `.factory` with the
build command of your repository, adds `run.sh`, and opens the PR `factory/setup`. The loader does not read
`run.sh`. Then change the files as you need.

## 2. The main file

`factory.yaml` has these sections. The loader refuses a key that it does not know, at each level.

| Key | Required | Description |
|-----|----------|-------------|
| `version` | yes | The version of the format. The only value is `1`. |
| `workflow` | yes | The state machine of the issues. See section 3. |
| `build` | yes | The build command. See section 12. |
| `claude` | yes | The tools that the agent can use. See section 12. |
| `merge` | yes | The target branch. See section 12. |
| `repositories` | no | A list of text. Version 1 reads it, but does not use it. |

## 3. The workflow

The workflow is a state machine:

- An issue is always in one **state**.
- When the issue enters a state, the worker starts the **action** of that state automatically. A state can
  also have no action. Then the issue waits.
- The action ends with an **event**, for example `passed`.
- The `on` of the state names the next state for each event.

```yaml
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

| Key | Required | Description |
|-----|----------|-------------|
| `start` | yes | The name of the first state. Its action must be `prepare`. |
| `on-any-state` | yes | The target states of the three moves from any state. See section 5. |
| `states` | yes | The states, by name. You choose the names and the number of states. |

The word `previous` has a special meaning (section 4.2). A state cannot have this name.

## 4. A state

```yaml
    build:
      column: in-progress
      action: { type: build }
      on: { passed: review, failed: implement }
```

| Key | Required | Description |
|-----|----------|-------------|
| `column` | yes | The column of the board. See section 9. |
| `action` | no | The action that starts when the issue enters the state. Without an action, the issue waits in the state. See section 6. |
| `limit` | no | The maximum number of entries into the state. See section 8. |
| `on` | yes, except with `finish` | The next state for each event of the action. |

Each action type is in one state at most. The exception is `approval`: it can be in more than one state,
because it only waits for you.

### 4.1 The events and `on`

Each action type has a fixed list of events (section 6). The Java code of the action sends them. The YAML
cannot add an event. It only maps each event to a state.

- `on` must map each event of the action. An event that the action does not have is an error.
- A state with the action `finish` has no event, so it has no `on`.
- A state without an action has one event: `continue`.

An event only moves the issue. The work is in the actions. An event can carry data to the next action. For
example, the event `failed` of `build` carries the errors of the build. The next `coding` action gives them to
the agent as `{{findings}}`.

### 4.2 `previous`

A value of `on` is the name of a state, or the word `previous`.

`previous` is the last state with an action that does not wait for you. So `previous` skips the states without
an action, and the states with the action `approval` or `conversation`.

`previous` is the last state with an action that the issue moved toward, also when its action did not run.
The action does not run in these cases:

- A `limit` refuses the entry.
- The issue text changed.
- You block the issue, or you enter (see section 5), before the action starts.

Example: the reviews reject at the limit of `implement`, so the issue goes to `blocked`. Then
`continue: previous` goes to `implement`, and the agent fixes the findings. The reviews do not run again.

## 5. `on-any-state`

Three things can move an issue from any state. The YAML names their target states once, in `on-any-state`.

| Key | What moves the issue | Required |
|-----|----------------------|----------|
| `block` | The label `factory:block`, an unexpected failure (section 10.3), or a refused merge (section 6.6). | yes |
| `enter` | An Enter: the label `factory:enter`, or the button Terminal or Browser of the board. | yes |
| `issue-text-changed` | The issue text changed after the label `factory` (section 10.5). | yes |

- The target of `block` is the **block state**. The target of `enter` is the **enter state**. The target of
  `issue-text-changed` is the **text-change state**.
- Each target must be a state of the workflow.
- The target of `issue-text-changed` must have the action `approval`.
- The target of `enter` must have the action `conversation`.
- The target of `block` must be a state with no action.
- In the target state itself, the same move does nothing. For example, `factory:block` on a blocked issue has
  no effect.

## 6. The action types

| Type | What the action does | Events | Prompts |
|------|----------------------|--------|---------|
| `prepare` | Creates the branch and the worktree of the issue. | `done` | none |
| `coding` | Runs a `claude` session that changes the code. | `changed`, `no-change`, `question`, `conflict-left`, `failed` | `implement`, `fix`, `continue`, and `resume` (optional) |
| `build` | Runs the build command in the worktree. | `passed`, `failed` | none |
| `reviews` | Runs the reviews of the change. | `approved`, `rejected` | one for each review |
| `risk` | Runs a `claude` session that gives the risk and the size of the change. | `assessed` | `risk` |
| `merge-gate` | Merges the change, or asks for your approval. | `merged`, `needs-approval`, `needs-new-reviews`, `conflict`, `factory-changed` | none |
| `approval` | Waits for the label `factory:approve`. | `approved` | none |
| `conversation` | Opens a live conversation with the agent, and waits for `/factory:continue`. | `continue`, `changed` | none |
| `finish` | Sets the column of its state, removes the worktree, and removes the label `factory`. | none | none |
| no action | The issue waits for the button Continue, or for `/factory:continue`. | `continue` | none |

An action reads its prompts from `action.prompts`, a map from the prompt name to the file:

```yaml
      action:
        type: coding
        prompts: { implement: prompts/implement.md, fix: prompts/fix.md, continue: prompts/continue.md }
```

Only `merge-gate` has the key `action.mode`. The other action types have only `type` and `prompts`.

### 6.1 `prepare`

The action creates the branch and the worktree of the issue. It sends `done`. The start state must have
this action.

When the issue has a new assignee, the factory of the new assignee can continue the pushed branch. See section 10.8.
Then the action makes the worktree from `origin/factory/issue-<n>`. The issue goes to the first state with the
action `build`, in the order of the moves from the start state. In a workflow without a `build` state, the action
sends `done`.

### 6.2 `coding`

The action runs a `claude` session in the worktree. Its events:

- `changed`: the agent made a change. The worker commits it.
- `no-change`: the agent made no change.
- `question`: the agent asks you a question. The worker writes the question on the issue.
- `conflict-left`: conflict markers stay in the files after a merge with the target branch.
- `failed`: the session failed.

The worker chooses the prompt from the event that moves the issue into the `coding` state:

| The issue enters the `coding` state | Prompt | Placeholders |
|-------------------------------------|--------|--------------|
| for the first time | `implement` | `{{issue}}` |
| with an event that has findings: `build` `failed`, `reviews` `rejected`, `merge-gate` `conflict`, `coding` `conflict-left` | `fix` | `{{findings}}` |
| from the enter state or the block state, with `continue` | see below | |
| with each other event | `resume` | `{{findings}}` |

When an issue enters the `coding` state, the worker selects a prompt. It keeps this **pending prompt** and its
findings in the status comment until the prompt ran. After a return with `continue`:

1. From the block state: the pending prompt runs again (`implement`, `fix`, or `resume`, with the same
   findings).
2. From the enter state: the prompt `continue` runs, and then the pending prompt when it is `fix` or
   `resume`.
3. When the session of the agent did not start yet: the prompt is always `implement`.
4. When the findings of a pending `fix` or `resume` are missing: the issue goes to the block state, and the
   pending prompt becomes `implement`. So the next `continue` runs `implement`.

The prompt `resume` tells the agent to continue the work of the issue. Its `{{findings}}` is the result of
the action that sends the issue back. For `risk`, it is the risk, the size, and the reasons. The prompt
`resume` is required only when the workflow has an entry of the last row of the table. The jar has an example
file, `prompts/resume.md`.

### 6.3 `build`

The action runs `build.command` in the worktree. It sends `passed` or `failed`. With `failed`, the errors of
the build go to the next `coding` action as `{{findings}}`. With `passed`, it pushes the branch and opens the
pull request. A workflow with `merge-gate` needs a `build` state.

### 6.4 `reviews`

```yaml
    review:
      column: in-review
      action:
        type: reviews
        prompts:
          correctness: prompts/review-correctness.md
          security: prompts/review-security.md
```

- Each key of `prompts` is the name of one review. You choose the names and the number of reviews: one review
  or more. A review name has only small letters, digits, and hyphens.
- The event is `approved` when all the reviews approve. It is `rejected` when one review or more has
  findings. The findings of all the reviews go to `{{findings}}` of the next `coding` action.
- The factory has no built-in review. The jar has four review prompts as an example.

### 6.5 `risk`

The action gives the risk (`low`, `medium`, or `high`), the size (`small` or not small), and the reasons. It
sends `assessed`. The status comment shows all three. The card on the board shows the risk in one line. For example, the status
comment shows:

```
risk: high, not small
- The change edits the authentication filter.
- The change has no test for the expired token.
```

Each new risk result replaces the reasons.

### 6.6 `merge-gate`

```yaml
    merge:
      column: in-review
      action: { type: merge-gate, mode: risk }
```

`mode` is `risk` (the default), `auto-merge`, or `always-ask`.

| Mode | Checks | Then |
|------|--------|------|
| `risk` | The four checks below. | An automatic merge when the risk is `low` and the change is small. Else, the event is `needs-approval`. Without a `risk` state, the event is always `needs-approval`. |
| `always-ask` | The four checks below. | The event is `needs-approval`, until you approve. |
| `auto-merge` | Only the check of an open question. | An automatic merge. |

The four checks:

1. No question of the agent is open.
2. Each review approves.
3. The reviews are for the current files.
4. The build is for the current files.

The checks 2 and 3 apply only when the workflow has a `reviews` state. A workflow with `merge-gate` always has
a `build` state (section 14, rule 2). Only the action `build` pushes the branch and opens the pull request.

A failed check refuses the merge. The issue goes to the block state, with the reason in a note, for
example "the files changed after the reviews".

The events:

- `merged`: the worker merged the pull request.
- `needs-approval`: the merge needs approval.
- `needs-new-reviews`: the target branch changed, and the reviews must run again on the merged files.
- `conflict`: the merge with the target branch has a conflict. The agent must solve it.
- `factory-changed`: the pull request changes `.factory`. This event comes in each mode, also with
  `auto-merge`.

### 6.7 `approval`

The action waits for the label `factory:approve`. It sends `approved`. The label does one of two things. The
state decides which one:

1. **The approval of a merge.** This is an approval state whose `approved` goes to the `merge-gate` state. When you
   add the label, the worker writes the approved files in the status comment. The merge gate then accepts both
   `needs-approval` and `factory-changed` for these files, so it does not ask again. A change of the code
   before the merge removes the approval: a fix, a merge with the target branch, or a change in a live
   conversation. The merge gate then asks again for the new files. The label also works when you add it before
   the issue waits. See the warning in [Issues and labels](issues-and-labels.md).
2. **The approval of a new issue text.** This is the text-change state. The approval only accepts
   the new text. The worker then removes the label at once. It does not approve the merge.

The loader does not check the paths to the merge gate. So a workflow can go from `coding` directly to the
merge gate.

### 6.8 `conversation`

When the issue enters the state, the action opens the live conversation: a hidden tmux session with
`claude --resume` and Remote Control, and a comment with the link on the issue. Each event that moves the issue
there opens the conversation: an Enter, `question`, or another event.

The action ends when you type `/factory:continue` in the conversation, or click Continue on the board. It sends
`continue` when the conversation did not change the code. When the conversation changed the code, the action
commits the change, and then it sends `changed`.

The enter state of `on-any-state` must have this action. The action `conversation` is in one state at
most.

### 6.9 `finish`

The action sets the column of its state, removes the worktree, and removes the label `factory`. It has no
event and no `on`. The workflow must have one state with this action.

### 6.10 A state without an action

The issue waits in the state. The event `continue` comes from one of these:

- The button Continue of the board. The board shows it on each card in a state without an action.
- `/factory:continue` in a live conversation, when the issue goes through the enter state.

## 7. The prompts

The placeholders of each prompt:

| Prompt | When the worker uses it | Placeholders |
|--------|--------------------------|--------------|
| `implement` | The first session of the agent. | `{{issue}}` |
| `fix` | An entry with findings (section 6.2). | `{{findings}}` |
| `resume` | An entry without findings (section 6.2). | `{{findings}}` |
| `continue` | The session of the agent, after a live conversation with you. | none |
| each review | The reviews. | `{{issue}}`, `{{tree}}`, `{{base}}` |
| `risk` | The risk check. | `{{issue}}`, `{{base}}` |

The values of the placeholders:

- `{{issue}}`: the title and the text of the issue.
- `{{findings}}`: the findings of the build, of the reviews, or of the merge conflict, or the result of the
  action that sends the issue back.
- `{{tree}}`: the git tree hash of the files under review.
- `{{base}}`: `origin/` and the target branch, for example `origin/main`.

A prompt can use only the placeholders of its row. The loader refuses another placeholder, for example the
typo `{{isue}}`.

A prompt must keep the contract of its action. The worker reads these files in the folder `.factory-work` of
the worktree:

| Prompt | The file that the agent must write |
|--------|------------------------------------|
| `implement`, `fix`, `resume`, `continue` | `.factory-work/description.md`: the description of the change for the pull request. For a question: `.factory-work/question.md`, and then the agent stops. |
| each review | `.factory-work/review-<name>.json`: `{"verdict": "approve\|reject", "tree": "{{tree}}", "findings": [...], "notes": [...]}` |
| `risk` | `.factory-work/risk.json`: `{"risk": "low\|medium\|high", "small": true\|false, "reasons": [...]}` |

The prompts of the example folder show the full contract. Start from them.

## 8. The limits

```yaml
      limit: { entries: 3, then: blocked }
```

| Key | Required | Description |
|-----|----------|-------------|
| `entries` | yes | The maximum number of entries into the state. A whole number of 1 or more. |
| `then` | yes | The state that the issue goes to at entry `entries + 1`. A state name. The word `previous` is not valid here, because at the limit `previous` is the state itself. |

- `entries` counts each entry of the issue into the state, also the first entry. So `entries: 3` on a
  `coding` state gives the first implementation and two fixes.
- An entry counts only when the action of the state starts. So a return after a text change or a block does
  not count two times.
- At entry `entries + 1`, the issue does not enter the state. It goes to the `then` state.
- Each limit has its own count. The card of the board shows the count of each limit as "Entries".
- When the issue leaves the block state with the event `continue`, the counts of all the limits go back
  to 0.
- When an Enter (see section 5) moves the issue from the `block`
  target, the counts of all the limits also go back to 0.

## 9. The columns and the labels

- Each state must have `column`. A column name has only small letters, digits, and hyphens.
- A column name has 39 characters or less. A GitHub label has 50 characters or less, and the label of a column
  is `factory:is:` and the column name.
- You choose the names of the columns. The board shows them in the order of their first use in the YAML. The
  board writes them with a capital letter and with spaces. For example, `waits-for-you` becomes
  "Waits for you".
- The column "Queued" is always first. It holds the new issues. The name `queued` is reserved: no state can
  use it.
- The GitHub label of the column is `factory:is:<column>`, for example `factory:is:in-review`. The worker
  creates the label on its first use. The label is for display only. The status comment keeps the state.
- The labels of the columns of the default workflow have fixed colors. The label of each other column has the
  color `c5def5`.
- The worker removes the labels `factory:status:<name>` of earlier versions of the factory.
- Issues can run on different versions of `.factory`. The board shows the columns of the newest version that
  loads. A card whose column is not in that version goes to an extra column at the end, with the name of its
  column.

## 10. The behavior at run time

### 10.1 Enter while an action runs

1. You enter (see section 5). The worker sees the label `factory:enter`.
2. The worker stops the running `claude` session or the running build at once, and drops the result of the action. A
   stopped session is not a failed session: the issue does not go to the block state, and a review round
   does not count as failed.
3. The issue moves to the enter state. Its action `conversation` opens the live conversation.
4. `previous` is the state of the stopped action. So `continue: previous` runs that action again from the
   start.

When no action runs, the issue moves to the enter state at once.

### 10.2 `factory:block` while an action runs

1. The worker stops the action at once and drops its result.
2. The issue moves to the block state.
3. `previous` is the state of the stopped action. So `continue: previous` runs that action again from the
   start.

Exception: when the worker already merged the pull request, the worker does not block. The issue continues to the `finish`
state.

### 10.3 An unexpected failure

An unexpected failure has no event, because the action did not end. Examples: git fails in the worktree, the
disk is full, or a defect in the code of the factory.

1. The issue moves to the block state.
2. The note on the issue gives the failure, for example
   "An unexpected failure stopped the issue: ... git worktree add failed".
3. `previous` is the state of the failed action.

The YAML does not write this move.

### 10.4 The retries inside an action

The worker tries again after two types of technical failure. The YAML does not show these retries.
[Configuration](configuration.md) tells how you set them.

- After the last try, the action has an unexpected failure (section 10.3).
- The count of the tries belongs to one run of the action. Each new run of the action starts at 0. So after
  `continue: previous`, the action has all its tries again.

### 10.5 A change of the issue text

1. The worker keeps a hash of the issue text when it takes the issue.
2. Before each action that uses `{{issue}}` (`coding`, `reviews`, and `risk`), the worker compares the hash.
3. When the text changed, the issue moves to the text-change state. The action `approval` of this
   state starts. First it removes an older `factory:approve`, because only an approval after the change
   counts.
4. You add `factory:approve`. The worker accepts the new text. The event `approved` goes to `previous`, so
   the action runs with the new text.

### 10.6 Start again

The button "Start again" of the board and the label `factory:start-again` start the issue again from the
beginning.

1. The button and the label work only when the issue is in the block state and has no live conversation.
   In the other states, the worker removes the label, writes a note, and does nothing.
2. The worker discards the earlier work of the issue. It resets the status comment to the start state, with a new
   agent. The issue takes the current version of `.factory`, so a correction of `.factory` reaches
   it. All the limit counts are 0. When no version of `.factory` loads, Start again has no effect.
3. The action `prepare` makes the worktree and the branch again.
4. Exception: when GitHub already merged the pull request of the issue, the issue goes to the `finish` state.
   So the worker never makes the change two times.

The YAML does not name this move.

### 10.7 What the worker does on a move

An event only moves the issue. But the worker applies these rules on a move. The YAML does not write them.

1. **The label and the status comment.** Each move changes the label `factory:is:<column>`, and changes the status
   comment.
2. **`previous`.** Each move toward a state with an action that does not wait for you sets `previous`.
3. **The limit.** At the limit, the issue goes to the `then` state, and not into the state.
4. **The reset of the limits.** See section 8.
5. **The moves of `on-any-state`.** `factory:block`, Enter, and a change of the issue text move the issue from
   any state.
6. **`factory:block` stops the action.** See section 10.2.
7. **An Enter stops the action.** See section 10.1.
8. **The moves without an `on` entry.** A refused merge and an unexpected failure move the issue to the
   block state. The note gives the reason.
9. **A change of the code makes the results old.** A fix, a merge with the target branch, and a change in a live
   conversation change the code. The worker then removes the results of the build, the reviews, and the risk
   check. It also removes the approval of the merge.

### 10.8 A new assignee of the issue

The GitHub assignee of the issue decides which factory works on it. See
[More than one factory](multiple-factories.md). When the assignee changes, the factory of the new assignee writes a
new status comment. The issue is then in the start state of the current version of `.factory`.

1. When the branch `factory/issue-<n>` is on GitHub, the new factory continues that branch, and it keeps the pull
   request. The action `prepare` then goes to the first `build` state (see section 6.1).
2. An earlier status comment in a state without an action goes to the block state.
3. An earlier status comment that waits for the approval of a new issue text goes to the text-change state.
4. An earlier status comment that waits for a merge approval keeps its wait. The `merge-gate` event that caused the
   wait selects the state in the new workflow, for example `waits-for-approval`. This needs the pushed branch. After the approval,
   the issue goes to the start state, so the worker makes the worktree from the branch and builds again.
5. The new factory does not keep a wait in another `approval` state, which no `merge-gate` state enters. It
   continues from the start state.
6. The new factory does not keep a wait in a state with the action `conversation`, because the session stays on
   the earlier machine.

The YAML does not name these moves.

## 11. The status comment

The status comment of the worker on each issue keeps the **record**: the data of the issue. The
record holds the name of the current state, the value of `previous`, and the count of each state with a `limit`. It
also holds the results that the merge gate checks.

### 11.1 Issues of an earlier version

A record of an earlier version of the factory has no commit of `.factory`. It runs with the default workflow of the
jar. The worker converts its old fields, the phase and the wait reason, to a state of the default workflow. For example, the wait
reason `MERGE_NEEDS_APPROVAL` becomes the state `waits-for-approval`.

## 12. The build, the tools, and the target branch

### 12.1 `build`

```yaml
build:
  command: ./mvnw verify
  environment: { LANG: C.UTF-8 }
```

| Key | Required | Description |
|-----|----------|-------------|
| `command` | yes | The command that the action `build` runs in the worktree. |
| `environment` | no | Variables for the build. |

The file `worker.yaml` of each machine can also give `build.environment`, for example `JAVA_HOME`. A value
of the machine replaces a value of `.factory` with the same name.

The build command is the first check of each change. It runs before the reviews. Select a command that obeys these
rules:

- It compiles the program and runs the tests. A command without tests can pass a change that breaks the program.
- It ends with the exit code 0 only when the build and the tests pass. Each other exit code is the event `failed`.
- It ends without the help of a person. The worker gives the command no input. The command has no time limit.
- It does not change the files of the repository, except the files that git ignores. Else, the worker blocks the
  issue.

### 12.2 `claude`

```yaml
claude:
  allowed-tools: [Read, Edit, Write, Glob, Grep, "Bash(git diff*)", "Bash(./mvnw *)"]
```

`allowed-tools` (required, not empty) is the list of tools that the agent can use without a question. The
worker gives the list to `claude` with `--allowedTools`. The format of each item is the format of `claude`,
for example `Bash(./mvnw *)`.

The file `worker.yaml` of each machine gives `claude.disallowed-tools`. These tools stay refused, also
when `allowed-tools` names them.

### 12.3 `merge`

```yaml
merge:
  target-branch: main
```

`target-branch` (required) is the branch that the pull requests of the factory go to. The worker never
pushes to the default branch or to a tag directly. It merges a pull request into the target branch.

## 13. How the worker reads `.factory`

With `--definition`, the worker reads a definition folder in place of `.factory`. See section 17.

- At the start of each poll, the worker fetches the default branch in its clone. It reads `.factory` of the
  newest commit with git, never from the worktree of an issue.
- A new issue gets the newest version that loads. Its status comment keeps the commit of that version.
- Each issue runs with the workflow, the prompts, the build, the tools, and the target branch of its own
  version.

### 13.1 When `.factory` does not load

The worker checks `.factory` at each poll. When the newest version does not load:

- The worker keeps the last version that loads. The issues in work continue with their own version.
- The board shows the reason at the top.
- After a restart, the worker reads up to 20 earlier commits of `.factory`, the newest first. It takes the first
  one that loads.
- When no version loads, the worker takes no new issue.

## 14. The rules of the loader

The loader refuses a definition that breaks one of these rules. Each message starts with `factory.yaml: `.
When the error is in a state or a key, the message names it.

| Rule | Example message |
|------|-----------------|
| 1. `version` is `1`. No key is unknown, at each level. | `factory.yaml: version 2 is not supported`<br>`factory.yaml: the state build has the unknown key step` |
| 2. `start` exists, and its action is `prepare`. A state with `finish` exists. When a state has `merge-gate`, a state with `build` exists. | `factory.yaml: the start state build has the action build; it needs the action prepare`<br>`factory.yaml: no state has the action finish`<br>`factory.yaml: the state merge has the action merge-gate, but no state has the action build; the action build pushes the branch and opens the pull request` |
| 3. Each action type is in one state at most, except `approval`. | `factory.yaml: the states build and build-again both have the action build; an action other than approval is in one state at most` |
| 4. Each state has `column`. No column is `queued`. | `factory.yaml: the state build has no column`<br>`factory.yaml: the state build has the column queued, which the factory keeps for new issues` |
| 5. `on-any-state` has `block`, `enter`, and `issue-text-changed`. Each one names a state that exists. The target of `issue-text-changed` has the action `approval`. The target of `enter` has the action `conversation`. The target of `block` is a state with no action. | `factory.yaml: key workflow.on-any-state.enter is missing`<br>`factory.yaml: on-any-state block goes to the state stuck, which does not exist`<br>`factory.yaml: the enter target blocked needs the action conversation`<br>`factory.yaml: the block target build has the action build; the block target must have no action` |
| 6. Each `on` maps each event of the action, and no other event. | `factory.yaml: the state build has no next state for the event failed`<br>`factory.yaml: the state build has the event crashed, but the action build has no such event` |
| 7. Each value of `on` is a state that exists, or `previous`. Each `limit.then` is a state that exists. | `factory.yaml: the state build goes to the state deploy for the event passed, but deploy does not exist`<br>`factory.yaml: the state implement goes to the state stuck at its limit, but stuck does not exist`<br>`factory.yaml: the state implement has the limit then previous; the then of a limit must be a state name` |
| 8. Each state is reachable from `start`, through `on` and `limit.then`. The targets of `on-any-state` count as reachable. | `factory.yaml: no state goes to the state security-scan` |
| 9. From each state, a path goes to the `finish` state. `limit.then` counts as a path. `previous` counts as a path back to each state with an action. | `factory.yaml: the state review has no path to the state done` |
| 10. Each cycle has a state with a `limit`. A cycle through a state that waits for you (a state without an action, or with `approval` or `conversation`) needs no limit. | `factory.yaml: the cycle implement, build has no limit` |
| 11. Each action has exactly the prompts of its type, and each prompt file exists. `resume` is required only when the workflow has an entry without findings into the `coding` state (section 6.2). | `factory.yaml: the state implement needs the prompt fix`<br>`factory.yaml: the state implement: prompt file prompts/fix.md does not exist`<br>`factory.yaml: the state implement needs the prompt resume, because the state risk goes to it with the event assessed` |
| 12. Each prompt uses only the placeholders of its prompt (section 7). | `factory.yaml: the prompt prompts/fix.md of the state implement has the placeholder {{isue}}; the prompt fix knows only {{findings}}` |
| 13. `build.command`, `claude.allowed-tools`, and `merge.target-branch` are present. | `factory.yaml: key build.command is missing` |
| 14. Each limit is in its state. The format has no root key `limits`. | `factory.yaml: unknown key limits` |

The loader also refuses these values:

- A `limit.entries` that is not a whole number of 1 or more:
  `factory.yaml: key workflow.states.implement.limit.entries must be an integer of 1 or more`.
- An unknown `mode` of `merge-gate`: `factory.yaml: the state merge has the unknown mode yolo`.
- A column name or a review name with characters other than small letters, digits, and hyphens.
- A column name with more than 39 characters.
- The state name `previous`: `factory.yaml: the state name previous is reserved`.

## 15. The default workflow

The default workflow is the `factory.yaml` of the jar, in
[`src/main/resources/factory/`](../src/main/resources/factory/). It has four reviews and a risk check. It
merges a small change with a low risk automatically.

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
  environment: { LANG: C.UTF-8 }
claude:
  allowed-tools: [Read, Edit, Write, Glob, Grep, "Bash(git status*)", "Bash(git diff*)", "Bash(git log*)",
                  "Bash(git show*)", "Bash(./mvnw *)", "Bash(mvn *)"]
merge:
  target-branch: main
repositories: []
```

The loader rules accept this workflow:

- The cycles `implement` and `build`, `implement` and `review`, and the cycle of `implement` to itself go
  through `implement`, which has a limit.
- The cycle `merge`, `build`, `review`, `risk` goes through `review`, which has a limit.
- The cycles through `waits-for-approval`, `factory-change-approval`, `text-changed`, `live`, and `blocked`
  wait for you.

## 16. Other workflows

A workflow without reviews and without risk, with an automatic merge. The state `build` has a limit, because
the cycle `build`, `merge` has no other state with a limit (rule 10).

```yaml
version: 1
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
build:
  command: ./mvnw verify
claude:
  allowed-tools: [Read, Edit, Write, Glob, Grep, "Bash(./mvnw *)"]
merge:
  target-branch: main
```

A review state that runs up to ten times, with one security review only. Put it between `build` and `merge`
of a workflow:

```yaml
    review:
      column: review
      action: { type: reviews, prompts: { security: prompts/review-security.md } }
      limit: { entries: 10, then: blocked }
      on: { approved: merge, rejected: code }
```

## 17. A definition outside the repository

You can keep the definition in a definition folder outside the repository. Give the folder with the option
`--definition` of `run`:

```
rossumworks run --definition <folder>
```

With the jar, write `java -jar rossumworks-<version>.jar` in place of `rossumworks`.

The definition folder has the same content as `.factory`: `factory.yaml` and the prompt files. It does not need
git. Without `--definition`, the worker reads `.factory` of the default branch. The worker refuses a value of
`--definition` that is empty or that starts with `--`.

### 17.1 The versions

At the start of each poll, the worker reads the definition folder. When the content changes, the worker makes a
new version: a local version. A local version is `local:` and the 64 characters of the SHA-256 hash of the
content. The board shows a short form, for example `local:8be41d0`.

The worker keeps each version that loads as a definition copy in `<work folder>/definitions/<hash>/`. The
status comment of an issue keeps its version. The issue reads its definition from its copy, never from the
definition folder. So an edit of the folder reaches only the new issues. To give an edit to an issue in the block
state, use Start again (section 10.6).

The worker never deletes a definition copy.

### 17.2 When the definition folder does not load

- The worker keeps the last version that loads, and the board shows the reason at the top.
- After a restart, the worker takes the newest definition copy that loads. The newest copy is the copy with the
  newest modification time. A copy keeps the time of its first write, also when the same content comes back
  later.
- When no version loads, the worker takes no new issue.

A file that is not valid UTF-8 is an error of the definition. The definition then does not load.

A symbolic link below the definition folder is an error. The worker cannot read the folder, so it keeps the
current version. At the start, it then takes the newest definition copy that loads. The worker reads the folder
again at each poll. When you remove the link, the worker loads the folder again.

The worker skips each hidden entry, for example `.git`, at each level. So you can keep the definition folder in a
git repository: a commit does not make a new version, and the copies do not contain `.git`. A prompt file cannot
be a hidden entry.

### 17.3 An edit that is not complete

The worker can read the folder while your edit is not complete. When the content that is not complete loads, new issues
can start with it. To prevent this, edit a copy of the folder. When the copy is complete, rename it to the
name of the definition folder.

### 17.4 The checks of the status comment

A person with write access to the repository can edit the status comment. So the worker checks the version of
each record:

| Mode of the worker | Version in the record | What the worker does |
|---|---|---|
| Without `--definition` | A commit | It uses the commit when the commit is on the default branch. |
| Without `--definition` | A `local:` version | It blocks the issue. |
| With `--definition` | A `local:` version | It uses the version when its definition copy exists. |
| With `--definition` | A commit | It blocks the issue. |
| Both | No version | It uses the built-in definition. |

When another person takes over the issue, their factory writes a new record with its own current version. See
[More than one factory](multiple-factories.md). When the same account runs the worker on a machine without the
definition copy, the worker blocks the issue.

With `--definition`, a pull request that changes `.factory` still ends with the event `factory-changed`, and the
merge needs your approval. The worker does not use that `.factory`. A change of the definition folder never gives
`factory-changed`, because the folder is not in the repository.
