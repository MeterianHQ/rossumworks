# The workflow of `.factory` as a state machine: implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the fixed workflow of PR #13 with the state machine of the spec: free states, actions, events, `previous`, limits, `on-any-state`, free columns, and the default workflow that does what `main` does.

**Architecture:** The loader reads `factory.yaml` into a new definition model (`StateDefinition` with a column, an optional action, an optional limit, and a map from event to `Target`), and refuses a trap before an issue meets it. One engine class (`MoveRunner`) applies each event: it resolves `previous`, sets the column, the pending prompt, and the notes. The record keeps the state, the column, `previous`, and the entry counts; it no longer has a status or a wait reason. The waits (approval, conversation, a state without an action) are actions, so the worker loop treats them the same way as the other actions.

**Tech Stack:** Java 21, Maven (`./mvnw verify`), Jackson YAML and JSON, JUnit 5. No new dependency.

**Spec:** `docs/superpowers/specs/2026-11-05-workflow-state-machine-design.md` (approved 2026-11-05, with the sections 6.1 and 6.7 updated after the merge check of `main`). The older spec `docs/superpowers/specs/2026-11-02-workflow-definitions-design.md` stays valid except section 5.

## Global Constraints

- Build: `export JAVA_HOME=/home/bbossola/.sdkman/candidates/java/21.0.2-open; ./mvnw verify`. Each task ends with a green `./mvnw verify`.
- Repository: `/home/bbossola/projects/rocksolid/code/claude-ai-factory` (MeterianHQ/claude-ai-factory). Execution worktree: `/home/bbossola/projects/rocksolid/code/claude-ai-factory-worktrees/state-machine`, branch `feature/state-machine`, created from the spec commit 982c2b2. The final pull request targets `workflow-definitions`, never `main`.
- The subscription `claude` CLI only. Never an API key, never the Agent SDK. Tests never reach GitHub, a real token, the real `claude`, or the default tmux server.
- Fail closed. The factory never pushes to `main` or to a tag.
- `version` stays `1`. The keys of PR #13 (`status`, `step`, `loops`, root `limits`) get the normal unknown-key error, for example `factory.yaml: the state build has the unknown key step`.
- The words of the YAML, the documents, and the messages are state, action, and event. Not step, phase, status, or outcome.
- Every message of the loader starts with `factory.yaml: ` and names the state and the key.
- Clean code (see `CLAUDE.md` of the user): one responsibility for each class and method, full names (`context`, not `ctx`), a verb for each method name, one-line comments only for complex code, Javadoc allowed.
- Strict Simplified Technical English in comments, Javadoc, commit messages, and Markdown.
- Commit messages end with the two lines `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>` and `Claude-Session: https://claude.ai/code/session_01KvjgDKKDuaMtsk17fqM6ms`.
- Never write a customer name in code, tests, commits, or documents.
- Do not push. The controller asks the owner before each push or pull request.

## Review Focus

1. **An issue in work with a record of `main`.** After the deploy, the records of `main` (`phase`, `status`, `waitReason`, `fixLoops`) must read as states of the default workflow, and the issue must continue. A broken read blocks each issue in work. Test: Task 11, `readsAWaitingMergeRecordOfMain`.
2. **A `limit.then` that goes back into the cycle.** `implement` with `limit: { then: build }` and `build: { failed: implement }` loops for ever at the limit: each entry of `implement` goes to `build`. The cycle rule must count only the edges into a state with a limit. Test: Task 4, `refusesACycleThroughTheThenOfALimit`.
3. **Enter or block during the first coding session.** The stop drops the result. The entry of the limit must count one time only, and `continue: previous` must give the same prompt. Test: Task 5, `countsOneEntryAfterABlockDuringTheAction`.
4. **A card of an older version of `.factory`.** Its column is not in the newest version. The card must show in an extra column at the end, not disappear. Test: Task 6, `showsACardOfAnUnknownColumnInAnExtraColumn`.
5. **A label `factory:approve` that comes before the merge gate asks.** As on `main`, the gate merges without a wait for these files, and a later code change removes the approval. Test: Task 9, `mergesAtOnceWithAnApprovalGivenBefore`.

---

### Task 1: Merge `main` into the branch

`origin/main` has 5 commits after the base 756fa80 of the branch: ccfc2e1 (Enter stops the current step), e56c06b (the watcher of the continue marker), 4099a94 (a new session after a very early stop), f53b174 (Start again, and the recovery of a lost forward), 36158a4 (the pause of the pick up). The merge has 13 conflicts.

**Files:** the conflicts of `git merge origin/main`: `Main.java`, `web/PageModel.java`, `web/PageModelSource.java`, `worker/Worker.java`, `workflow/IssueWorkflow.java`, `workflow/RequestReader.java` (deleted on the branch), `workflow/ReviewRound.java`, and the tests `BoardPageTest`, `PageModelSourceTest`, `WebServerTest`, `GitHubRequestBudgetTest`, `WorkerTest`, `RequestReaderTest`.

**Interfaces:**
- Produces: the branch with the five features of `main`, on the PR #13 engine. The later tasks use `StartAgainCheck`, `StartAgainRequests`, `PickUpSwitch`, `ContinueMarkerWatcher`, `SessionReport.stopped()`, and the Enter stop of `Worker`.

- [ ] **Step 1:** In the worktree, run `git merge origin/main`.
- [ ] **Step 2:** Solve each conflict with these rules:
  - The branch removed `accounts.allowed` (34eebbc). Keep the removal. Where `main` checks the account of a label (`IssueLabelActors`, `isGivenByAllowedAccount`, `RequestReader`), every label counts as accepted. Delete `RequestReader` and its test when nothing uses them after the merge.
  - The branch replaced the phases with the states of PR #13. Where `main` uses `record.phase()`, use `record.step()` and `StepType`. Where `main` uses `Phase.DONE`, use `StepType.FINISH`. Where `main` uses `Phase.PREPARE`, use `StepType.PREPARE`.
  - `StartAgainCheck.isPossible` becomes: status BLOCKED, no live session, and step not FINISH. A Start again moves the record to the start state of its definition with a new session (use `IssueRecord.queued` plus the definition commit of the old record).
  - Keep the Enter stop of ccfc2e1 and the stopped-session rules (`SessionReport.stopped()`) in `ReviewRound` and the agent step.
- [ ] **Step 3:** Run `./mvnw verify`. Expected: BUILD SUCCESS. Each test of `main` and of the branch passes.
- [ ] **Step 4:** Commit the merge with the message `Merge main into the workflow definitions` and a body that lists the rules of step 2.

### Task 2: The words action and event in the Java code

A pure rename. No behavior changes. It makes the later diffs read in the words of the spec.

