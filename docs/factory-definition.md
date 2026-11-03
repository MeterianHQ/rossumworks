# The factory definition

This document explains the folder `.factory` of a repository. The folder tells the factory how to work on the
issues of this repository: the workflow, the prompts of the agent, the build, the limits, and the merge target.

The worker reads `.factory` from the default branch of the repository. Each issue keeps the version of
`.factory` that was current when the work on the issue started. A change of `.factory` has no effect on the
issues in work. A pull request that changes `.factory` always waits for your approval before the merge.

## 1. The folder

```
.factory/
  factory.yaml          the main file
  prompts/              the prompt files that factory.yaml names
    implement.md
    fix.md
    ...
```

The main file is `.factory/factory.yaml`. The paths of the prompt files are relative to the folder `.factory`.
You can put the prompt files in any subfolder of `.factory`.

The jar of the factory contains a complete example:
[`src/main/resources/factory/`](../src/main/resources/factory/). To start, copy this folder to `.factory` in
your repository, and then change it.

## 2. The main file

`factory.yaml` has these sections. The loader refuses a key that it does not know, at each level.

| Key | Required | Description |
|-----|----------|-------------|
| `version` | yes | The version of the format. The only value is `1`. |
| `workflow` | yes | The states and the loops of the workflow. See section 3. |
| `limits` | no | Named numbers. A loop uses a limit by its name. See section 6. |
| `build` | yes | The build command. See section 7. |
| `claude` | yes | The tools that the agent can use. See section 8. |
| `merge` | yes | The merge target. See section 9. |
| `repositories` | no | A list of text. Version 1 reads it, but does not use it. |

A short example:

```yaml
version: 1
workflow:
  start: prepare
  states: { ... }
  loops: { ... }
limits:
  fix-loops: 2
build:
  command: ./mvnw verify
claude:
  allowed-tools: [Read, Edit, Write, Glob, Grep, "Bash(./mvnw *)"]
merge:
  target-branch: main
```

## 3. The workflow

The workflow is a state machine. An issue is always in one state. Each state runs one step. The step ends
with an outcome, for example `passed` or `failed`. The state maps each outcome to a transition. The
transition tells the factory what to do next.

```yaml
workflow:
  start: prepare        # the state of a new issue
  states:
    <state name>: { ... }
  loops:
    <loop name>: { ... }
```

- `start` (required): the name of the first state. Its step must have the type `prepare`.
- `states` (required): the states, by name. You choose the names.
- `loops` (optional): the loops, by name. Version 1 allows one loop or none.

### 3.1 A state

```yaml
    build:
      status: in-progress
      step: { type: build }
      on: { passed: review, failed: fix-loop }
```

| Key | Required | Description |
|-----|----------|-------------|
| `status` | yes | The status that GitHub shows while the issue is in this state: `in-progress`, `in-review`, or `done`. The factory sets the label `factory:status:<status>`. |
| `step` | yes | The step of the state: `type`, and `prompts` for some types. See section 4. |
| `on` | yes, except for `finish` | One transition for each outcome of the step type. See section 3.2. |

The rules of `on`:

- Each outcome of the step type must have a transition.
- An outcome that the step type does not have is an error.
- A state with the step type `finish` has no `on`.

### 3.2 A transition

A transition has one of five forms.

| Form | Example | Effect |
|------|---------|--------|
| Go to a state | `passed: review` | The issue goes to the state `review` and gets the status of that state. |
| Go to a state, keep the status | `merged: { to: done, keep-status: true }` | The issue goes to the state `done`, but keeps its current status. |
| Enter a loop | `failed: fix-loop` | The issue enters the loop `fix-loop`. See section 3.3. |
| Wait for you | `needs-approval: { wait: merge-needs-approval }` | The issue gets the status "Waits for you", with the reason. |
| Block | `failed: block` or `{ block: fix-loop-limit }` | The issue gets the status "Blocked", with the reason. The word `block` alone gives the reason `failure`. |

