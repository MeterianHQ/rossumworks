# Workflow definitions, plan A: the engine, the definition, and the loader

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The worker runs today's workflow from a YAML definition in the folder `.factory` of the starting repository, and each issue keeps the version that it started with.

**Architecture:** A new package `definition` holds the model of a definition, the YAML loader, and the versions that the worker reads from the default branch. The record of an issue keeps a state name and a step type in place of the enum `Phase`. `IssueWorkflow` becomes the engine: each step returns an outcome, and the engine finds the transition in the definition of the issue. The configuration of the worker keeps only the facts of the machine.

**Tech Stack:** Java 21, Maven, Jackson (YAML and JSON), JUnit 5, AssertJ. No new dependency.

**Spec:** `docs/superpowers/specs/2026-11-02-workflow-definitions-design.md` (the newer decision) and `docs/superpowers/specs/2026-11-01-factory-design.md`.

## Before Task 1

PR #12 ("Enter in three places") must be on `main`, because this plan uses `live.terminal-command`. Then rebase this branch:

```bash
cd /home/bbossola/projects/rocksolid/code/claude-ai-factory-worktrees/workflow-definitions
git fetch origin && git rebase origin/main
export JAVA_HOME=/home/bbossola/.sdkman/candidates/java/21.0.2-open
./mvnw -q verify
```

Expected: the rebase has no conflict (this branch changes only `docs/`), and the build passes.

In this plan, `M` is `src/main/java/com/meterian/factory`, `T` is `src/test/java/com/meterian/factory`, and `R` is `src/main/resources`. The command of one test class is `./mvnw -q test -Dtest=<ClassName>`. The command of all the tests is `./mvnw -q verify`. Each command needs `JAVA_HOME` as above.

## Global Constraints

- The subscription only: never an API key, never the Agent SDK. Fail closed. The factory never pushes to `main` or a tag.
- Tests never reach GitHub, a real token, the real `claude`, or the default tmux server.
- No change of behavior. If a test of today changes its expected result, the refactoring is wrong. The only allowed exceptions are the ones that the spec mandates and that this plan names: the scratch folder path (Task 1), the state name in the headline of the status comment (Task 4), the old form of the worker configuration (Task 8), and a new wait for a change of `.factory` (Task 9).
- The YAML only names step types that the worker already has. No code from the repository runs in the worker.
- An issue never meets a state that its version of the workflow does not know.
- The built-in state names are `prepare`, `implement`, `build`, `review`, `risk`, `merge`, `done`.
- The definition folder is `.factory`. The main file is `.factory/factory.yaml`. The scratch folder of a worktree is `.factory-work`.
- Simplified Technical English in docs, comments, notes, and texts of the page. The clean code rules of `CLAUDE.md`: no truncated names, one responsibility for each class and method, verbs for method names.
- Update the sections of `README.md` that a task makes wrong in the same task. The README has no code map.

## Review Focus

1. A status comment from before this change, with `"phase":"MERGE"` and no `state` or `step`: the worker must read it as the state `merge` with the step `merge-gate`, and continue the issue. Test: Task 4, `readsARecordFromBeforeTheStates`.
2. A commit on the default branch that breaks `.factory/factory.yaml`, or deletes `.factory`: the running issues continue, new issues use the last good version, and the page shows a warning. Test: Task 7, `keepsTheLastGoodVersionWhenANewCommitDoesNotLoad`.
3. A restart of the worker: the cache of versions is empty, and an issue with a version commit must load that commit again from the clone, not the newest version. Test: Task 7, `loadsTheVersionOfARecordAfterARestart`.
4. A typo in an outcome name, for example `pased: review`: the loader must refuse the definition and name the state and the outcome. A silent unknown key would leave the real outcome without a transition. Test: Task 2, `refusesAnOutcomeThatTheStepTypeDoesNotHave`.
5. An account that the newest version removes: a request of that account on a running issue (with an older version) must have no effect. Test: Task 7, `takesTheAccountsFromTheNewestVersion`.

---

### Task 1: Move the scratch folder to `.factory-work`

**Files:**
- Modify: `M/workflow/FactoryFolder.java`, `M/workflow/PullRequestDescription.java`, `M/prompt/RiskResultReader.java`, `M/prompt/ReviewResultReader.java`, `M/git/ProcessGit.java` (`FACTORY_FOLDER_PATTERN`)
- Modify: all the prompts in `R/prompts/` (`implement.md`, `fix.md`, `continue.md`, `risk.md`, the four `review-*.md`)
- Test: `T/git/ProcessGitTest.java`, `T/prompt/PromptLibraryTest.java`, `T/prompt/RiskResultReaderTest.java`, `T/prompt/ReviewResultReaderTest.java`, `T/workflow/IssueWorkflowTest.java`, `T/workflow/PullRequestDescriptionTest.java`, `T/worker/WorkerTest.java`, `T/testing/FakeAgentRunner.java`

**Interfaces:**
- Produces: the scratch folder name `.factory-work`, in one constant `FactoryFolder.NAME = ".factory-work"` (make it package-public in a small public class if other packages need it: `com.meterian.factory.workflow.ScratchFolder.NAME`). All readers use the constant.

- [ ] **Step 1: Change the tests to the new path.** Replace `.factory` by `.factory-work` in every test path above. Add this test to `ProcessGitTest`:

```java
@Test
void excludesTheScratchFolderButNotTheDefinitionFolder() throws Exception {
    // a worktree with .factory/factory.yaml (tracked) and .factory-work/question.md (not tracked)
    // after excludeFactoryFolder and commitAll:
    assertThat(committedFiles).contains(".factory/factory.yaml").doesNotContain(".factory-work/question.md");
}
```