**Files:** all of `src/main/java/com/meterian/factory/workflow/`, `state/StepType.java`, `definition/`, `worker/`, `web/`, `prompt/PromptTexts.java`, and their tests; `src/main/resources/factory/factory.yaml` and the test YAML texts.

**Interfaces:**
- Produces, with the same behavior as before:
  - `state.ActionType` (was `StepType`). The value `AGENT` becomes `CODING`, with the YAML name `coding`. `outcomes()` becomes `events()`.
  - `workflow.Action` (was `Step`), with `ActionResult run(Issue issue, IssueRecord record, Path worktree)`.
  - `workflow.ActionResult` (was `StepResult`). `Outcome` becomes `Event`, with the accessor `event()` (was `outcome()`). The factory methods keep their names: `endWith`, `endWithReason`, `endWithFindings`, `stay`, `block`.
  - `workflow.ActionSet` (was `StepSet`), with `actionOf(ActionType)` (was `stepOf`). `workflow.ActionServices` (was `StepServices`).
  - `PrepareAction`, `CodingAction`, `BuildAction`, `ReviewsAction`, `RiskAction`, `MergeGateAction`, `FinishAction` (were `PrepareStep`, `AgentStep`, `BuildStep`, `ReviewsStep`, `RiskStep`, `MergeGateStep`, `FinishStep`).
  - The JSON property `step` of the record stays as it is in this task.

- [ ] **Step 1:** Rename the types and the files with `git mv`. Update each reference. Change `type: agent` to `type: coding` in `src/main/resources/factory/factory.yaml` and in the YAML texts of the tests.
- [ ] **Step 2:** Rename the words in the Javadoc and the comments of the renamed classes: "step" becomes "action", "outcome" becomes "event". Do not change the messages of the loader in this task: Task 3 replaces them.
- [ ] **Step 3:** Run `./mvnw verify`. Expected: BUILD SUCCESS, the same number of tests as after Task 1.
- [ ] **Step 4:** Commit: `Use the words action and event in the code of the workflow`.

### Task 3: The definition model and the reader of the new YAML

The new loader exists next to the old one. Nothing uses it yet. Task 5 switches the engine to it.

**Files:**
- Rename (same package, `git mv`): `FactoryDefinition` → `LegacyFactoryDefinition`, `WorkflowDefinition` → `LegacyWorkflowDefinition`, `StateDefinition` → `LegacyStateDefinition`, `DefinitionLoader` → `LegacyDefinitionLoader`, `StateReader` → `LegacyStateReader`, `WorkflowReader` → `LegacyWorkflowReader`, `DefinitionValidator` → `LegacyDefinitionValidator`. Update their users. The engine keeps the legacy model until Task 5.
- Modify: `state/ActionType.java`. `LegacyDefinitionValidator` uses an explicit list of the seven old types instead of `ActionType.values()`.
- Create in `src/main/java/com/meterian/factory/definition/`: `FactoryDefinition.java`, `WorkflowDefinition.java`, `StateDefinition.java`, `ActionDefinition.java`, `LimitDefinition.java`, `Target.java`, `OnAnyState.java`, `MergeMode.java`, `ColumnNames.java`, `DefinitionLoader.java`, `WorkflowReader.java`, `StateReader.java`, `ActionReader.java`, `DefinitionRules.java`.
- Create the test folder `src/test/resources/definitions/default/` with `factory.yaml` (the YAML of spec section 9, with `prompts/resume.md` absent) and copies of the prompt files of `src/main/resources/factory/prompts/`.
- Test: `src/test/java/com/meterian/factory/definition/DefinitionLoaderTest.java` (new content for the new loader; rename the old test class to `LegacyDefinitionLoaderTest`).

**Interfaces:**
- Consumes: `DefinitionNode`, `DefinitionFiles`, `DefinitionException`, `BuildDefinition` (unchanged).
- Produces:
  - `state.ActionType` gets the values `APPROVAL("approval", Set.of("approved"))` and `CONVERSATION("conversation", Set.of("continue", "changed"))`, and these methods: `boolean waitsForYou()` (APPROVAL, CONVERSATION), `boolean usesIssueText()` (CODING, REVIEWS, RISK), `boolean allowsSeveralStates()` (APPROVAL only), `boolean needsWorktree()` (CODING, BUILD, REVIEWS, RISK, MERGE_GATE, CONVERSATION). The constant `Set<String> ActionType.EVENTS_WITHOUT_ACTION = Set.of("continue")` gives the events of a state without an action.
  - `record FactoryDefinition(WorkflowDefinition workflow, BuildDefinition build, List<String> allowedTools, String mergeTargetBranch, List<String> repositories, Map<String, String> prompts)` with `String promptText(String relativePath)`.
  - `record WorkflowDefinition(String start, OnAnyState onAnyState, Map<String, StateDefinition> states)` with `StateDefinition state(String name)`, `Optional<StateDefinition> findStateWithAction(ActionType type)` (the first state with this type, in YAML order), `StateDefinition finishState()`, `List<String> columns()` (the distinct columns in the order of first use).
  - `record StateDefinition(String name, String column, Optional<ActionDefinition> action, Optional<LimitDefinition> limit, Map<String, Target> on)` with `Optional<ActionType> actionType()`, `boolean waitsForYou()` (no action, or an action that waits for you), `Set<String> events()`.
  - `record ActionDefinition(ActionType type, Map<String, String> promptPaths, MergeMode mergeMode)`. `mergeMode` is `MergeMode.RISK` for each type except a `merge-gate` with another `mode`.
  - `record LimitDefinition(int entries, Target then)`.
  - `sealed interface Target` with `record ToState(String name)` and `record ToPrevious()`. `static Target read(String text)` gives `ToPrevious` for `previous`.
  - `record OnAnyState(String block, String enter, String issueTextChanged)`.
  - `enum MergeMode { RISK("risk"), AUTO_MERGE("auto-merge"), ALWAYS_ASK("always-ask") }` with `yamlName()` and `static Optional<MergeMode> findByYamlName(String)`.
  - `final class ColumnNames` with `String QUEUED = "queued"`, `static String displayName(String column)` (`waits-for-you` → `Waits for you`), and `static boolean isValid(String column)` (pattern `[a-z0-9]+(-[a-z0-9]+)*`).
  - `static FactoryDefinition DefinitionLoader.load(DefinitionFiles files) throws DefinitionException`.