A single word is a state name, a loop name, or `block`. The factory first looks for a loop with this name,
and then for a state.

The reasons of `wait` and `block`:

| Reason | Use |
|--------|-----|
| `question` | Only on the outcome `question` of the step type `agent`. The factory writes the question of the agent on the issue and opens a live conversation. |
| `merge-needs-approval` | The merge waits until you add the label `factory:approve`. |
| `factory-change-needs-approval` | The pull request changes `.factory`. The merge waits until you add the label `factory:approve`. |
| `fix-loop-limit` | Use it in the exit of a loop. |
| `failure` | A general failure. |

The loader also accepts `blocked-by-owner`, `issue-text-changed`, and `live-conversation`. The factory itself
uses these reasons. Do not use them in a transition: no action of the owner ends a wait with these reasons.

### 3.3 A loop

A loop sends the issue back to an earlier state with the findings of the step, for example the errors of the
build or the findings of a review. The factory counts each entry of the issue into the loop.

```yaml
  loops:
    fix-loop: { to: implement, limit: fix-loops, exit: { block: fix-loop-limit } }
limits:
  fix-loops: 2
```

| Key | Required | Description |
|-----|----------|-------------|
| `to` | yes | The state that the loop goes to. |
| `limit` | yes | The name of a number in `limits`. |
| `exit` | yes | The transition when the count is more than the limit. It cannot enter a loop. |

With `fix-loops: 2`, the issue can enter the loop two times. At the third entry, the exit applies.

## 4. The step types

Version 1 has seven step types. The workflow must have exactly one state for each type.

| Type | What the step does | Outcomes | Prompts |
|------|--------------------|----------|---------|
| `prepare` | Creates the branch and the worktree of the issue. | `done` | none |
| `agent` | Runs a `claude` session that changes the code. | `changed`, `no-change`, `question`, `conflict-left`, `failed` | `implement`, `fix`, `continue` |
| `build` | Runs the build command in the worktree. | `passed`, `failed` | none |
| `reviews` | Runs four `claude` reviews of the change. | `approved`, `rejected` | `correctness`, `security`, `architecture`, `quality` |
| `risk` | Runs a `claude` session that gives the risk and the size of the change. | `assessed` | `risk` |
| `merge-gate` | Merges the change into the merge target, or waits for you. | `merged`, `needs-approval`, `needs-new-reviews`, `conflict`, `factory-changed` | none |
| `finish` | Sets the status done, removes the worktree, and removes the label `factory`. | none | none |

The outcomes:

- `agent`:
  - `changed`: the agent made a change.
  - `no-change`: the agent made no change.
  - `question`: the agent asks you a question.
  - `conflict-left`: conflict markers stay in the files after a merge conflict.
  - `failed`: the session failed.
- `build`: `passed` or `failed`. With `failed`, the errors of the build go to the agent through the loop.
- `reviews`: `approved` when all four reviews approve. `rejected` when one or more reviews have findings. The
  findings go to the agent through the loop.
- `merge-gate`:
  - `merged`: the merge is done.
  - `needs-approval`: the risk is not low, or the change is not small, so the merge needs your approval.
  - `needs-new-reviews`: the target changed, and the reviews must run again on the merged files.
  - `conflict`: the merge with the target has a conflict. The agent must solve it.
  - `factory-changed`: the change touches `.factory`.

A merge is automatic only when the risk is `low` and the change is small. Version 1 has this rule in the code,
not in `.factory`.

## 5. The prompts

`step.prompts` maps each prompt name to a file:

```yaml
      step:
        type: agent
        prompts: { implement: prompts/implement.md, fix: prompts/fix.md, continue: prompts/continue.md }
```

The step needs exactly the prompts of its type (see the table of section 4). A missing prompt, an unknown
prompt, or a missing file is an error.

