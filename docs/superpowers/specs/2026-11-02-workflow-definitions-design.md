# Workflow definitions in each repository: design

Status: approved by the owner on 2026-11-02, after the brainstorming. This
document adds to `2026-11-01-factory-design.md`. Where the two documents
differ, this document is the newer decision.

## 1. Intent

The owner sees the factory as a workflow engine with a state machine.
GitHub is the model: it holds the state of each issue. The worker is the
engine: it runs the allowed transitions. The web page shows the states, and
it offers only the transitions that are possible now.

A large part of the future work is the configuration of different workflows.
So the workflow must be a definition and not Java code. Each repository has
its own definition, in a folder `.factory` at the root of the repository.
That folder configures everything about the factory of that repository.

## 2. Goals, non-goals, anti-goals

Goals:

- The worker runs a workflow from a YAML definition in `.factory`.
- The first definition is today's workflow, with all its loops, retries,
  and waits. The behavior of the factory does not change.
- `.factory` holds everything that configures the factory for the
  repository. The configuration of the worker shrinks to the facts of the
  machine.
- One command creates a worker. It asks questions with default values, or
  finds the values by itself.
- When the repository has no `.factory`, the command writes a reasonable
  default and opens a PR for it.

Non-goals of this step:

- New workflows. The format must leave room for them, but this step ships
  only today's workflow.
- The work across several repositories (plan 4). `.factory` can list the
  other repositories, but the worker does not use them yet.
- A graphical editor of workflows.

Anti-goals:

- Code from the repository that runs in the worker. The YAML only names
  step types that the worker already has.
- A change of behavior in the refactoring. If a test of today changes its
  expected result, the refactoring is wrong.
- A definition that a running issue cannot finish. An issue never meets a
  state that its version of the workflow does not know.
- Many questions at the creation of a worker. A value that the command can
  find, it finds.

## 3. Constraints

- All the constraints of `2026-11-01-factory-design.md` section 3 still
  apply. The subscription only, never an API key, and never the Agent SDK.
  Fail closed. The factory never pushes to `main` or a tag. Tests never
  reach GitHub, a real token, the real `claude`, or the default tmux
  server.
- The owner said: "This is a personal tool. I do what I want." So `.factory`
  can configure everything. The
  design does not protect the owner from their own repository.
- Java 21, Maven, Jackson for YAML, as today.
- Simplified Technical English in the docs, the comments, and the texts of
  the page.

## 4. The `.factory` folder (owner decisions)

### 4.1 What it holds

`.factory` holds everything that configures the factory for the
repository:

- the workflow;
- the prompts of the agents;
- the build command and its environment;
- the limits of the work on an issue, for example the fix loops;
- the merge rules: the target branch, and when a merge is automatic;
- the other repositories of the factory (for plan 4).

### 4.2 The configuration of the worker

The configuration of the worker keeps only what the repository cannot know:

- the starting repository, for example `MeterianHQ/heidi-fix`;
- the work directory;
- the settings of this machine: the `claude` command, the parallel issues,
  the poll interval, the usage threshold, the web page (address, port,
  password hash), and the terminal command of live mode
  (`live.terminal-command`, from PR #12);
- the isolation mode, the gatekeeper directory, and the live directory;
- the build variables of this machine, for example `JAVA_HOME`. The worker
  adds them to the build environment of `.factory`. A value of the machine
  replaces a value of `.factory` with the same name;
- the tools that the agents must never use (`claude.disallowed-tools`, for
  example `git push` and `gh`). This list keeps the rule "the factory
  never pushes to `main`" true.

The worker clones the repositories into the work directory by itself. A
path to a local clone is not a setting any more.

My calls:

- The parallel issues, the poll interval, and the usage threshold stay in
  the worker, because they describe the use of this machine and of the
  subscription, not the work of the repository.
- The allowed tools of the repository (`claude.allowed-tools`, for example
  `Bash(./mvnw *)`) go into `.factory`, because they depend on the build of
  the repository.
- `prompts-directory` goes away: the prompts are in `.factory/prompts/`.

### 4.3 Where the worker reads it (owner decision)

The worker reads `.factory` from the default branch of the starting
repository. It reads it again when that branch gets a new commit.

Each issue keeps the version that it started with. The record of the issue
keeps the commit of that version. Only new issues get a new version. So a
running issue never meets a state or a step that its workflow does not know.

My call on the parts: an issue keeps the workflow, the prompts, the build,
the allowed tools, the limits, and the merge target of its start version.
The `.factory` has no list of accounts. GitHub permissions control the
labels: only an account with triage access or more can add a label. The
loader refuses the key `accounts` as an unknown key.

My calls:

- The worker reads the definition with `git show <commit>:.factory/...` in
  its own clone, never from the worktree of an issue. So a change of
  `.factory` in the PR of an issue has no effect on that issue.
- A definition that does not load (a YAML error, an unknown step type, a
  state with no transition) does not replace the current version. The
  worker keeps the last good version, writes a warning, and shows it on the
  page. Fail closed: with no good version at all, the worker takes no new
  issue.
- A record from before this change has no version commit. It runs with the
  built-in definition of today's workflow (section 5.4).

### 4.4 A change of `.factory` in the PR of an issue (owner decision)

When the PR of an issue changes a file in `.factory`, the merge gate always
waits for the approval of the owner, also when the risk is low and the
change is small. The wait reason says that the PR changes the factory. A
change of the factory itself is always a decision of the owner.

### 4.5 The scratch folder of each worktree (my call)

Today the worker and the agents use `.factory` in each worktree as a
scratch folder: `question.md`, `description.md`, `fix-prompt.md`,
`review-<kind>.json`, and `risk.json`. Git does not commit it. This name
now collides with the definition folder.

The scratch folder moves to `.factory-work` in each worktree. Git does not
commit it. The prompts name the new paths. There is no move of old files,
because no worktree of the old form exists at the upgrade (section 9).

## 5. The workflow definition

### 5.1 The model

A workflow is a state machine:

- A **state** has a name, a **status** of the board (Queued, In progress,
  In review, Waits for you, Blocked, Done), and one **step**.
- A **step** has a **type** that the worker knows, and its parameters, for
  example the prompt of an agent.
- Each step ends with an **outcome**, for example `passed`, `failed`,
  `question`, or `rejected`. The **transitions** of the state map each
  outcome to the next state.
- A **loop** is a transition back to an earlier state, with a **limit**.
  When the limit is reached, the transition goes to its exit state. The
  record counts each loop.
- A **wait** ends only on a request of the owner, for example the label
  `factory:approve`, or Continue after a live conversation.

My calls on states:

- A state has a free name, so that a later workflow can add new states.
  The record keeps the name of the current state in place of the enum
  `Phase`. The status comment shows the name of the state where it shows
  the phase today ("phase IMPLEMENT").
- The states of the built-in definition have the names of today's phases:
  `prepare`, `implement`, `build`, `review`, `risk`, `merge`, `done`. A
  record from before this change maps its phase to the state of the same
  name.
- A wait, Blocked, and the fix-loop limit are not states. As today, they are
  a status and a wait reason on the current state. An outcome can map to
  `wait: <reason>` or `block`, and an approval or a Continue continues the
  current state. So `ApprovalCheck`, live mode, and the restart rules keep
  their logic. The code that tests a phase value today (for example
  `Worker`, `LiveMode`, `RestartPreparation`, `IssueTextCheck`,
  `FinalStepResult`, `ApprovalCheck`, `MergedIssue`) tests the step type of
  the current state instead.

### 5.2 The step types of the first version

The step types are the parts of today's code, without change of
behavior:

| Step type | Today's code | Outcomes |
|---|---|---|
| `prepare` | `IssueWorkflow.prepare` | `done` |
| `agent` (with a prompt) | `IssueWorkflow.implement` and `ImplementPrompt` | `changed`, `no-change`, `question`, `conflict-left`, `failed` |
| `build` | `IssueWorkflow.build` and `PullRequestPublisher` | `passed`, `failed`, `not-forwarded` |
| `reviews` (a list of review kinds, run in parallel) | `ReviewRound` | `approved`, `rejected`, `session-failed` |
| `risk` | `IssueWorkflow.assessRisk` | `assessed`, `failed` |
| `merge-gate` | `MergeGate`, `OwnerApproval` | `merged`, `needs-approval` |
| `finish` | `IssueWorkflow.finish`, `MergedIssue` | `done` |
| `wait` (for a request of the owner) | `ApprovalCheck`, `IssueTextCheck`, live mode | the request that ends it |

The guards inside a step stay in the code of the step. Examples: the check
for a change of the files by a review, the conflict resolution, the check
of the forward, and the check of the issue text before each phase. A
guard can end the step with the outcome `failed` and a reason, as today.

The rules that apply to every state stay in the engine: live mode (Enter
and Continue), Block, the check for an unknown session, the rate limit of
GitHub, the usage limit, and the stop of the worker.

### 5.3 Today's workflow as a definition

The first definition must express exactly today's sequence:

- `prepare` → implement.
- implement (`agent` with the implement or fix prompt) → build. A
  question → a wait for the owner with a live conversation. No change and
  no unbuilt commit → Blocked.
- build → reviews. A failed build → the fix loop. A forward that GitHub
  does not show → a retry, then Blocked, as `ForwardCheckFailure` does.
- reviews (correctness, security, architecture, quality) → risk. A
  rejection → the fix loop. A failed review session → the same reviews
  again, at most 2 times, then Blocked.
- risk → the merge gate.
- The merge gate → finish when the merge is automatic. Else a wait for the
  approval of the owner, then the merge.
- The fix loop → implement with the fix prompt, at most `fix-loops` times
  (default 2). Then the issue is Blocked with the reason "fix-loop limit".
- finish → Done.

The retry of the forward and the retry of a failed review session stay
inside the code of the step (section 5.2). The definition does not name them.

The names of the states, the statuses, the wait reasons, and the texts of
the status comment stay the same, so the page and the records do not
change.

### 5.4 The built-in definition

The jar contains today's definition and today's prompts as the built-in
version. The create command copies them into a new `.factory`. A record
from before this change runs with the built-in version.

### 5.5 Format

YAML, read with Jackson. My call on the shape, which the builder can
change if a test shows a better one:

```yaml
version: 1
workflow:
  start: prepare
  states:
    prepare:
      status: in-progress
      step: { type: prepare }
      on: { done: implement }
    implement:
      status: in-progress
      step: { type: agent, prompt: prompts/implement.md, fix-prompt: prompts/fix.md }
      on: { changed: build, question: { wait: question }, no-change: block, failed: block }
    build:
      status: in-progress
      step: { type: build }
      on: { passed: reviews, failed: fix-loop, not-forwarded: build }
    # ...
  loops:
    fix-loop: { to: implement, limit: fix-loops, exit: { wait: fix-loop-limit } }
limits:
  fix-loops: 2
build:
  command: ./mvnw verify
  environment: { LANG: C.utf8 }
claude:
  allowed-tools: [Read, Edit, Write, Glob, Grep, "Bash(./mvnw *)"]
merge:
  target-branch: main
repositories: []
```

The prompts are files in `.factory/prompts/`. The paths in the definition
are relative to `.factory`.

## 6. The create command (owner decisions)

`claude-ai-factory create` creates a worker. It takes parameters, or asks
questions with default values. It finds a value by itself where it can.

My calls on the questions and their defaults:

| Value | Default |
|---|---|
| The starting repository | the GitHub repository of the current folder, from `git remote` |
| The work directory | `~/.claude-ai-factory/<owner>-<name>` |
| The GitHub account | found from `gh auth token`; no question |
| The `claude` command | found on the `PATH`; no question |
| The web page | `127.0.0.1:8090`; the command asks for the password and writes only its hash |
| The terminal command | `gnome-terminal --` when it is installed |

When the starting repository has no `.factory` on its default branch:

1. The command writes a default `.factory`: the built-in workflow, the
   built-in prompts, and the limits of today.
2. It fills in the values that it can find. For example, the build command
   is `./mvnw verify` when the repository has `mvnw`, and `npm test` when it
   has `package.json`.
3. It shows a summary of the files and asks for a confirmation.
4. After a yes, it pushes the branch `factory/setup` and opens a PR. It
   assigns the PR to the owner.
5. The worker starts after the merge of the PR. Until then, it waits and
   shows the reason on the page.

The rule "the factory never pushes to `main`" stays true for every part of
the tool.

## 7. Proof of the first step

- All the tests of today pass with the engine and the built-in definition.
  The tests of the workflow (`IssueWorkflowTest`, `WorkerTest`, and the
  others) are the proof that the behavior did not change.
- New tests prove the loader: a valid definition, an unknown step type, a
  state with no transition for an outcome, a loop with no limit, and a
  missing prompt file.
- A test proves that an issue keeps its version after a new commit of
  `.factory`.
- A run on heidi-fix with a `.factory` from the create command gives the
  same result as campaign 1 for a small issue.

## 8. Left to the builder

- The exact names of the outcomes and the exact YAML shape, within
  section 5.
- How to split `IssueWorkflow` into step types. The current methods are
  the natural boundaries.
- How the record keeps the version: a field with the commit of `.factory`.
- The order of the plans. My call: plan A is the engine, the definition,
  the loader, and the scratch folder rename. Plan B is the create command
  and the default `.factory` with the PR.

## 9. The upgrade (my call)

There is no migration of the old configuration. The new worker refuses a
configuration file of the old form, and its message names the command
`claude-ai-factory create`. The create command writes the new configuration
of the worker, and opens the PR `factory/setup` when `.factory` is missing.
At the decision, no issue was in work, so no worktree or record of the old
form exists.

## 10. Small decisions of the builder check (my calls)

- `git.excludeFactoryFolder` excludes `.factory-work` from now on.
- The gatekeeper already accepts the branch `factory/setup`.
- The `reviews` step can use only the four existing review kinds.