- [ ] **Step 1: Write the failing tests** in `DefinitionLoaderTest`. Use an in-memory `DefinitionFiles` (a map from path to text). Each test asserts the exact message:
  - `loadsTheDefaultWorkflow`: the folder `definitions/default` loads; `workflow().start()` is `prepare`; `onAnyState()` is `new OnAnyState("blocked", "live", "text-changed")`; `state("implement").limit()` is `Optional.of(new LimitDefinition(3, new Target.ToState("blocked")))`; `state("text-changed").on().get("approved")` is `new Target.ToPrevious()`; `state("merge").action().get().mergeMode()` is `MergeMode.RISK`; `columns()` is `[in-progress, in-review, waits-for-you, blocked, done]`.
  - `refusesAnUnknownKeyOfAState`: a state with `step:` → `factory.yaml: the state build has the unknown key step`.
  - `refusesTheRootKeyLimits`: `limits: { fix-loops: 2 }` → `factory.yaml: unknown key limits`.
  - `refusesAStateWithoutColumn` → `factory.yaml: the state build has no column`.
  - `refusesTheColumnQueued` → `factory.yaml: the state build has the column queued, which the factory keeps for new issues`.
  - `refusesAColumnThatIsNotAName` (`In Review`) → `factory.yaml: the state review has the column In Review; a column has only small letters, digits, and hyphens`.
  - `refusesAStartWithoutPrepare` → `factory.yaml: the start state build has the action build; it needs the action prepare`.
  - `refusesAWorkflowWithoutFinish` → `factory.yaml: no state has the action finish`.
  - `refusesTwoStatesWithTheSameAction` (two `build`) → `factory.yaml: the states build and build-again both have the action build; an action other than approval is in one state at most`.
  - `acceptsTwoApprovalStates`.
  - `refusesAMissingOnAnyStateKey` (no `enter`) → `factory.yaml: key workflow.on-any-state.enter is missing`.
  - `refusesAnOnAnyStateTargetThatIsNotAState` → `factory.yaml: on-any-state block goes to the state stuck, which does not exist`.
  - `refusesATextTargetWithoutApproval` → `factory.yaml: the issue-text-changed target blocked needs the action approval`.
  - `refusesAnEnterTargetWithoutConversation` → `factory.yaml: the enter target blocked needs the action conversation`.
  - `refusesAMissingEvent` → `factory.yaml: the state build has no next state for the event failed`.
  - `refusesAnUnknownEvent` → `factory.yaml: the state build has the event crashed, but the action build has no such event`.
  - `refusesAnEventOnFinish` → `factory.yaml: the state done has the event done, but the action finish has no such event`.
  - `refusesATargetThatIsNotAState` → `factory.yaml: the state build goes to the state deploy for the event passed, but deploy does not exist`.
  - `refusesALimitWithoutEntries` and `refusesALimitBelowOne` (`entries: 0`) → `factory.yaml: key workflow.states.implement.limit.entries must be an integer of 1 or more`.
  - `refusesTheStateNamePrevious` → `factory.yaml: the state name previous is reserved`.
  - `refusesAMissingCodingPrompt` (no `fix`) → `factory.yaml: the state implement needs the prompt fix`.
  - `refusesAnUnknownPrompt` → `factory.yaml: the state implement has the unknown prompt review`.
  - `refusesReviewsWithoutPrompt` → `factory.yaml: the state review needs one review prompt or more`.
  - `refusesAReviewNameThatIsNotAName` (`Security Check`) → `factory.yaml: the state review has the review Security Check; a review name has only small letters, digits, and hyphens`.
  - `refusesAMissingPromptFile` → `factory.yaml: the state implement: prompt file prompts/fix.md does not exist`.
  - `refusesModeOnAnotherAction` → `factory.yaml: the state build has the unknown key action.mode`.
  - `refusesAnUnknownMode` → `factory.yaml: the state merge has the unknown mode yolo`.
  - `requiresResumeForAnEntryWithoutFindings`: `risk: { on: { assessed: implement } }` without `resume` → `factory.yaml: the state implement needs the prompt resume, because the state risk goes to it with the event assessed`.
  - `doesNotRequireResumeForTheDefault` (the default loads without `resume`).
  - `refusesAMissingBuildCommand`, `refusesMissingAllowedTools`, `refusesAMissingTargetBranch`: the messages of the old loader, unchanged.
- [ ] **Step 2:** Run `./mvnw -q test -Dtest=DefinitionLoaderTest`. Expected: compile failure, because the new classes do not exist.
- [ ] **Step 3:** Do the renames to `Legacy*`. Create the new model and the readers. `DefinitionLoader` reads the root keys `version`, `workflow`, `build`, `claude`, `merge`, `repositories`, as the old loader. `WorkflowReader` reads `start`, `on-any-state`, and `states`. `StateReader` reads `column`, `action`, `limit`, and `on`. `ActionReader` reads `type`, `prompts`, and `mode`, and reads each prompt file once into the map of prompt texts. `DefinitionRules.verify(WorkflowDefinition)` applies the rules 2 to 7 and 11 of spec section 7, in this order: start, finish, single actions, on-any-state, events, targets, prompts.
  - The prompts of `coding` are `implement`, `fix`, `continue` (required) and `resume` (required only by rule 11). The prompt of `risk` is `risk`. `reviews` has any names. The other actions have no prompts.
  - The entries that need `resume` (rule 11): an `on:` entry `event: <coding state>` of a state X, except: X has the action `prepare` and the event is `done`; the event has findings (`build` `failed`, `reviews` `rejected`, `merge-gate` `conflict`, `coding` `conflict-left`); the event is `continue` and X is the `block` target or the `enter` target. An entry `event: previous` of a state with an action that does not wait for you also needs `resume` when the workflow has a coding state (my call: `previous` can be the coding state).
- [ ] **Step 4:** Run `./mvnw -q test -Dtest='DefinitionLoaderTest,LegacyDefinitionLoaderTest'`. Expected: PASS.
- [ ] **Step 5:** Run `./mvnw verify`. Expected: BUILD SUCCESS.
- [ ] **Step 6:** Commit: `Add the reader of the state machine of .factory`.

### Task 4: The loader rules of the graph and of the placeholders

**Files:**
- Create: `definition/WorkflowGraph.java`, `definition/PromptPlaceholders.java`.
- Modify: `definition/DefinitionLoader.java` (call both after `DefinitionRules`).
- Test: `definition/WorkflowGraphTest.java`, `definition/PromptPlaceholdersTest.java`.

**Interfaces:**
- Consumes: the model of Task 3.
- Produces: `static void WorkflowGraph.verify(WorkflowDefinition) throws DefinitionException` (rules 8, 9, 10), `static void PromptPlaceholders.verify(WorkflowDefinition, Map<String, String> promptTexts) throws DefinitionException` (rule 12).

The edges of the graph, for each rule:

| Rule | Start nodes | Edges | Removed |
|------|-------------|-------|---------|
| 8 reachability | `start` and the three `on-any-state` targets | each `on:` entry and each `limit.then` that names a state | none; `previous` adds no node, because it only goes back to a state that the issue visited |
| 9 path to finish | each state | each named `on:` entry and `limit.then`; `previous` goes to each state whose action does not wait for you | none |
| 10 cycles | the states whose action does not wait for you | a move X → L to a state L with a limit is a **bounded** edge; the same move also gives the **unbounded** edge X → `L.limit.then`; all the other moves (named and `previous`, which goes to each state whose action does not wait for you) are unbounded | the states that wait for you, and the bounded edges |

Rule 10: after the removal, each strongly connected component with two states or more, or with a self-edge, is a trap. The message lists the states of the component in YAML order. This rule refuses the trap of the Review Focus item 2, which the plain rule "a cycle has a state with a limit" accepts.

The placeholders of rule 12: `implement` `{{issue}}`; `fix`, `resume` `{{findings}}`; `continue` none; a review `{{issue}}`, `{{tree}}`, `{{base}}`; `risk` `{{issue}}`, `{{base}}`. The pattern is the pattern of `PromptLibrary` (`\{\{(\w+)}}`).

- [ ] **Step 1: Write the failing tests:**
  - `acceptsTheDefaultWorkflow`.
  - `refusesAnUnreachableState` → `factory.yaml: no state goes to the state security-scan`.
  - `countsTheOnAnyStateTargetsAsReachable` (a `blocked` that only `on-any-state` names loads).
  - `refusesAStateWithoutAPathToFinish` (review approved goes to `review-again`, which only goes back to `review`) → `factory.yaml: the state review has no path to the state done`.
  - `countsPreviousAsAPath` (`text-changed` with `approved: previous` loads).
  - `refusesACycleWithoutLimit` (implement without limit) → `factory.yaml: the cycle implement, build has no limit`.
  - `acceptsACycleThroughAWaitingState` (`blocked: { continue: previous }`).
  - `refusesACycleThroughTheThenOfALimit` (implement `limit: { entries: 3, then: build }`, build `failed: implement`) → `factory.yaml: the cycle implement, build has no limit`.
  - `refusesASelfCycleWithoutLimit` (`conflict-left: implement` on an implement without limit) → `factory.yaml: the cycle implement has no limit`.
  - `refusesAnUnknownPlaceholder` (`{{isue}}` in fix) → `factory.yaml: the prompt prompts/fix.md of the state implement has the placeholder {{isue}}; the prompt fix knows only {{findings}}`.
  - `refusesAPlaceholderOfAnotherPrompt` (`{{tree}}` in implement).
- [ ] **Step 2:** Run `./mvnw -q test -Dtest='WorkflowGraphTest,PromptPlaceholdersTest'`. Expected: FAIL.
- [ ] **Step 3:** Implement both classes. Use the algorithm of Tarjan for the strongly connected components.
- [ ] **Step 4:** Run the two tests and `DefinitionLoaderTest`. Expected: PASS.
- [ ] **Step 5:** Run `./mvnw verify`. Expected: BUILD SUCCESS.
- [ ] **Step 6:** Commit: `Refuse the traps of a workflow at the load`.

### Task 5: The engine of the state machine

The engine switches to the new model. After this task, the default workflow does what `main` does, with these differences of the spec: the waits are states, the counts of the limits go back to 0 on `continue` from the `block` target (spec 4.5), the coding prompt `resume` exists (spec 5.1), and Continue of the page also works in a state without an action. Use the most capable model for this task.

Out of scope of this task, and kept as before: six fixed columns on the page and the labels `factory:status:<column>` (Task 6), the four review names of `ReviewKind` (Task 7), the risk reasons (Task 8), the merge modes and the approved tree (Task 9), the retry properties (Task 10), the records of `main` (Task 11).

**Files:**
- Delete: all the `Legacy*` classes, `StepDefinition`, `Transition`, `TransitionReader`, `StepReader`, `LoopDefinition`, `workflow/TransitionRunner`, `workflow/FixLoop`, `workflow/FixLoopLimits`, `state/IssueStatus`, `state/WaitReason`, `state/LegacyPhase`, and their tests. Delete `src/test/resources/definitions/default/`: the tests load the jar copy.
- Replace: `src/main/resources/factory/factory.yaml` with the YAML of spec section 9. Create `src/main/resources/factory/prompts/resume.md` (an example; the default does not use it): it tells the agent "Continue the work of the issue." and gives `{{findings}}` in a fence, in the style of `fix.md`.
- Create: `workflow/MoveRunner.java`, `workflow/OnAnyStateMoves.java`, `workflow/ApprovalAction.java`, `workflow/ConversationAction.java`, `workflow/WaitAction.java`, `workflow/LimitCheck.java`, `workflow/IssueDefinitionFacts.java`, `state/PendingPrompt.java`, `state/ColumnLabels.java` (moved from the label code of `IssueStatus`).
- Modify: `state/IssueRecord.java`, `state/LiveSession.java`, `state/StatusComment.java`, `definition/BuiltInDefinition.java`, `definition/DefinitionLookup.java`, `definition/DefinitionVersions.java`, `definition/DefinitionVersion.java`, `workflow/IssueWorkflow.java`, `workflow/ActionSet.java`, `workflow/VersionServices.java`, `workflow/ImplementPrompt.java`, `workflow/CodingAction.java`, `workflow/LiveMode.java`, `workflow/IssueTextCheck.java`, `workflow/ApprovalCheck.java`, `workflow/FactoryFolder.java`, `workflow/StatusPublisher.java`, `workflow/MergedIssue.java`, `workflow/ForwardCheckFailure.java`, `workflow/FinishAction.java`, `workflow/MergeGateAction.java`, `workflow/StartAgainCheck.java`, `prompt/PromptTexts.java`, `prompt/PromptLibrary.java`, `worker/Worker.java`, `worker/RestartPreparation.java`, `worker/FinalStepResult.java`, `worker/LiveRecordCheck.java`, `worker/EnterRequests.java`, the start-again code of the worker, `github/GitHubClient.java`, `github/HttpGitHubClient.java`, `github/StatusLabelMemory.java`, `board/BoardState.java`, `web/BoardPage.java`, `web/CardRenderer.java`, `web/CardFingerprint.java`, `web/PageAction.java`, `web/PageModelSource.java`, `Main.java`, and the tests.
- Test: `workflow/MoveRunnerTest.java` (new), `workflow/IssueWorkflowTest.java`, `worker/WorkerTest.java`, `state/IssueRecordTest.java`, `state/StatusCommentTest.java`, `testing/RecordStates.java`, and the other tests that use the deleted types.