| Prompt | When the factory uses it | Placeholders |
|--------|--------------------------|--------------|
| `implement` | The first agent session of the issue. | `{{issue}}` |
| `fix` | An agent session after the loop: the build failed, a review rejected, or a merge has a conflict. | `{{findings}}` |
| `continue` | An agent session after a live conversation with you. | none |
| `correctness`, `security`, `architecture`, `quality` | The four reviews. | `{{issue}}`, `{{tree}}`, `{{base}}` |
| `risk` | The risk assessment. | `{{issue}}`, `{{base}}` |

The values of the placeholders:

- `{{issue}}`: the title and the text of the issue.
- `{{findings}}`: the findings of the build, of the reviews, or of the merge conflict.
- `{{tree}}`: the git tree hash of the files under review.
- `{{base}}`: `origin/` and the merge target, for example `origin/main`.

A placeholder that the prompt does not support makes the step fail, and the issue blocks.

A prompt must keep the contract of its step. The factory reads these files in the folder `.factory-work` of
the worktree:

| Prompt | The file that the agent must write |
|--------|------------------------------------|
| `implement`, `fix`, `continue` | `.factory-work/description.md`: the description of the change for the pull request. For a question: `.factory-work/question.md`, and then the agent stops. |
| each review | `.factory-work/review-<name>.json`: `{"verdict": "approve\|reject", "tree": "{{tree}}", "findings": [...], "notes": [...]}` |
| `risk` | `.factory-work/risk.json`: `{"risk": "low\|medium\|high", "small": true\|false, "reasons": [...]}` |

The prompts of the example folder show the full contract. Start from them.

## 6. The limits

`limits` maps names to whole numbers:

```yaml
limits:
  fix-loops: 2
```

A loop uses a limit by its name. In version 1, only the loop uses `limits`.

## 7. The build

```yaml
build:
  command: ./mvnw verify
  environment: { LANG: C.utf8 }
```

| Key | Required | Description |
|-----|----------|-------------|
| `command` | yes | The command that the step `build` runs in the worktree. |
| `environment` | no | Variables for the build. |

The worker configuration of each machine can also give `build.environment`, for example `JAVA_HOME`. A value
of the machine replaces a value of `.factory` with the same name.

## 8. Claude

```yaml
claude:
  allowed-tools: [Read, Edit, Write, Glob, Grep, "Bash(git diff*)", "Bash(./mvnw *)"]
```

`allowed-tools` (required, not empty) is the list of tools that the agent can use without a question. The
factory gives the list to `claude` with `--allowedTools`. The format of each item is the format of `claude`,
for example `Bash(./mvnw *)`.

The worker configuration of each machine gives `claude.disallowed-tools`. These tools stay refused, also
when `allowed-tools` names them.

## 9. The merge

```yaml
merge:
  target-branch: main
```

`target-branch` (required) is the branch that the pull requests of the factory go to. The factory never
pushes to the default branch or to a tag directly. It merges a pull request into the target branch.

## 10. When `.factory` does not load

The worker checks `.factory` at each poll. When the newest version does not load:

- The worker keeps the last version that loads. The issues in work continue with their own version.
- The web page shows the reason at the top.
- After a restart, the worker looks back through the history of `.factory` for the newest version that loads.
- When no version loads, the worker takes no new issue.

The message gives the file and the key, for example:
`factory.yaml: state build has no transition for the outcome failed`.

## 11. The rules of version 1

The loader refuses a definition that breaks one of these rules:

- `version` is `1`.
- The start state exists, and its step type is `prepare`.
- The workflow has exactly one state for each of the seven step types.
- Each state has one transition for each outcome of its step type, and no other transition.
- Each transition goes to a state or a loop that exists.
- The workflow has one loop or none. The loop goes to a state that exists, names a limit of `limits`, and its
  exit does not enter a loop.
- Each step has exactly the prompts of its type, and each prompt file exists.
- `build.command`, `claude.allowed-tools`, and `merge.target-branch` are present.
- No key is unknown.

So in version 1 you can change the names of the states, the statuses, the transitions, the loop and its
limit, the prompts, the build, and the tools. You cannot add a step type, run a type two times, or add a
second loop.