- [ ] **Step 2: Run the tests.** Run: `./mvnw -q verify`. Expected: FAIL in the tests above, because the code still writes `.factory`.
- [ ] **Step 3: Change the code and the prompts** to `.factory-work`. The exclude pattern is `.factory-work/`. Each prompt sentence keeps its words; only the path changes, for example "Write the files of the factory only in the folder .factory-work/."
- [ ] **Step 4: Run the tests.** Run: `./mvnw -q verify`. Expected: PASS. Run `grep -rn '\.factory/' src` . Expected: no match.
- [ ] **Step 5: Commit.** `git commit -am "Move the scratch folder of a worktree to .factory-work"`

---

### Task 2: The definition model and the YAML loader

**Files:**
- Create in `M/definition/`: `FactoryDefinition.java`, `WorkflowDefinition.java`, `StateDefinition.java`, `StepDefinition.java`, `Transition.java`, `LoopDefinition.java`, `BuildDefinition.java`, `DefinitionFiles.java`, `DefinitionLoader.java`, `DefinitionException.java`
- Create: `M/state/StepType.java`
- Test: `T/definition/DefinitionLoaderTest.java`

**Interfaces:**
- Consumes: `IssueStatus`, `ReviewKind`, `WaitReason` from `state`.
- Produces:

```java
// state package, so that IssueRecord can use it without a cycle
public enum StepType {
    PREPARE("prepare", Set.of("done")),
    AGENT("agent", Set.of("changed", "no-change", "question", "conflict-left", "failed")),
    BUILD("build", Set.of("passed", "failed")),
    REVIEWS("reviews", Set.of("approved", "rejected")),
    RISK("risk", Set.of("assessed")),
    MERGE_GATE("merge-gate", Set.of("merged", "needs-approval", "needs-new-reviews", "conflict")),
    FINISH("finish", Set.of());
    public String yamlName();
    public Set<String> outcomes();
    public static Optional<StepType> findByYamlName(String name);
}

public record FactoryDefinition(WorkflowDefinition workflow, Map<String, Integer> limits, BuildDefinition build,
        List<String> allowedTools, String mergeTargetBranch, List<String> allowedAccounts,
        List<String> repositories, Map<String, String> prompts) {
    /** The text of a prompt file, by its path relative to .factory, for example prompts/fix.md. */
    public String promptText(String relativePath);
}
public record WorkflowDefinition(String start, Map<String, StateDefinition> states, Map<String, LoopDefinition> loops) {
    public StateDefinition state(String name);            // IllegalArgumentException for an unknown name
    public StateDefinition stateWithStep(StepType type);  // the one state of the type; see the rules below
    public Optional<LoopDefinition> loop(String name);
}
public record StateDefinition(String name, IssueStatus status, StepDefinition step, Map<String, Transition> transitions) {}
public record StepDefinition(StepType type, Map<String, String> promptPaths) {
    public List<ReviewKind> reviewKinds();   // REVIEWS only: the keys of promptPaths, in YAML order
}
public sealed interface Transition {
    record GoTo(String state, boolean keepStatus) implements Transition {}
    record EnterLoop(String loop) implements Transition {}
    record Wait(WaitReason reason) implements Transition {}
    record Block(WaitReason reason) implements Transition {}
}
public record LoopDefinition(String name, String to, int limit, Transition exit) {}
public record BuildDefinition(String command, Map<String, String> environment) {}
public interface DefinitionFiles { Optional<String> read(String relativePath) throws DefinitionException; }
public final class DefinitionLoader { public static FactoryDefinition load(DefinitionFiles files) throws DefinitionException; }
public final class DefinitionException extends Exception { public DefinitionException(String message); public DefinitionException(String message, Throwable cause); }
```

The YAML shape is the shape of spec section 5.5, with these decisions:

- The main file is `factory.yaml`, relative to `.factory`. Keys: `version` (must be `1`), `workflow`, `limits`, `build`, `claude.allowed-tools`, `merge.target-branch`, `accounts.allowed`, `repositories`.
- A step has `type` and an optional map `prompts`. The required prompt keys: `agent` needs `implement`, `fix`, `continue`; `risk` needs `risk`; `reviews` needs one or more of `correctness`, `security`, `architecture`, `quality`, and no other key; the other types need no prompt.
- A transition value is one of: a state name (`build`), a loop name (`fix-loop`), the word `block`, `{ to: <state>, keep-status: true }`, `{ wait: <reason> }`, `{ block: <reason> }`. A reason is the kebab-case name of a `WaitReason`, for example `fix-loop-limit`. The word `block` is `Block(FAILURE)`.
- A state `status` is `in-progress`, `in-review`, or `done`.
- A loop has `to`, `limit` (the name of a key in `limits`), and `exit` (a transition value).

The loader refuses, with a message that names the file, the state, and the key:

| Test | Input | Message contains |
|---|---|---|
| `refusesAnUnknownStepType` | `type: deploy` | `unknown step type deploy` |
| `refusesAStateWithoutATransitionForAnOutcome` | `build` without `failed` | `state build has no transition for the outcome failed` |
| `refusesAnOutcomeThatTheStepTypeDoesNotHave` | `pased: review` | `state build has the outcome pased, but the step type build has no such outcome` |
| `refusesALoopWithoutALimit` | `fix-loop: { to: implement, exit: block }` | `loop fix-loop has no limit` |
| `refusesALoopLimitThatIsNotInLimits` | `limit: retries` | `loop fix-loop names the limit retries, which is not in limits` |
| `refusesAMissingPromptFile` | `fix: prompts/missing.md` | `prompt file prompts/missing.md does not exist` |
| `refusesATransitionToAnUnknownState` | `passed: revue` | `state build goes to revue, which is not a state or a loop` |
| `refusesAnUnknownStartState` | `start: begin` | `start state begin is not a state` |
| `refusesASecondLoop` | two loops | `version 1 supports one loop` |
| `refusesAMissingStateOfARequiredStepType` | no `finish` state | `needs exactly one state with the step type finish` |
| `refusesAFinishStateWithTransitions` | `done` with `on` | `state done has the step type finish and cannot have transitions` |
| `refusesAnUnknownVersion` | `version: 2` | `version 2 is not supported` |
| `refusesAYamlError` | `workflow: [` | `factory.yaml` and the Jackson message |
| `refusesAMissingMainFile` | no `factory.yaml` | `.factory/factory.yaml does not exist` |