**Interfaces:**
- Consumes: the model of Tasks 3 and 4.
- Produces:
  - `IssueRecord` without `status`, `step`, `fixLoops`, and `waitReason`. New fields: `Optional<ActionType> action` (empty for a state without an action), `String column`, `Optional<String> previousState`, `Map<String, Integer> entries`, `Optional<PendingPrompt> pendingPrompt`, `boolean actionStarted`. New methods: `static IssueRecord queued(int issueNumber, String startState)` (state `startState`, action PREPARE, column `queued`, `previousState` = `startState`), `withMove(String state, Optional<ActionType> action, String column)` (sets `actionStarted` to false), `withPreviousState(String)`, `withEntryCounted(String state)`, `withoutEntries()`, `withPendingPrompt(PendingPrompt)`, `withActionStarted()`. Replace the repeated calls of the canonical constructor with one private copy helper, so that each `with` method is one or two lines.
  - `LiveSession(String startHeadCommit, Optional<String> link, LivePlace place)`: the fields `statusBefore` and `waitBefore` go away, because the conversation is a state now.
  - `enum PendingPrompt { IMPLEMENT, FIX, RESUME }`.
  - `final class ColumnLabels` with `static String labelName(String column)` (`factory:status:` + column in this task; Task 6 changes the prefix), `static boolean isColumnLabel(String label)`, `static String colorOf(String column)` (the six colors of the old `IssueStatus`; `c5def5` for another column).
  - `GitHubClient.setColumn(Issue issue, String column)` (was `setStatus`) and `ensureColumnLabels(List<String> columns)` (was `ensureStatusLabels`).
  - `MoveRunner.move(FactoryDefinition definition, ActionResult.Event event, Path worktree) : IssueRecord` and `MoveRunner.moveTo(FactoryDefinition definition, IssueRecord record, Target target, Path worktree) : IssueRecord`.
  - `OnAnyStateMoves` (public): `moveToBlockTarget(IssueRecord record, String note)`, `moveToEnterTarget(IssueRecord record)`, `moveToTextChangedTarget(IssueRecord record)`. Each move does nothing when the issue is already in the target (spec 4.4). When the definition of the record does not load, `moveToBlockTarget` keeps the state, adds the note, and sets the column `blocked`.
  - `IssueWorkflow.isWaiting(IssueRecord)` and `IssueWorkflow.isStartable(Issue, IssueRecord)`, see step 3.
  - `IssueDefinitionFacts.limitsOf(IssueRecord) : Map<String, Integer>` (state name → `limit.entries`; empty when the definition does not load). It replaces `FixLoopLimits`.
  - `StatusComment.render(IssueRecord record, Map<String, Integer> limits)`.
  - `ActionResult.Event` keeps its reason. `MoveRunner` writes the reason as a note of the record.
  - `PromptLibrary.resume(List<Finding> findings)`. `PromptTexts` gets the optional `resume` text.

The rules of the engine (spec 4.3, 4.5, 5.1, 6, 6.6):

1. **A move** (`MoveRunner`). Find the `on:` entry of the event (none: `IllegalStateException`). Resolve `ToPrevious` to `record.previousState()`. Write the reason of the event as a note. When the source state is the `block` target and the event is `continue`, call `withoutEntries()`. When the target is a coding state, set the pending prompt (rule 4). Call `withMove(target, its action type, its column)`. When the action of the target does not wait for you, call `withPreviousState(target)`. `afterConversation` is true only when the source action is `conversation`, the event is `continue`, and the target action is `coding`; a move to another state clears it.
2. **The start of an action** (`IssueWorkflow.advance`). The order is: (a) the unknown-session check, as now; (b) when the action is FINISH, run it; (c) when the action uses the issue text and the text changed, `moveToTextChangedTarget` (the action does not start); (d) when `actionStarted` is false and the state has a limit (`LimitCheck`): when `entries[state] + 1` is more than `limit.entries`, `moveTo(limit.then)` with the note `The state <name> reached its limit of <N> entries.`; else `withEntryCounted(state)`; (e) `withActionStarted()`; (f) run the action. An entry counts only when its action starts (spec 4.3). A result that the worker drops (block, Enter, stop) also drops the count.
3. **The waits.** `isWaiting(record)`: the action is FINISH, APPROVAL, or none, or the action is CONVERSATION and `record.live()` is present. `isStartable(issue, record)`: FINISH; an action that does not wait for you; APPROVAL with the label `factory:approve` on the issue; CONVERSATION without a live session, or with a continue request; no action with a continue request.
4. **The pending prompt** (`MoveRunner`, when the target is a coding state). Keep the pending prompt when the source is the `block` target, the `enter` target, or a state whose action waits for you and the target is `previous`. Else: `FIX` with the findings when the event has findings (`build` `failed`, `reviews` `rejected`, `merge-gate` `conflict`, `coding` `conflict-left`); `IMPLEMENT` when the source action is `prepare`; `RESUME` with the findings of the event for each other entry. `FactoryFolder.writePendingFindings(List<Finding>)` writes the findings to `.factory-work/pending-findings.json`. It replaces `writeFixPrompt`.
5. **The coding prompt** (`ImplementPrompt.choose`): no started session → `implement`; `afterConversation` → `continue`, then the rendered pending prompt when it is `FIX` or `RESUME`; `IMPLEMENT` or none → `implement`; `FIX` → `fix(findings)`; `RESUME` → `resume(findings)`. Missing findings for `FIX` or `RESUME` → `ActionResult.block` with `The findings of the pending prompt are missing.`
6. **The work moves into the actions** (spec 6.6). The coding action posts `QuestionComment` before it sends `question` (was in `TransitionRunner.askOwner`). The coding action calls `ownerApproval.withdraw` when it sends `changed` (was in `FixLoop.sendFindingsBack`).
7. **`ApprovalAction`.** In the `issue-text-changed` target: at the start (`actionStarted` false), withdraw an older `factory:approve`; when the label is on the issue, accept the new text (`withIssueTextHash`), withdraw the label, and send `approved`. In another approval state: send `approved` when the label is on the issue, and keep the label (the merge gate reads it, as on `main`). Else `stay`.
8. **`ConversationAction`.** Without a live session: open it with `LiveMode` (the tmux session, the comment with the link, the place of the page). A failure of the open gives `ActionResult.block` with `The live conversation did not open: <reason>`. With a continue request: close the session, delete the question, and send `changed` when the conversation changed the code (as `LiveMode.hasChangedInLiveMode`, where "the step is AGENT" becomes "the action of `previous` is CODING"); before `changed`, commit the uncommitted files, withdraw the approval, and clear the reviews, the risk, and the built tree. Else send `continue`. Else `stay`.
9. **`WaitAction`** (a state without an action): with a continue request, send `continue`; else `stay`. `LiveRecordPublisher` clears the continue request after it publishes a record that left a state without an action or a conversation state. `PageAction.CONTINUE` is possible when the record has a live session or no action, and no continue request exists.
10. **On any state** (`OnAnyStateMoves`, used by `Worker` and `IssueWorkflow`): a block (`Worker.finishBlock`) → `moveToBlockTarget(base, "The owner blocked the issue.").withoutLive()`; an unexpected failure (`Worker.blockOnFailure`, `IssueWorkflow.failAction`, a guard `ActionResult.Blocked`, the unloadable definition) → `moveToBlockTarget` with the note of today; Enter (`EnterRequests.enter`) → `moveToEnterTarget`, then one `advance` that opens the conversation, then the publish, as fast as on `main`. Enter keeps the notes of today for a finished issue and for an issue without a started session; the issue does not move then. The Enter stop of `main` (Task 1) stays: the worker stops the action, drops its result, and enters on the kept record.
11. **Start again** (spec 6.7): `StartAgainCheck.isPossible`: the state is the `block` target, no live session, and the action is not FINISH. The new record is `IssueRecord.queued(number, start)` with the definition commit of the old record. A merged pull request moves the issue to `finishState()`. (Owner decision of 2026-11-09: the new record takes the current version, not the version of the old record.)
12. **A broken record** (`Worker.blockBrokenRecord`): `IssueRecord.queued` in the start state of the current version, then `moveToBlockTarget` with the note of today. Without a current version, the worker does nothing (fail closed).
13. **The labels and the page in this task:** `StatusPublisher` calls `setColumn(issue, record.column())`. `BoardPage` keeps the six columns in a fixed list of column names (`queued`, `in-progress`, `in-review`, `waits-for-you`, `blocked`, `done`) and filters by `record.column()`. `BoardState` gives the done date to a record with the action FINISH. `CardRenderer` marks a card as waiting when `isWaiting`; it shows the newest note instead of the sentence of the wait reason. `PageAction.APPROVE` is possible when the action is APPROVAL. `PageAction.BLOCK` is possible when the action is present and is not FINISH. `CardFingerprint` uses the column, the state, the entries, the reviewed tree, and the issue text hash.
14. **The status comment:** the headline is `**Factory status:** <displayName(column)> — state <state>`. The counters line shows each state with a limit, for example `Entries: implement 1 of 3, review 0 of 10 · Risk: — · Pull request: —`.
15. **The restart** (`RestartPreparation`): an action with `needsWorktree()` and no worktree → the start state with a new session, as today. The entries stay.
16. **The pick up** (`Worker.waitsForThePickUp`): the record is in the column `queued`, the action is PREPARE, and no session started.

- [ ] **Step 1: Write the failing tests** in `MoveRunnerTest` (a fake `DefinitionFiles` with small workflows) and in `IssueWorkflowTest` and `WorkerTest` (the default workflow, with the fakes of `testing/`):
  - `movesToTheNamedStateAndSetsItsColumn`, `writesTheReasonOfTheEventAsANote`.
  - `setsPreviousOnlyForAStateThatDoesNotWait`, `resolvesPreviousToTheLastStateWithAnAction`.
  - `resetsTheEntriesOnContinueFromTheBlockTarget`, `keepsTheEntriesOnContinueFromTheEnterTarget`.
  - `setsFixForTheEventsWithFindings` (each of the four events), `setsImplementAfterPrepare`, `setsResumeForAnotherEvent`, `keepsThePendingPromptOnContinueFromTheBlockTarget`, `keepsThePendingPromptAfterTheTextApproval`.
  - `countsTheEntryWhenTheActionStarts`, `goesToThenAboveTheLimitAndKeepsPrevious` (the reviews reject at the limit of implement: the issue goes to `blocked`, `previousState` is `implement`, and `continue` runs implement with the fix prompt), `countsOneEntryAfterABlockDuringTheAction`, `doesNotCountTheEntryWhenTheTextChanged`.
  - `waitsInTheApprovalStateUntilTheLabel`, `acceptsTheNewTextAndRemovesTheLabel`, `withdrawsAnOlderApprovalWhenTheTextChanged`.
  - `opensTheConversationOnAQuestion`, `sendsChangedAfterACommitInTheConversation`, `sendsContinueWithoutAChange`, `blocksWhenTheConversationDoesNotOpen`.
  - `continuesABlockedIssueWithTheContinueButton`.
  - `entersAtOnceAndOpensTheConversation`, `stopsTheActionOnEnterAndEntersOnTheKeptRecord`.
  - `blocksTheIssueIntoTheBlockTarget`, `doesNothingWhenTheIssueIsInTheBlockTargetAlready`, `movesAnUnexpectedFailureToTheBlockTarget`.
  - `startsAgainOnlyFromTheBlockTarget`.
  - `rendersTheColumnAndTheEntries` (`StatusCommentTest`).
  - The existing tests of `IssueWorkflowTest` and `WorkerTest` keep their scenarios. Replace each assertion on `status()` and `waitReason()` with an assertion on `state()`, `column()`, and the newest note.
- [ ] **Step 2:** Run `./mvnw -q test -Dtest='MoveRunnerTest,IssueWorkflowTest,WorkerTest'`. Expected: compile failure or FAIL.
- [ ] **Step 3:** Implement the rules 1 to 16. Delete the replaced types.
- [ ] **Step 4:** Run `./mvnw verify`. Expected: BUILD SUCCESS. No test is disabled.
- [ ] **Step 5:** Run `grep -rn "IssueStatus\|WaitReason\|fixLoops\|StepType\|Legacy" src/`. Expected: no line.
- [ ] **Step 6:** Commit: `Run the issues on the state machine of .factory`.

### Task 6: Free columns and the labels `factory:is:<column>`

**Files:**
- Modify: `state/ColumnLabels.java`, `github/HttpGitHubClient.java`, `github/StatusLabelMemory.java` (rename to `ColumnLabelMemory`), `worker/Worker.java` (`ensureLabelsOnce`), `web/BoardPage.java`, `web/PageModel.java`, `web/PageModelSource.java`, `src/main/resources/web/page.css`, `Main.java`, `testing/FakeGitHubClient.java`.
- Test: `github/HttpGitHubClientTest.java`, `web/BoardPageTest.java`, `web/PageModelSourceTest.java`.

**Interfaces:**
- Consumes: `WorkflowDefinition.columns()`, `ColumnNames.displayName`, `DefinitionVersions.current()`.
- Produces: `ColumnLabels.labelName(column)` = `factory:is:<column>`. `isColumnLabel` is true for `factory:is:` and for the old prefix `factory:status:`, so that `setColumn` removes the old labels. `PageModel.columns() : List<String>`.