Each test builds the files with a `Map<String, String>` and the lambda `path -> Optional.ofNullable(files.get(path))`.

My calls, for the ledger of the executor:
- Exactly one state of each of the step types `prepare`, `build`, and `finish`. Live mode goes back to the build state, and a restart goes to the start state or the finish state. Cost if wrong: a later workflow with two build states needs a named target.
- One loop in version 1, because the record has one counter (`fixLoops`). Cost if wrong: a counter map in the record.
- The retries of a failed review session (2) and of a forward check (3) stay guards in the code of the step (spec 5.2), not loops of the definition. Cost if wrong: a later workflow cannot change them.
- The step type `wait` of the spec table is not in version 1: a wait is a transition target (spec 5.1). Cost if wrong: one more step type later.

- [ ] **Step 1: Write `DefinitionLoaderTest`** with the test `loadsAValidDefinition` (a full definition in the shape of Task 3, all values checked: start, the state `build` with its status `IN_PROGRESS`, step `BUILD`, the transition `failed` equal to `new Transition.EnterLoop("fix-loop")`, the loop limit `2`, the prompt text of `prompts/fix.md`, the merge target, the accounts) and every test of the table above.
- [ ] **Step 2: Run** `./mvnw -q test -Dtest=DefinitionLoaderTest`. Expected: FAIL (the classes do not exist).
- [ ] **Step 3: Write the model and the loader.** Read the YAML with `YAMLMapper.readTree`, as `ConfigurationLoader` does. Refuse unknown keys at each level, so that a typo never goes unseen. The loader reads each prompt file once and keeps the text in `FactoryDefinition.prompts`.
- [ ] **Step 4: Run** `./mvnw -q test -Dtest=DefinitionLoaderTest`. Expected: PASS.
- [ ] **Step 5: Commit.** `git commit -m "Add the model of a workflow definition and its YAML loader"`

---

### Task 3: The built-in definition, and the prompts from the definition

**Files:**
- Create: `R/factory/factory.yaml`
- Move: `R/prompts/*.md` to `R/factory/prompts/` (`git mv`)
- Create: `M/definition/ClasspathDefinitionFiles.java`, `M/definition/BuiltInDefinition.java`, `M/prompt/PromptTexts.java`
- Modify: `M/prompt/PromptLibrary.java`, `M/Main.java` (the creation of `PromptLibrary`)
- Test: `T/definition/BuiltInDefinitionTest.java`, `T/prompt/PromptLibraryTest.java`

**Interfaces:**
- Consumes: `DefinitionLoader.load`, `FactoryDefinition` (Task 2).
- Produces:

```java
public final class BuiltInDefinition { public static FactoryDefinition load(); }  // IllegalStateException if broken
public record PromptTexts(String implement, String fix, String continueAfterConversation,
                          Map<ReviewKind, String> reviews, String risk) {
    public static PromptTexts from(FactoryDefinition definition);
}
public PromptLibrary(PromptTexts texts, String mergeTargetBranch)   // replaces (Optional<Path>, String)
```

`PromptTexts.from` takes the paths of the `agent`, `reviews`, and `risk` states of the workflow. The configuration key `prompts-directory` stays in `FactoryConfiguration` until Task 8, but `Main` no longer uses it.

`R/factory/factory.yaml` is this text. It is today's workflow (spec 5.3):

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
      step:
        type: agent
        prompts: { implement: prompts/implement.md, fix: prompts/fix.md, continue: prompts/continue.md }
      on:
        changed: build
        question: { wait: question }
        no-change: block
        failed: block
        conflict-left: fix-loop
    build:
      status: in-progress
      step: { type: build }
      on: { passed: review, failed: fix-loop }
    review:
      status: in-review
      step:
        type: reviews
        prompts:
          correctness: prompts/review-correctness.md
          security: prompts/review-security.md
          architecture: prompts/review-architecture.md
          quality: prompts/review-quality.md
      on: { approved: risk, rejected: fix-loop }
    risk:
      status: in-review
      step: { type: risk, prompts: { risk: prompts/risk.md } }
      on: { assessed: merge }
    merge:
      status: in-review
      step: { type: merge-gate }
      on:
        merged: { to: done, keep-status: true }
        needs-approval: { wait: merge-needs-approval }
        needs-new-reviews: { to: build, keep-status: true }
        conflict: fix-loop
    done:
      status: done
      step: { type: finish }
  loops:
    fix-loop: { to: implement, limit: fix-loops, exit: { block: fix-loop-limit } }
limits:
  fix-loops: 2
build:
  command: ./mvnw verify
  environment: { LANG: C.utf8 }
claude:
  allowed-tools: [Read, Edit, Write, Glob, Grep, "Bash(git status*)", "Bash(git diff*)", "Bash(git log*)",
                  "Bash(git show*)", "Bash(./mvnw *)", "Bash(mvn *)"]
merge:
  target-branch: main
accounts:
  allowed: []
repositories: []
```

`keep-status` keeps today's status on two transitions: after a merge, the status stays "In review" until the finish step publishes "Done"; after a rebase that needs new reviews, the status stays "In review" in the build. The empty `accounts.allowed` lets nobody give a request: the built-in version runs only records from before this change, and Task 7 takes the accounts from the newest version of the repository.

- [ ] **Step 1: Write `BuiltInDefinitionTest`**: `loadsTheBuiltInDefinition` (no exception, start `prepare`, the seven state names in order), `expressesTodaysSequence` (for each state, the expected transition of each outcome, as in the YAML above), `usesTodaysAllowedTools` (equal to `ClaudeSettings.DEFAULT_ALLOWED_TOOLS`), and `hasTheFixLoopLimitOfToday` (`2`). Change `PromptLibraryTest` to build the library with `PromptTexts.from(BuiltInDefinition.load())`. Remove the tests of the override directory; add `rendersAPromptOfARepositoryDefinition` (a definition whose `prompts/fix.md` is `Fix: {{findings}}` renders that text).
- [ ] **Step 2: Run** `./mvnw -q test -Dtest='BuiltInDefinitionTest,PromptLibraryTest'`. Expected: FAIL.
- [ ] **Step 3: Write the resources and the classes.** `ClasspathDefinitionFiles` reads `/factory/<path>` from the jar.
- [ ] **Step 4: Run** `./mvnw -q verify`. Expected: PASS, with no change in the expected values of the other tests.
- [ ] **Step 5: Commit.** `git commit -m "Ship today's workflow and prompts as the built-in definition"`

---

### Task 4: The record keeps a state name and a step type

This task is a mechanical change. The transitions stay in the code; Task 5 moves them to the definition.

**Files:**
- Create: `M/state/LegacyPhase.java`
- Delete: `M/state/Phase.java`
- Modify: `M/state/IssueRecord.java`, `M/state/StatusComment.java`, and every user of `Phase`: `M/workflow/IssueWorkflow.java`, `FixLoop.java`, `IssueTextCheck.java`, `LiveMode.java`, `MergedIssue.java`, `ApprovalCheck.java`, `M/worker/RestartPreparation.java`, `FinalStepResult.java`, `Worker.java`, `M/web/PageAction.java`, `CardRenderer.java`, `CardFingerprint.java`
- Create: `T/testing/RecordStates.java`
- Test: every test that uses `Phase` (see `grep -rln "Phase\." src/test`), `T/state/IssueRecordTest.java`, `T/state/StatusCommentTest.java`

**Interfaces:**
- Consumes: `StepType` (Task 2), `BuiltInDefinition` (Task 3, in tests only).
- Produces:

```java
// IssueRecord: the component Phase phase becomes two components, at the same place
String state, StepType step,
// and a new last component
Optional<String> definitionCommit,

public IssueRecord withState(String newState, StepType newStep);   // replaces withPhase; the afterConversation rule tests step == AGENT
public IssueRecord withDefinitionCommit(String commit);

/** The phases of the records from before the states. Only the JSON reader and the code of today's transitions use it. */
public enum LegacyPhase {
    PREPARE("prepare", StepType.PREPARE), IMPLEMENT("implement", StepType.AGENT), BUILD("build", StepType.BUILD),
    REVIEW("review", StepType.REVIEWS), RISK("risk", StepType.RISK), MERGE("merge", StepType.MERGE_GATE),
    DONE("done", StepType.FINISH);
    public String stateName();
    public StepType stepType();
    public IssueRecord applyTo(IssueRecord record);   // record.withState(stateName(), stepType())
}

// tests only
public final class RecordStates { public static IssueRecord move(IssueRecord record, String stateName); } // step from BuiltInDefinition
```

The rules of the change:
- `record.phase() == Phase.DONE` becomes `record.step() == StepType.FINISH`. `Phase.IMPLEMENT` becomes `StepType.AGENT`. A set of phases becomes a set of step types with the same members.
- `withPhase(Phase.X)` in the main code becomes `LegacyPhase.X.applyTo(record)`. Task 5 removes these calls.
- `LiveMode.statusOfThePhase` becomes a switch on the step type with the same result.
- `IssueRecord.queued` uses the state `prepare` and the step `PREPARE`.
- JSON: the record writes `state` and `step` (`step` as the YAML name, for example `merge-gate`). A record from before has `phase` and no `state` or `step`: the reader maps it with `LegacyPhase` (use `@JsonAlias("phase")` on `state` and complete `step` in the compact constructor, or a `@JsonCreator`). A record without `definitionCommit` reads it as empty.
- The headline of the status comment shows the state name: `— phase implement` in place of `— phase IMPLEMENT` (spec 5.1). This is the only expected text that changes.
- In the tests, `Phase.BUILD` in an assertion becomes `"build"`, `advanceUntilPhase` becomes `advanceUntilState` with a state name, and `withPhase(Phase.MERGE)` becomes `RecordStates.move(record, "merge")`.

- [ ] **Step 1: Write the new tests in `IssueRecordTest` and `StatusCommentTest`:**

```java
@Test
void readsARecordFromBeforeTheStates() {
    String json = /* a status comment of today with "phase":"MERGE" and no state, step, or definitionCommit */;
    IssueRecord record = StatusComment.parse(json).orElseThrow();
    assertThat(record.state()).isEqualTo("merge");
    assertThat(record.step()).isEqualTo(StepType.MERGE_GATE);
    assertThat(record.definitionCommit()).isEmpty();
}

@Test
void writesAndReadsTheStateTheStepAndTheDefinitionCommit() {
    IssueRecord record = IssueRecord.queued(7).withState("review", StepType.REVIEWS).withDefinitionCommit("abc123");
    assertThat(StatusComment.parse(StatusComment.render(record, ...)).orElseThrow()).isEqualTo(record);
}