Rules (spec 4.6):
- `setColumn` creates the label of a column when the client did not create it yet (once for each name in the memory of the client), with `ColumnLabels.colorOf` and the description `Factory column: <display name>. The factory sets this label.`. `ensureLabelsOnce` no longer creates the six labels.
- The page shows `queued`, then the columns of the newest good version, then an extra column for each column of a card that is not in this list, in the order of the issue numbers. Without a current version, the page uses the columns of the built-in definition.
- The header of a column is `ColumnNames.displayName`. The CSS class of a column is `status-<column>`, so the six known colors stay. A card with a done date goes in its column by the date, the newest first; the other cards go by the issue number.

- [ ] **Step 1: Write the failing tests:** `setsTheLabelOfTheColumnAndRemovesTheOldStatusLabel`, `createsTheLabelOfANewColumnOnce`, `showsTheColumnsInTheOrderOfTheDefinition`, `showsACardOfAnUnknownColumnInAnExtraColumn`, `writesTheDisplayNameOfAFreeColumn` (`needs-me` → `Needs me`).
- [ ] **Step 2:** Run them. Expected: FAIL.
- [ ] **Step 3:** Implement the rules.
- [ ] **Step 4:** Run `./mvnw verify`. Expected: BUILD SUCCESS.
- [ ] **Step 5:** Commit: `Show the free columns of .factory and set the label factory:is`.

### Task 7: Reviews with free names

**Files:**
- Delete: `state/ReviewKind.java`.
- Modify: `state/IssueRecord.java` (`Map<String, Verdict> reviews`, in the order of the YAML), `state/StatusComment.java`, `prompt/PromptTexts.java`, `prompt/PromptLibrary.java`, `prompt/ReviewResultReader.java`, `workflow/ReviewRound.java`, `workflow/ReviewNote.java`, `workflow/FactoryFolder.java`, `workflow/PullRequestPublisher.java` and `workflow/PullRequestDescription.java` (when they name the reviews), `merge/MergeGate.java`, `web/CardRenderer.java`, `workflow/IssueDefinitionFacts.java`.
- Test: `workflow/ReviewRoundTest.java` or the review tests of `IssueWorkflowTest`, `merge/MergeGateTest.java`, `state/StatusCommentTest.java`, `prompt/PromptLibraryTest.java`, `prompt/ReviewResultReaderTest.java`.

**Interfaces:**
- Produces: `IssueDefinitionFacts.reviewNamesOf(IssueRecord) : List<String>` (the names of the reviews state, empty without one). `PromptLibrary.review(String name, Issue issue, String treeHash)`. `ReviewResultReader.read(Path worktree, String name)` reads `.factory-work/review-<name>.json`. `MergeGate.decide` gets the list of the review names.

Rules (spec 5.2): the reviews run in the order of the YAML. `allApprove` is true when each name of the definition has APPROVE. The findings start with `The review <name>: `. The status comment and the card show the names of the definition, with `—` for a name without a verdict.

- [ ] **Step 1: Write the failing tests:** `runsEachReviewOfTheDefinitionInOrder` (two reviews `security` and `style`), `rejectsWhenOneReviewHasFindings`, `refusesTheMergeWhenAReviewOfTheDefinitionDoesNotApprove`, `rendersTheReviewNamesOfTheDefinition`, `readsTheResultFileOfANamedReview`.
- [ ] **Step 2:** Run them. Expected: FAIL.
- [ ] **Step 3:** Implement the rules. Delete `ReviewKind`.
- [ ] **Step 4:** Run `./mvnw verify`. Expected: BUILD SUCCESS.
- [ ] **Step 5:** Commit: `Run the reviews that .factory names`.

### Task 8: The reasons of the risk

**Files:** `state/IssueRecord.java` (`List<String> riskReasons`), `workflow/RiskAction.java`, `state/StatusComment.java`, `web/CardRenderer.java`, and their tests.

**Interfaces:**
- Produces: `IssueRecord.withRisk(RiskLevel risk, boolean small, List<String> reasons)`. `withoutRisk()` also clears the reasons. The event `assessed` carries one finding: the title `The risk: <level>, <small or not small>` and the reasons as the detail, one line each. So `resume` gets `{{findings}}` (spec 5.1).

Rules (spec 5.3): the status comment shows the line `risk: high, not small` and one line `- <reason>` for each reason. Each new risk result replaces the reasons. The card shows the same lines.

- [ ] **Step 1: Write the failing tests:** `keepsTheRiskReasonsInTheRecord`, `rendersTheRiskAndItsReasons`, `replacesTheReasonsWithANewResult`, `givesTheRiskAsTheFindingsOfResume` (a workflow with `risk: { assessed: implement }` and `resume`).
- [ ] **Step 2:** Run them. Expected: FAIL.
- [ ] **Step 3:** Implement the rules.
- [ ] **Step 4:** Run `./mvnw verify`. Expected: BUILD SUCCESS.
- [ ] **Step 5:** Commit: `Show the reasons of the risk`.

### Task 9: The modes of the merge gate and the approval of the files

**Files:** `merge/MergeGate.java`, `merge/MergeDecision.java`, `workflow/MergeGateAction.java`, `workflow/ApprovalAction.java`, `state/IssueRecord.java` (`Optional<String> approvedTree`), the places that withdraw the approval (`CodingAction`, `BuildAction`, `ConversationAction`, `IssueTextCheck`), and their tests.

**Interfaces:**
- Consumes: `ActionDefinition.mergeMode()`, `WorkflowDefinition.findStateWithAction`.
- Produces: `MergeGate.decide(IssueRecord record, String currentTreeHash, boolean questionOpen, boolean approved, MergeMode mode, MergeChecks checks)` with `record MergeChecks(List<String> reviewNames, boolean hasBuild)`. `IssueRecord.withApprovedTree(String)`, `withoutApprovedTree()`.

Rules (spec 5.4, 5.5):
- `auto-merge`: only the check of an open question, then the merge.
- `risk` and `always-ask`: the four checks. The checks of the reviews apply only when the workflow has a reviews state; the check of the build applies only when it has a build state.
- `risk`: an automatic merge when the risk is `low` and the change is small; else `needs-approval`. `always-ask`: never an automatic merge.
- `approved` is true when `record.approvedTree()` equals the current tree, or when the label `factory:approve` is on the issue.
- An approval state that is not the `issue-text-changed` target writes `withApprovedTree(<tree of the worktree>)` when it sends `approved`.
- Each place that withdraws the label also calls `withoutApprovedTree()`.
- A change of `.factory` without an approval gives `factory-changed` in all three modes.

- [ ] **Step 1: Write the failing tests** (`MergeGateTest`, `IssueWorkflowTest`): `mergesWithAutoMergeWithoutReviewsAndBuild`, `refusesAutoMergeWithAnOpenQuestion`, `asksAlwaysWithAlwaysAsk`, `skipsTheBuildCheckWithoutABuildState`, `asksWithoutARiskState`, `mergesAtOnceWithAnApprovalGivenBefore`, `acceptsFactoryChangedForTheApprovedFiles`, `asksAgainAfterACodeChange`, `givesFactoryChangedAlsoWithAutoMerge`.
- [ ] **Step 2:** Run them. Expected: FAIL.
- [ ] **Step 3:** Implement the rules.
- [ ] **Step 4:** Run `./mvnw verify`. Expected: BUILD SUCCESS.
- [ ] **Step 5:** Commit: `Add the modes of the merge gate and record the approved files`.

### Task 10: The retries as system properties

**Files:** create `workflow/RetrySettings.java`; modify `workflow/ReviewSessionRetry.java`, `workflow/ForwardCheckFailure.java`, `workflow/MoveRunner.java`, `workflow/IssueWorkflow.java` (constructor), `Main.java`; tests `workflow/RetrySettingsTest.java`, `MainTest.java`, `IssueWorkflowTest.java`.

**Interfaces:**
- Produces: `record RetrySettings(int reviewSessions, int forwardChecks)` with `static RetrySettings fromSystemProperties()` (throws `IllegalArgumentException`) and `static RetrySettings defaults()` (2 and 3). `IssueWorkflow` gets a `RetrySettings` parameter.

Rules (spec 6.4):
- The properties are `factory.retries.review-session` (default `2`) and `factory.retries.forward-check` (default `3`).
- A value that is not a whole number of 1 or more stops the worker at the start. The message is `The system property factory.retries.review-session must be a whole number of 1 or more, but it is <value>.` `Main` reads the settings before it starts the worker, and exits with this message.
- The counts belong to one run of the action: each move of `MoveRunner` sets `failedReviewRounds` and `failedForwardChecks` to 0.
- The notes give the number: `The review sessions failed <N> times.`, `GitHub does not show the pushed commit ... after <N> tries.`

- [ ] **Step 1: Write the failing tests:** `readsTheDefaults`, `readsTheProperties`, `refusesZero`, `refusesAText`, `stopsTheWorkerOnAnInvalidProperty` (`MainTest`), `blocksAfterTheConfiguredReviewRetries`, `startsTheRetriesAgainAfterContinue`.
- [ ] **Step 2:** Run them. Expected: FAIL.
- [ ] **Step 3:** Implement the rules.
- [ ] **Step 4:** Run `./mvnw verify`. Expected: BUILD SUCCESS.
- [ ] **Step 5:** Commit: `Read the retry counts from system properties`.

### Task 11: The records of `main`

The issues that `main` runs today have records in the format of `main`. After the deploy, the worker must read them as records of the default workflow. A record of `main` has no definition commit, so it uses the built-in definition, which is the default workflow.

**Files:** create `state/MainRecordReader.java`; modify `state/StatusComment.java` (the read); test `state/MainRecordReaderTest.java`, `state/StatusCommentTest.java`.

**Interfaces:**
- Produces: `static Optional<IssueRecord> MainRecordReader.read(JsonNode json)`. `StatusComment.findRecord` uses it when the JSON has the property `phase` and no property `column`. A record that neither reader reads stays a broken record (fail closed).

The map (spec 9.1):

| `main` | new record |
|--------|------------|
| `phase` PREPARE, IMPLEMENT, BUILD, REVIEW, RISK, MERGE, DONE | `state` `prepare`, `implement`, `build`, `review`, `risk`, `merge`, `done`; `previousState` = this state |
| `waitReason` QUESTION or LIVE_CONVERSATION, or a `live` session | `state` `live` |
| `waitReason` MERGE_NEEDS_APPROVAL | `state` `waits-for-approval` |
| `waitReason` FACTORY_CHANGE_NEEDS_APPROVAL | `state` `factory-change-approval` |
| `waitReason` ISSUE_TEXT_CHANGED | `state` `text-changed` |
| `waitReason` BLOCKED_BY_OWNER, FIX_LOOP_LIMIT, FAILURE | `state` `blocked` |
| `status` QUEUED | `column` `queued` |
| `fixLoops` N, in a phase after IMPLEMENT | `entries` `{ implement: N + 1 }` (the entries count the first entry) |
| `reviews` `{ CORRECTNESS: APPROVE, ... }` | `reviews` `{ correctness: APPROVE, ... }` |
| `live.statusBefore`, `live.waitBefore` | dropped |
| the other fields | the same name and value |

The column and the action come from the state of the built-in definition. `actionStarted` is false, so a restarted action counts its entry again only for a state with a limit.

- [ ] **Step 1: Write the failing tests:** `readsARunningImplementRecordOfMain`, `readsAWaitingMergeRecordOfMain`, `readsABlockedRecordOfMain`, `readsALiveRecordOfMain`, `readsAQueuedRecordOfMain`, `keepsABrokenRecordBroken`. Each test uses a JSON text that `main` at 36158a4 writes (copy one from `git show origin/main:src/test/java/com/meterian/factory/state/StatusCommentTest.java` or render one with the record of `main`).
- [ ] **Step 2:** Run them. Expected: FAIL.
- [ ] **Step 3:** Implement the map.
- [ ] **Step 4:** Run `./mvnw verify`. Expected: BUILD SUCCESS.
- [ ] **Step 5:** Commit: `Read the records of main as records of the default workflow`.

### Task 12: The documents

**Files:** `docs/factory-definition.md` (rewrite), `README.md` (the sections ".factory", "Live mode", "Web page"), `src/main/resources/factory/prompts/resume.md` (check the text).

The reference describes only the new format (spec section 11): the folder, the main file, the workflow (`start`, `on-any-state`, the states), a state (`column`, `action`, `limit`, `on`), the events and `previous`, each action type with its events and prompts, the limits, the columns and the labels, the merge modes and the approvals, the run-time rules (Enter, block, failure, retries, text change, Start again), the loader rules with one example message each, the default workflow, and the examples of spec section 10. It is a reference, not the full user guide: that guide is a follow-up issue after the implementation (owner decision).

- [ ] **Step 1:** Rewrite `docs/factory-definition.md`. Each YAML example in it must load: add the test `DocumentExamplesTest` that extracts each `yaml` block that starts with `version: 1` from `docs/factory-definition.md` and loads it with `DefinitionLoader` and the prompt files of the jar.
- [ ] **Step 2:** Update `README.md`: the labels `factory:is:<column>`, the Continue button for a state without an action, the system properties of the retries, and the words state, action, and event.
- [ ] **Step 3:** Run `./mvnw verify`. Expected: BUILD SUCCESS.
- [ ] **Step 4:** Commit: `Describe the state machine of .factory`.

## After the last task

- The controller runs the final review of the whole branch, then asks the owner before the push and the pull request to `workflow-definitions` (assignee bbossola).
- After the implementation, the controller creates the follow-up issue of the full user guide (owner decision, assignee bbossola).