@Test
void showsTheStateNameInTheHeadline() { /* the headline contains "— phase review" */ }
```

Use the real parse and render methods of `StatusComment`; the names above show the intent.

- [ ] **Step 2: Run** `./mvnw -q test -Dtest='IssueRecordTest,StatusCommentTest'`. Expected: FAIL (compile errors: no `state()`).
- [ ] **Step 3: Make the change** in the main code and the tests, by the rules above.
- [ ] **Step 4: Run** `./mvnw -q verify`. Expected: PASS. Run `grep -rn "\bPhase\b" src`. Expected: only `LegacyPhase`, and no file `Phase.java`. Run `git diff --stat HEAD -- src/test` and read the diff of the assertions: each change is a rename by the rules above, and no expected value changes except the headline.
- [ ] **Step 5: Commit.** `git commit -am "Keep a state name and a step type in the record in place of the phase"`

---

### Task 5: The engine finds the transitions in the definition

**Files:**
- Create: `M/definition/DefinitionLookup.java`, `M/workflow/StepResult.java`, `M/workflow/TransitionRunner.java`
- Modify: `M/workflow/IssueWorkflow.java`, `FixLoop.java`, `ApprovalCheck.java`, `IssueTextCheck.java`, `LiveMode.java`, `MergedIssue.java`, `M/worker/RestartPreparation.java`, `M/worker/Worker.java`, `M/Main.java`
- Test: `T/workflow/TransitionRunnerTest.java`, `T/workflow/IssueWorkflowTest.java`, `T/workflow/LiveModeTest.java`, `T/worker/WorkerTest.java`

**Interfaces:**
- Consumes: the model (Task 2), `BuiltInDefinition` (Task 3), `IssueRecord.withState` (Task 4).
- Produces:

```java
public interface DefinitionLookup {
    FactoryDefinition definitionOf(IssueRecord record);
    static DefinitionLookup builtInOnly(FactoryDefinition builtIn) { return record -> builtIn; }
}

sealed interface StepResult {
    /** The step ends with an outcome; the engine applies the transition of the outcome. */
    record Outcome(IssueRecord record, String outcome, Optional<String> reason, List<Finding> findings) implements StepResult {}
    /** The step does not end now, for example at the usage limit; the record has no transition. */
    record Stay(IssueRecord record) implements StepResult {}
    /** A guard of the step stops the issue: Blocked with the wait reason FAILURE and the reason as a note. */
    record Blocked(IssueRecord record, String reason) implements StepResult {}
}

final class TransitionRunner {
    IssueRecord apply(FactoryDefinition definition, Issue issue, StepResult.Outcome result, Path worktree);
}
```

The rules of `TransitionRunner.apply`:
- `GoTo`: `withState(target, target step)`, and the status of the target state unless `keepStatus`.
- `EnterLoop`: `FixLoop` counts the loop. Above the limit it applies the exit transition of the loop. Else it writes the fix prompt from the findings with the `fix` prompt of the target state, withdraws the approval, and goes to the target state with its status. This is today's `FixLoop.start`, with the limit and the target from the `LoopDefinition`.
- `Wait`: status `WAITS_FOR_YOU` and the reason. For `QUESTION`, first today's `askOwner`: the question comment, then `LiveMode.enter`. The agent step gives the question text in `reason`.
- `Block`: status `BLOCKED` and the reason. When the step gives a reason, it is also a note. Today, the fix-loop limit has no note and a failure has a note; this rule keeps both.

The changes of the steps in `IssueWorkflow`. Each step method returns a `StepResult`. Map today's code to outcomes as follows. Each line keeps the side effects of today:

| Step | Today | Now |
|---|---|---|
| prepare | `withPhase(IMPLEMENT)`, `IN_PROGRESS` | `Outcome("done")` |
| implement | finished merge commit → BUILD; commit → BUILD; unbuilt commit → BUILD | `Outcome("changed")` |
| implement | question | `Outcome("question", reason = the question text)` |
| implement | session failed → block | `Outcome("failed", reason = "The agent session failed: ...")` |
| implement | no change → block | `Outcome("no-change", reason = "The agent made no change.")` |
| implement | conflict markers left → fix loop | `Outcome("conflict-left", findings = the marker finding)` |
| implement | findings missing → block | `Blocked("The findings of the fix loop are missing.")` |
| build | passed and on GitHub → REVIEW, `IN_REVIEW` | `Outcome("passed")` |
| build | failed → fix loop | `Outcome("failed", findings = "The build failed")` |
| build, review, risk | a guard: changed files, uncommitted files | `Blocked(<today's reason>)` |
| build, merge | the forward check | stays in `ForwardCheckFailure`, inside the step |
| review | approved → RISK | `Outcome("approved")` |
| review | rejected → fix loop | `Outcome("rejected", findings)` |
| review, risk | session failed | stays in `retryReviewRound`, inside the step: `Stay` or `Blocked` |
| risk | assessed → MERGE | `Outcome("assessed")` |
| merge | merged on GitHub, or merged now → DONE | `Outcome("merged")` |
| merge | needs owner | `Outcome("needs-approval")` |
| merge | needs new reviews | `Outcome("needs-new-reviews")` (the step keeps `withoutReviews()`) |
| merge | needs fix | the step opens the merge, then `Outcome("conflict", findings)` |
| merge | refused, failed | `Blocked(<today's reason>)` |
| any | usage limit, refused session, stopped build, postponed merge | `Stay` |
| finish | publishes DONE, cleans up | `Stay(done record)` |

The other moves without a step:
- `ApprovalCheck.continueAfterApproval`: the issue stays in its state (the merge state). It no longer sets the phase. The status is the status of the current state.
- `IssueTextCheck.acceptNewText`: the status is the status of the current state (`in-progress` for `implement`, `in-review` for `review` and `risk`, as today).
- `LiveMode`: the status after a conversation is the status of the current state. `buildAgain` goes to `stateWithStep(BUILD)` with its status.
- `RestartPreparation` and `Worker`: "back to PREPARE" is the start state; "to DONE" is `stateWithStep(FINISH)`. `MergedIssue.noteFailureAfterTheMerge` gets the finish state name as a parameter.
- `IssueWorkflow`, `LiveMode`, `RestartPreparation`, and `Worker` get a `DefinitionLookup`. `Main` and the tests give `DefinitionLookup.builtInOnly(BuiltInDefinition.load())`.
- After this task, the main code has no `LegacyPhase.X.applyTo` call. `LegacyPhase` stays for the JSON reader only.

- [ ] **Step 1: Write `TransitionRunnerTest`** with a small definition (from `DefinitionLoader`, not the built-in): `goesToTheTargetStateWithItsStatus`, `keepsTheStatusWhenTheTransitionSaysSo`, `entersTheLoopAndWritesTheFixPrompt`, `appliesTheExitOfTheLoopAboveTheLimit` (the limit 1, the second entry gives `BLOCKED` and `FIX_LOOP_LIMIT` with no note), `waitsWithTheReason`, `blocksWithTheReasonOfTheStepAsANote`. Add to `IssueWorkflowTest`: `followsADefinitionWithAnotherFixLoopLimit` (a definition with `fix-loops: 1`: the second failed build blocks with `FIX_LOOP_LIMIT`).
- [ ] **Step 2: Run** `./mvnw -q test -Dtest='TransitionRunnerTest,IssueWorkflowTest'`. Expected: FAIL.
- [ ] **Step 3: Write the engine** by the rules above.
- [ ] **Step 4: Run** `./mvnw -q verify`. Expected: PASS. The tests of today pass with no change of their expected values (spec 7). Run `grep -rn "applyTo(" src/main`. Expected: no match.
- [ ] **Step 5: Commit.** `git commit -am "Run the transitions of the issue from the workflow definition"`

---

### Task 6: One class for each step type

This task is a refactoring only. No test changes its expected result.

**Files:**
- Create in `M/workflow/`: `Step.java`, `StepSet.java`, `PrepareStep.java`, `AgentStep.java`, `BuildStep.java`, `ReviewsStep.java`, `RiskStep.java`, `MergeGateStep.java`, `FinishStep.java`, `ReviewSessionRetry.java`
- Modify: `M/workflow/IssueWorkflow.java`

**Interfaces:**
- Consumes: `StepResult` (Task 5), `FactoryDefinition` (Task 2).
- Produces:

```java
interface Step { StepResult run(Issue issue, IssueRecord record, Path worktree) throws AgentException, ApiKeyDetectedException; }

/** The steps of one definition version, with the helpers that depend on the version, for example the merge target. */
final class StepSet {
    static StepSet create(FactoryDefinition definition, StepServices services);
    Step stepOf(StepType type);
    FactoryDefinition definition();
}
/** The services that all versions share: GitHub, git, the agent runner, the build runner, the usage, the live mode. */
record StepServices(...)  // the fields that IssueWorkflow gets in its constructor today
```

- `IssueWorkflow` keeps `advance`, `enterLiveMode`, `isLive`, `isWaiting`, the unknown session check, `failStep`, the dispatch `stepSet.stepOf(record.step()).run(...)`, and the `TransitionRunner`. The text of the issue check stays before the dispatch.
- `ReviewSessionRetry` is today's `retryReviewRound`, with the limit 2. `ReviewsStep` and `RiskStep` use it.
- `AgentStep` keeps `markSessionStarted`, `undoSessionStart`, `hasUnbuiltCommit`, and `commitSolvedConflict`.
- The review notes of a round stay in one map that `ReviewsStep` fills and `IssueWorkflow.advance` publishes after the record.
- In this task, `StepSet.create` gets the built-in definition and the values of the configuration of today (the merge target, the fix-loop limit). Task 7 changes the source of the values.

- [ ] **Step 1: Run** `./mvnw -q verify` before the change. Expected: PASS. Note the number of tests.
- [ ] **Step 2: Move the code** into the classes above. Each class has one public method or a small number of private methods. `IssueWorkflow` is shorter than 200 lines.
- [ ] **Step 3: Run** `./mvnw -q verify`. Expected: PASS, with the same number of tests. `git diff --stat HEAD -- src/test` shows no change.
- [ ] **Step 4: Commit.** `git commit -am "Give each step type its own class"`

---

### Task 7: The versions of the definition from the default branch

**Files:**
- Create in `M/definition/`: `DefinitionVersions.java`, `DefinitionVersion.java`, `GitDefinitionFiles.java`
- Create: `M/build/BuildSettings.java`
- Modify: `M/git/Git.java`, `M/git/ProcessGit.java` (new methods), `M/workflow/IssueWorkflow.java` (a `StepSet` for each version), `StepSet.java`, `M/worker/Worker.java` (refresh, intake), `M/workflow/RequestReader.java`, `LiveMode.java`, `QuestionComment` call sites, `M/build/BuildRunner.java`, `M/merge/Merger.java`, `M/agent/PhaseRequest.java`, `ClaudeCommand.java`, `LiveSessions.java`, `TmuxLiveSessions.java`, `M/workflow/StatusPublisher.java`, `M/web/PageModel.java`, `PageModelSource.java`, `BoardPage.java` (the warning line), `M/Main.java`
- Test: `T/definition/DefinitionVersionsTest.java`, `T/git/ProcessGitTest.java`, `T/worker/WorkerTest.java`, `T/workflow/RequestReaderTest.java`, `T/web/BoardPageTest.java`, `T/agent/ClaudeCommandTest.java`, `T/build/BuildRunnerTest.java`

**Interfaces:**
- Consumes: `DefinitionLoader`, `BuiltInDefinition`, `DefinitionLookup`, `StepSet.create`, `IssueRecord.definitionCommit`.
- Produces:

```java
// Git
Optional<String> showFile(Path repository, String commit, String path);   // git show <commit>:<path>; empty when missing
String resolveCommit(Path repository, String reference);                  // git rev-parse <reference>^{commit}

public record DefinitionVersion(String commit, FactoryDefinition definition) {}

public final class DefinitionVersions implements DefinitionLookup {
    public DefinitionVersions(Git git, Path clone, String defaultBranch, FactoryDefinition builtIn);
    /** Reads origin/<default branch>. A new commit that loads becomes the current version. */
    public void refresh();
    public Optional<DefinitionVersion> current();
    /** The reason why the newest commit does not load, while an older good version stays current. */
    public Optional<String> warning();
    /** The version of the record: its commit, or the built-in version for a record without a commit. */
    public FactoryDefinition definitionOf(IssueRecord record);
    /** The accounts of the newest good version; empty without a good version. */
    public List<String> allowedAccounts();
}

public record BuildSettings(String command, Map<String, String> environment) {
    /** The build of the definition, with the variables of the machine. A value of the machine replaces a value with the same name. */
    public static BuildSettings combine(BuildDefinition build, Map<String, String> machineEnvironment);
}
BuildOutcome BuildRunner.run(Path worktree, BuildSettings settings);       // replaces run(Path, TargetRepository)
PhaseRequest(int issueNumber, UUID sessionId, boolean newSession, Path workingDirectory, String prompt, List<String> allowedTools)
String LiveSessions.open(int issueNumber, UUID sessionId, Path worktree, List<String> allowedTools)
```

The rules:
- `refresh` runs at the start of each poll, after `git fetch origin <default branch>` in the clone under `CloneLock`. A fetch failure writes a warning to the log and keeps the current version.
- A commit that does not load (a YAML error, a validation error, no `.factory/factory.yaml`) does not replace the current version. `warning()` gives `The .factory of <commit7> does not load: <message>. The factory keeps the version <commit7>.` A good version later clears the warning. The log has the same sentence once for each commit.
- With no good version at all, the worker takes no new issue. `warning()` gives `The default branch has no .factory that loads: <message>. The factory takes no new issue.` The issues in work continue with their versions.
- At the intake, the record gets `withDefinitionCommit(current commit)`.
- `definitionOf` keeps a cache by commit. After a restart, it loads the commit of the record with `git show`. A commit that does not load any more throws `IllegalStateException`; `IssueWorkflow` blocks the issue with the note `The definition <commit7> of the issue does not load: <message>.` (fail closed).
- `IssueWorkflow` keeps a `StepSet` for each commit (and one for the built-in version). The `StepSet` takes from its definition: the merge target, the prompts, the build, the allowed tools, and the fix-loop limit.
- The accounts always come from `allowedAccounts()`: `RequestReader`, `LiveMode` (the mention), the question comment, and the phone link sender get a `Supplier<List<String>>`.
- The status comment and the card show "Fix loops: N of M" with M from the definition of the record.
- The page shows the warning as one line at the top of the board, in the class `warning`, with the text escaped by `Html`.
- The default branch: `git symbolic-ref --short refs/remotes/origin/HEAD` in the clone, without `origin/`. Read it once at the start.

In this task the machine variables of the build are `configuration.target().buildEnvironment()`, and the clone is `configuration.target().cloneDirectory()`. Task 8 changes both sources.

- [ ] **Step 1: Write `DefinitionVersionsTest`** with a `TemporaryRepository` as `origin` and a clone of it:
  - `loadsTheDefinitionOfTheDefaultBranch`
  - `keepsTheLastGoodVersionWhenANewCommitDoesNotLoad`: commit a good `.factory`, refresh, commit a broken `factory.yaml`, refresh. Expected: `current()` has the first commit, `warning()` contains `does not load`. Then commit a commit that deletes `.factory`: the same result.
  - `takesNoVersionWithoutAGoodDefinition`: `current()` is empty, `warning()` contains `takes no new issue`.
  - `keepsTheVersionOfAnIssueAfterANewCommit` (spec 7): a record with the first commit; commit `fix-loops: 5`, refresh. Expected: `definitionOf(record).limits().get("fix-loops")` is `2`, and `current()` has `5`.
  - `loadsTheVersionOfARecordAfterARestart`: a new `DefinitionVersions` on the same clone, with the record of the first commit, gives the first version.
  - `usesTheBuiltInVersionForARecordWithoutACommit`
  - `takesTheAccountsFromTheNewestVersion`
- [ ] **Step 2: Write the other tests:** `WorkerTest.takesNoNewIssueWithoutAGoodDefinition`, `WorkerTest.givesTheIntakeTheCurrentCommit`, `RequestReaderTest.ignoresAnAccountThatTheNewestVersionRemoves`, `BoardPageTest.showsTheWarningOfTheDefinition`, `ClaudeCommandTest.usesTheAllowedToolsOfTheRequest`, `BuildRunnerTest.letsTheMachineReplaceAVariableOfTheDefinition`, and the `ProcessGitTest` tests of `showFile` and `resolveCommit`.
- [ ] **Step 3: Run** `./mvnw -q verify`. Expected: FAIL in the new tests.
- [ ] **Step 4: Write the code** by the rules above.
- [ ] **Step 5: Run** `./mvnw -q verify`. Expected: PASS.
- [ ] **Step 6: Commit.** `git commit -am "Read the workflow definition from the default branch, and keep its version in each issue"`

---

### Task 8: The configuration of the worker keeps only the facts of the machine

**Files:**
- Create: `M/configuration/RepositoryName.java`, `M/configuration/WorkerLimits.java`
- Delete: `M/configuration/TargetRepository.java`, `M/configuration/Limits.java`
- Modify: `M/configuration/FactoryConfiguration.java`, `ConfigurationLoader.java`, `ClaudeSettings.java`, `M/Main.java`, `M/github/HttpGitHubClient.java`, `M/workflow/IssueWorkspace.java`, `M/merge/Merger.java`, `M/web/PageModelSource.java`, `factory.example.yaml`, `README.md`
- Test: `T/configuration/ConfigurationLoaderTest.java`, `T/MainTest.java`, and the tests that build a `FactoryConfiguration`

**Interfaces:**
- Consumes: `DefinitionVersions` (Task 7).
- Produces:

```java
public record RepositoryName(String owner, String name) {
    public static RepositoryName parse(String fullName) throws ConfigurationException;  // "MeterianHQ/heidi-fix"
    public String fullName();
    public String sshUrl();
}
public record WorkerLimits(int parallelIssues, Duration pollInterval, double usageThreshold) { public static WorkerLimits createDefaults(); }
public record ClaudeSettings(String command, List<String> disallowedTools) { ... }
public record FactoryConfiguration(RepositoryName repository, Path workDirectory, Path gatekeeperDirectory,
        Path liveDirectory, WorkerLimits limits, Map<String, String> buildEnvironment, IsolationMode isolationMode,
        ClaudeSettings claude, Optional<WebSettings> web, List<String> terminalCommand) {
    public Path cloneDirectory() { return workDirectory.resolve("repository"); }
}
// Git
void cloneRepository(String url, Path directory);
```

The new form of the configuration file (`factory.example.yaml`):

```yaml
repository: MeterianHQ/heidi-fix
work-directory: /home/bbossola/.claude-ai-factory/work
gatekeeper-directory: /home/bbossola/.claude-ai-factory/gatekeeper.git
# live-directory: /path/to/live         # optional, default: <work-directory>/live
limits:                 # all keys optional
  parallel-issues: 2
  poll-interval: 60s
  usage-threshold: 0.90
build:                  # optional: the variables of this machine; a value here replaces the value of .factory
  environment:
    JAVA_HOME: /home/bbossola/.sdkman/candidates/java/21.0.2-open
isolation:              # optional
  mode: same-user
claude:                 # optional
  command: claude
  disallowed-tools: ["Bash(git push*)", "Bash(gh *)", WebFetch, WebSearch]
# live:                 # optional
#   terminal-command: [gnome-terminal, --]
# web: ...              # as today
```

The rules:
- The old form: a file with one of the keys `target`, `merge`, `accounts`, `prompts-directory`, `limits.fix-loops`, or `claude.allowed-tools`. The loader refuses it with: `The configuration file <file> has the old form (the key <key>). The workflow, the build, the merge, and the accounts are now in the folder .factory of the repository. Run claude-ai-factory create to write the new configuration.` (spec 9).
- At the start, `Main` clones the repository into `cloneDirectory()` when the folder does not exist, with `git clone <sshUrl>`. A clone that exists stays as it is.
- The web page shows the parallel issues from `WorkerLimits`, and the fix-loop limit of the current version.
- `README.md`: the section of the configuration shows the new form, and a new section ".factory" tells what the folder holds, with a link to `src/main/resources/factory/factory.yaml` as the example. Until plan B, the owner commits a `.factory` to the repository by hand: copy `src/main/resources/factory/` and fill in `accounts.allowed`.

- [ ] **Step 1: Change `ConfigurationLoaderTest`:** `loadsTheNewForm` (all values), `refusesTheOldForm` (one test for each old key, the message contains the key and `claude-ai-factory create`), `refusesARepositoryWithoutAnOwner` (`repository: heidi-fix`), `usesTheDefaultsOfTheLimits`. Add `MainTest.clonesTheRepositoryWhenTheCloneIsMissing` with a fake `Git` and no network.
- [ ] **Step 2: Run** `./mvnw -q test -Dtest='ConfigurationLoaderTest,MainTest'`. Expected: FAIL.
- [ ] **Step 3: Write the code** and change every user of the removed records.
- [ ] **Step 4: Run** `./mvnw -q verify`. Expected: PASS. Run `grep -rn "TargetRepository\|promptDirectory\|allowedAccounts()" src/main`. Expected: only `DefinitionVersions.allowedAccounts()` and `FactoryDefinition.allowedAccounts()`.
- [ ] **Step 5: Commit.** `git commit -am "Keep only the facts of the machine in the configuration of the worker"`

---

### Task 9: A pull request that changes `.factory` waits for the owner

**Files:**
- Modify: `M/state/StepType.java` (the outcome `factory-changed` of `MERGE_GATE`), `M/state/WaitReason.java`, `M/workflow/ApprovalCheck.java`, `M/workflow/MergeGateStep.java`, `M/git/Git.java`, `M/git/ProcessGit.java`, `R/factory/factory.yaml`
- Test: `T/workflow/IssueWorkflowTest.java`, `T/git/ProcessGitTest.java`, `T/definition/BuiltInDefinitionTest.java`

**Interfaces:**
- Produces:

```java
// WaitReason
FACTORY_CHANGE_NEEDS_APPROVAL("The pull request changes the folder .factory. The merge needs your approval."),
// Git
boolean changesPath(Path worktree, String baseReference, String path);   // git diff --quiet <base>...HEAD -- <path>
```

The rules:
- `MergeGateStep` asks `changesPath(worktree, "origin/" + target, ".factory")` before `MergeGate.decide`. When the PR changes `.factory` and the owner gave no approval, the outcome is `factory-changed`. With the approval, the step continues as today: `MergeGate.decide` with `isApproved` true.
- The built-in definition gets `factory-changed: { wait: factory-change-needs-approval }` in the state `merge`. The loader then requires the transition in every definition, because the outcome is part of the step type.
- `ApprovalCheck` accepts the new reason as an approvable wait.
- A change in `.factory-work` is not a change of `.factory`.

- [ ] **Step 1: Write the tests:** `IssueWorkflowTest.waitsForTheOwnerWhenThePullRequestChangesTheFactory` (an agent that writes `.factory/prompts/fix.md`, risk low and small: the issue waits with `FACTORY_CHANGE_NEEDS_APPROVAL` and does not merge), `IssueWorkflowTest.mergesAChangeOfTheFactoryAfterTheApproval`, `ProcessGitTest.changesPathIgnoresTheScratchFolder`, and the new transition in `BuiltInDefinitionTest.expressesTodaysSequence`.
- [ ] **Step 2: Run** `./mvnw -q test -Dtest='IssueWorkflowTest,ProcessGitTest,BuiltInDefinitionTest'`. Expected: FAIL.
- [ ] **Step 3: Write the code.**
- [ ] **Step 4: Run** `./mvnw -q verify`. Expected: PASS.
- [ ] **Step 5: Commit.** `git commit -am "Wait for the owner when a pull request changes the folder .factory"`

---

## After the plan

- The run on heidi-fix of spec section 7 needs a `.factory` in heidi-fix. Plan B (the create command) writes it. Until then, the owner can commit a copy of `src/main/resources/factory/` by hand.
- The deployed jar `~/.claude-ai-factory/campaign.jar` does not change in this plan. The new jar refuses the old `campaign.yaml` (Task 8), so a deploy needs a new configuration file and a `.factory` on the target branch.
