# Factory plan 2: live mode — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The owner can enter the conversation of the agent in charge of an issue, from the Claude phone app or claude.ai/code, discuss it, and give the issue back with `/factory:continue`. A question of the agent opens this conversation by itself, and a blocked issue can start again this way.

**Architecture:** A new `LiveSessions` interface (agent package) opens the same Claude session in tmux with `--remote-control`, a factory plugin, and a hook, reads the Remote Control link from the tmux screen, and detects `/factory:continue` through a marker file. A new `LiveMode` class (workflow package) changes the record when the issue enters and leaves live mode. The Worker reads the label `factory:enter` and the marker, and starts the right step.

**Tech Stack:** Java 21, Maven, tmux, jq (for the hook), the `claude` CLI. No new Java dependency.

**Spec:** `docs/superpowers/specs/2026-11-01-factory-design.md`, section 8 (live mode), section 7.2 (questions), section 13 (spike results).

## Global Constraints

- All the rules of plan 1 stay: subscription only, no API key in a session, fail closed, push only `factory/*`, Simplified Technical English, clean code, TDD (RED then GREEN).
- The live session uses the same session UUID as the agent in charge: `claude --resume <uuid> --remote-control "Fix #<n>"`.
- The command is `/factory:continue` (a plugin command). Never `/continue`: it is a built-in command and never reaches a hook.
- The worker never stops a phase in the middle to enter. It waits until the phase ends.
- Only one of the worker and the owner talks to a session at a time. While the live session is open, the worker starts no phase for the issue.
- `/factory:continue` is never a merge approval. Only `factory:approve` approves a merge.
- If the files changed in live mode, the build, the four reviews, and the risk check run again. If not, the issue goes back to where it stopped. The fix-loop count does not go back to 0.
- The environment of the live session has no credential variable: the same filter as `ClaudeCommand.environment`, applied with `env -i`, so the environment of the tmux server cannot add one.
- The worker never edits `~/.claude.json` or another configuration file of Claude Code. It answers the trust prompt on the tmux screen.
- Only the accounts of `accounts.allowed` can give `factory:enter`.

## Review Focus

1. The Remote Control line or the trust prompt does not appear (another CLI version, no network): the open fails after a time limit, the tmux session ends, and the issue gets a note; it is never stuck in "live" with no link. Test in Task 3.
2. The tmux server of the owner holds `ANTHROPIC_API_KEY` in its global environment: the live session must not see it. Test in Task 3.
3. The worker restarts while a live session is open: the tmux session stays, the record still shows live mode, and `/factory:continue` still works. Test in Task 5.
4. The owner adds `factory:enter` while a phase runs: the phase ends normally, then live mode opens; the phase is not stopped. Test in Task 5.
5. The agent changes files in live mode and the owner continues: the worker commits the changes, and the build and all reviews run again before any merge. Test in Task 4.

---

## File structure

```
src/main/java/com/meterian/factory/
  state/       LiveSession (new record), WaitReason (+ LIVE_CONVERSATION), IssueRecord (+ live, afterConversation)
  agent/       LiveSessions (interface), TmuxLiveSessions, LiveFiles, TmuxCommand
  workflow/    LiveMode (new), IssueWorkflow (enter/continue entry points), FactoryFolder (+ deleteQuestion)
  worker/      Worker (+ factory:enter, the marker, the ended session), PhaseStopper (+ close the live session)
  configuration/ FactoryConfiguration (+ liveDirectory), ConfigurationLoader (+ live-directory)
  Main         (+ tmux and jq checks, the wiring)
src/main/resources/
  live/        plugin.json, continue.md (the command), continue-hook.sh
  prompts/     continue.md (the prompt after a conversation)
src/test/java/com/meterian/factory/testing/FakeLiveSessions.java
src/test/resources/fake-claude-live.sh
```

---

### Task 1: The live state of an issue

**Files:**
- Create: `src/main/java/com/meterian/factory/state/LiveSession.java`
- Modify: `state/WaitReason.java`, `state/IssueRecord.java`, `state/StatusComment.java`
- Test: `state/IssueRecordTest.java`, `state/StatusCommentTest.java`

**Interfaces:**
- Produces:
  - `record LiveSession(String startHeadCommit, IssueStatus statusBefore, Optional<WaitReason> waitBefore, Optional<String> link)` — the state of the issue just before it entered, and the Remote Control link (empty while the session starts, or after it ended without `/factory:continue`).
  - `WaitReason.LIVE_CONVERSATION` with the sentence "You are in a live conversation with the agent. Type /factory:continue when you are done."
  - `IssueRecord` gets two new last components: `Optional<LiveSession> live` and `boolean afterConversation`. Methods: `withLive(LiveSession)`, `withoutLive()`, `withLiveLink(String)`, `withoutLiveLink()`, `withAfterConversation()`, `withoutAfterConversation()`. `queued` gives empty and false. The compact constructor turns a null `live` into `Optional.empty()` (old records still parse).
  - `StatusComment.render`: when `live` has a link, the human part has the line `Live conversation: <link>`.

- [ ] **Step 1: Write the failing tests**

```java
// IssueRecordTest
@Test void anOldRecordWithoutTheLiveFieldsReadsAsNotLive()   // JSON of a record without "live" and "afterConversation" → live empty, afterConversation false
@Test void withLiveKeepsTheStateBeforeTheConversation()      // withLive(new LiveSession("abc", IN_REVIEW, Optional.of(MERGE_NEEDS_APPROVAL), Optional.empty())) → live().get().statusBefore() == IN_REVIEW
@Test void withLiveLinkSetsOnlyTheLink()
@Test void withoutLiveClearsTheLiveState()
// StatusCommentTest
@Test void showsTheLinkOfTheLiveConversation()               // body contains "Live conversation: https://claude.ai/code/session_x"
@Test void aLiveRecordReadsBackEqual()                       // render then findRecord → equal record
```

- [ ] **Step 2: Run the tests and see them fail** — `./mvnw -q test -Dtest='IssueRecordTest,StatusCommentTest'`. Expected: compilation errors.
- [ ] **Step 3: Implement** `LiveSession`, the enum value, the two components (update every `with...` method and `queued`), and the line in `render`.
- [ ] **Step 4: Run the tests and see them pass.**
- [ ] **Step 5: Commit** — "Add the live state of an issue".

---

### Task 2: The factory plugin, the hook, and the live files

**Files:**
- Create: `src/main/resources/live/plugin.json`, `src/main/resources/live/continue.md`, `src/main/resources/live/continue-hook.sh`
- Create: `src/main/java/com/meterian/factory/agent/LiveFiles.java`
- Modify: `configuration/FactoryConfiguration.java`, `configuration/ConfigurationLoader.java`, `factory.example.yaml`
- Test: `agent/LiveFilesTest.java`, `configuration/ConfigurationLoaderTest.java`

**Interfaces:**
- Produces:
  - `FactoryConfiguration` gets the component `Path liveDirectory` (YAML key `live-directory`, default `<work-directory>/live`).
  - `final class LiveFiles { LiveFiles(Path liveDirectory); void install(); Path pluginDirectory(); Path settingsFile(int issueNumber); Path continueMarker(int issueNumber); boolean isContinueRequested(int issueNumber); void clearContinueRequest(int issueNumber) }`.
  - `install()` writes, every time and atomically (temporary file + move, as `Gatekeeper.writeHook`): `<live>/plugin/.claude-plugin/plugin.json` (`{"name":"factory","version":"1.0.0","description":"The commands of the claude-ai-factory"}`), `<live>/plugin/commands/continue.md`, and `<live>/continue-hook.sh` (executable).
  - `settingsFile(n)` writes `<live>/issue-<n>.settings.json` with one `UserPromptSubmit` hook whose command is `<live>/continue-hook.sh <live>/issue-<n>.continue`.
  - `continueMarker(n)` is `<live>/issue-<n>.continue`. The marker is outside the worktree, so the agent does not see it in its folder.

The hook script (the exact behavior; the copy is up to the builder):
- It reads the JSON of the hook from the standard input and takes `.prompt` with `jq -r '.prompt // ""'`.
- When the prompt, after leading blanks, starts with `/factory:continue`, it writes the date to the marker path (its first argument), prints "The factory takes the issue back. You can close this conversation." to the standard error, and exits with code 2 (this blocks the prompt).
- Otherwise it exits with code 0 and writes nothing.

`commands/continue.md` (the plugin command; the hook blocks it before the model sees it, so its text matters only if the hook fails):
```
---
description: Give the issue back to the factory
---
Tell the owner: "The factory did not get the request. Type /factory:continue again." Do nothing else.
```

- [ ] **Step 1: Write the failing tests**

```java
// LiveFilesTest (runs the real hook script with bash and jq)
@Test void installWritesThePluginAndAnExecutableHook()
@Test void theHookWritesTheMarkerAndBlocksTheContinueCommand()   // stdin {"prompt":"/factory:continue"} → exit 2, marker exists
@Test void theHookAcceptsASpaceAfterTheCommand()                 // "/factory:continue " (the phone adds it) → exit 2, marker
@Test void theHookLetsAnotherPromptPass()                        // "What is the plan?" → exit 0, no marker
@Test void theHookIgnoresTheCommandInsideASentence()             // "please /factory:continue" → exit 0, no marker
@Test void theSettingsFileNamesTheHookAndTheMarkerOfTheIssue()   // JSON parse: hooks.UserPromptSubmit[0].hooks[0].command ends with "issue-7.continue"
@Test void clearContinueRequestDeletesTheMarker()
// ConfigurationLoaderTest
@Test void theLiveDirectoryDefaultsToTheWorkDirectory()          // <work-directory>/live
```

- [ ] **Step 2: Run them and see them fail.**
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run them and see them pass.**
- [ ] **Step 5: Commit** — "Add the factory plugin and the continue hook for live mode".

---

### Task 3: Live sessions in tmux

**Files:**
- Create: `agent/LiveSessions.java`, `agent/TmuxLiveSessions.java`, `agent/TmuxCommand.java`, `agent/LiveSessionException.java`
- Create: `src/test/resources/fake-claude-live.sh`, `src/test/java/com/meterian/factory/testing/FakeLiveSessions.java`
- Test: `agent/TmuxLiveSessionsTest.java`

**Interfaces:**
- Consumes: `LiveFiles` (Task 2), `ClaudeSettings`, `ClaudeCommand.environment` (plan 1).
- Produces:
  - `interface LiveSessions { String open(int issueNumber, UUID sessionId, Path worktree) throws LiveSessionException; boolean isOpen(int issueNumber); void close(int issueNumber); boolean isContinueRequested(int issueNumber); void clearContinueRequest(int issueNumber) }` — `open` returns the Remote Control link.
  - `final class TmuxLiveSessions implements LiveSessions { TmuxLiveSessions(ClaudeSettings settings, LiveFiles liveFiles, Map<String, String> parentEnvironment, Duration openTimeout) }` — production timeout 90 seconds.
  - `final class TmuxCommand` — runs `tmux` with arguments and a timeout of 10 seconds; returns stdout; fails with `LiveSessionException` that names the command.
  - `FakeLiveSessions implements LiveSessions` — in memory: `openCalls()`, `closeCalls()`, `requestContinue(int)`, `endSession(int)` (makes `isOpen` false), `failTheNextOpen()`, and a fixed link `https://claude.ai/code/session_test<n>`.

The tmux session name is `factory-issue-<n>`. `open` does, in this order:
1. `clearContinueRequest(n)`; if a tmux session with the name exists, close it.
2. `tmux new-session -d -s factory-issue-<n> -x 200 -y 50 -c <worktree> <command>`, where `<command>` is one shell-quoted string: `env -i <each variable of ClaudeCommand.environment(parentEnvironment), quoted> <claude command> --resume <uuid> --remote-control "Fix #<n>" --settings <liveFiles.settingsFile(n)> --plugin-dir <liveFiles.pluginDirectory()> --allowedTools <each> --disallowedTools <each>`.
3. Poll `tmux capture-pane -p -t factory-issue-<n>` every 500 ms until `openTimeout`:
   - When the screen contains "trust this folder", send `Down` then `Enter` once (`tmux send-keys`).
   - When the screen matches `https://claude\.ai/code/session_[A-Za-z0-9]+`, return that link.
4. On the timeout, or when the tmux session ends: close it and throw `LiveSessionException` "The live session did not show a Remote Control link in 90 seconds." (or "...ended before it showed a link.").

`close(n)` runs `tmux kill-session -t factory-issue-<n>` and ignores "can't find session". `isOpen(n)` runs `tmux has-session -t factory-issue-<n>`.

The tests use the real tmux with a private server: every tmux call of the class adds `-L <socket name>` given in a package-private constructor (production: the default server). `fake-claude-live.sh` writes its arguments and its environment to files, prints "Quick safety check: ... Yes, I trust this folder", waits for a line on the standard input, prints "/remote-control is active · Continue here, on your phone, or at https://claude.ai/code/session_FAKE123", and then sleeps.

- [ ] **Step 1: Write the failing tests**

```java
@Test void openAnswersTheTrustPromptAndReturnsTheLink()     // link == "https://claude.ai/code/session_FAKE123"
@Test void openResumesTheSameSessionWithTheFactoryFiles()   // the arguments contain --resume <uuid>, --remote-control, "Fix #7", --settings <...issue-7.settings.json>, --plugin-dir
@Test void theSessionGetsNoCredentialVariable()              // parent env with ANTHROPIC_API_KEY, GH_TOKEN; also `tmux -L <socket> set-environment -g ANTHROPIC_API_KEY x` before the open → the env file has neither
@Test void openFailsAndClosesWhenNoLinkAppears()             // a fake that never prints the link, timeout 2 s → LiveSessionException, isOpen false
@Test void openReplacesAnOldSessionOfTheIssue()
@Test void closeEndsTheSession()                             // isOpen false after close; close of a missing session does not throw
@Test void isContinueRequestedReadsTheMarkerOfTheHook()      // write the marker through LiveFiles → true; clear → false
```

- [ ] **Step 2: Run them and see them fail.**
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run them and see them pass** (`./mvnw -q test -Dtest=TmuxLiveSessionsTest`).
- [ ] **Step 5: Commit** — "Open the session of the agent in tmux with Remote Control".

---

### Task 4: Enter and continue in the workflow

**Files:**
- Create: `workflow/LiveMode.java`, `src/main/resources/prompts/continue.md`
- Modify: `workflow/IssueWorkflow.java` (keep it under its current size: the new logic is in `LiveMode`), `workflow/FactoryFolder.java` (+ `deleteQuestion()`), `prompt/PromptLibrary.java` (+ `continueAfterConversation()`)
- Test: `workflow/LiveModeTest.java`, `workflow/IssueWorkflowTest.java`

**Interfaces:**
- Consumes: `LiveSessions` and `FakeLiveSessions` (Task 3), the record of Task 1.
- Produces:
  - `IssueWorkflow` constructor gets `LiveSessions liveSessions` as a new last parameter.
  - `public IssueRecord enterLiveMode(IssueRecord record)` — reads the issue, runs `LiveMode.enter`, publishes, returns the record.
  - `public static boolean isLive(IssueRecord record)` — `record.live().isPresent()`.
  - `final class LiveMode { IssueRecord enter(Issue issue, IssueRecord record, Path worktree); IssueRecord continueWork(Issue issue, IssueRecord record, Path worktree) }`.
  - `PromptLibrary.continueAfterConversation()` renders `prompts/continue.md`: "The owner discussed the issue with you in a live conversation. Continue the work of the issue with what you agreed with the owner." plus the same rules as `implement.md` (scope, description, no commit, `.factory/` only, the question rules).

`LiveMode.enter` rules:
- The record must have `agentSessionStarted` true and a worktree; otherwise add the note "The factory opens a live conversation only after the first implement step." and return the record (no live state).
- A record in phase DONE: add the note "The issue is done; a live conversation has no effect." and return it.
- A live record with a link whose session is still open (`liveSessions.isOpen`): add the note "The live conversation is already open: <link>" and return it (no second session).
- A live record whose link is empty (the session ended without `/factory:continue`): open again, and keep its `LiveSession` before-state (`startHeadCommit`, `statusBefore`, `waitBefore`); set only the new link.
- Otherwise: `liveSessions.open(...)`; on success return `record.withLive(new LiveSession(git.headCommit(worktree), record.status(), record.waitReason(), Optional.of(link))).withStatus(WAITS_FOR_YOU).withWaitReason(LIVE_CONVERSATION)`, and post one comment: "The live conversation is open: <link>\n\nType /factory:continue in the conversation when you are done.\n\n@<first allowed account>". On `LiveSessionException`: add the note "The live conversation did not open: <message>" and return the record unchanged otherwise.

`LiveMode.continueWork` rules, in this order (only when `liveSessions.isContinueRequested(n)`):
1. `liveSessions.close(n)`; `clearContinueRequest(n)`; `new FactoryFolder(worktree).deleteQuestion()`.
2. If the worktree has uncommitted changes, or `git.headCommit(worktree)` differs from `startHeadCommit`: `git.commitAll(worktree, ChangeTitle.describe(issue))` when there are changes; then `withoutReviews().withoutRisk().withoutBuiltTree().withPhase(BUILD).withStatus(IN_PROGRESS)`. The approval label is withdrawn (an approval covers one diff).
3. Else, if `waitBefore` was `MERGE_NEEDS_APPROVAL` or `ISSUE_TEXT_CHANGED`: restore `statusBefore` and `waitBefore` (continue is never an approval).
4. Else, if the phase is `IMPLEMENT`: `withAfterConversation()`, status `IN_PROGRESS`, no wait reason (the agent runs again with the continue prompt).
5. Else: no wait reason; status `IN_PROGRESS` for PREPARE, IMPLEMENT, BUILD; `IN_REVIEW` for REVIEW, RISK, MERGE (a BLOCKED issue starts again here).
6. In all cases: `withoutLive()`; `fixLoops` stays; no note (the conversation is the record of the discussion).

Other changes:
- `askOwner` (the question path) opens live mode at once: after the question comment, it returns `liveMode.enter(...)` of the waiting record. When the open fails, the issue stays in WAITS_FOR_YOU + QUESTION with the note.
- `runStep`: a live record whose continue is requested runs `continueWork`; a live record without the request returns the record unchanged.
- `implement`: when `afterConversation` is true, the prompt is `prompts.continueAfterConversation()`, the session is resumed (`newSession` false), and after the run the flag is cleared.
- `merge`: `questionOpen` stays as it is (the question file is deleted at continue).

- [ ] **Step 1: Write the failing tests** (IssueWorkflowTest and LiveModeTest, with FakeLiveSessions)

```java
@Test void aQuestionOpensTheLiveConversation()                // agent.thenAskQuestion → WAITS_FOR_YOU, LIVE_CONVERSATION, live link present, a comment with the link and "@bbossola"
@Test void aFailedOpenKeepsTheQuestionWaiting()                // failTheNextOpen → WAITS_FOR_YOU, QUESTION, note "The live conversation did not open"
@Test void continueWithoutChangesRunsTheAgentAgainWithTheContinuePrompt()  // after a question; requestContinue → next advance runs the agent with newSession false and the continue prompt; question file gone
@Test void continueWithChangesBuildsAndReviewsAgain()          // during live, write a file in the worktree; requestContinue → phase BUILD, reviews cleared; then all reviews run again before the merge
@Test void continueIsNeverAMergeApproval()                     // enter from MERGE_NEEDS_APPROVAL, no change, continue → WAITS_FOR_YOU + MERGE_NEEDS_APPROVAL again; no squash merge
@Test void continueStartsABlockedIssueAgain()                  // BLOCKED + FIX_LOOP_LIMIT in REVIEW, enter, continue without change → IN_REVIEW, phase REVIEW, fixLoops unchanged
@Test void noPhaseRunsWhileTheConversationIsOpen()             // live record without a continue request → advance returns it unchanged; agent not called
@Test void enterBeforeTheFirstImplementAddsANote()
@Test void enterOnADoneIssueAddsANote()
```

- [ ] **Step 2: Run them and see them fail.**
- [ ] **Step 3: Implement** `LiveMode`, the entry points, the prompt, and `deleteQuestion`.
- [ ] **Step 4: Run them and see them pass.**
- [ ] **Step 5: Commit** — "Enter a live conversation and continue after it".

---

### Task 5: The worker reads factory:enter and the continue request

**Files:**
- Modify: `worker/Worker.java` (move new logic to a small `worker/EnterRequests.java`), `worker/PhaseStopper.java`
- Test: `worker/WorkerTest.java`

**Interfaces:**
- Consumes: `IssueWorkflow.enterLiveMode`, `IssueWorkflow.isLive` (Task 4), `LiveSessions` (Task 3).
- Produces: `Worker` constructor gets `LiveSessions liveSessions` as a new last parameter; `PhaseStopper` constructor gets `LiveSessions` too, and `stopIssue(n)` also closes the live session.

Behavior in `findStartableRecord` (after the block check):
1. **Enter request:** the issue has `factory:enter` from an allowed account (`requests.isGivenByAllowedAccount`; else `noteIgnoredLabel` once, as for block).
   - If the issue is in work: remember the request (`EnterRequests`); the run loop of the issue checks it before each `advance` and ends the loop after the current step (the step is never stopped). When the loop ends, the worker calls `workflow.enterLiveMode(kept)` and removes the label.
   - If the issue is not in work: call `workflow.enterLiveMode(record)` at once and remove the label `factory:enter`.
2. **Continue:** a live record is startable only when `liveSessions.isContinueRequested(n)`.
3. **Ended session:** a live record with a link, no continue request, and `!liveSessions.isOpen(n)`: write `withoutLiveLink()` and the note "The live conversation ended without /factory:continue. Add the label factory:enter to open a new one." once. The issue stays in WAITS_FOR_YOU + LIVE_CONVERSATION. A new `factory:enter` opens a new session (Task 4 `enter` accepts a live record whose link is empty).
4. A block (`factory:block`) on a live issue closes the session through `PhaseStopper.stopIssue`, then blocks as today.
5. The shutdown hook and an API-key stop never close a live session: tmux keeps it, and a restarted worker finds it through the record and `isOpen`.

- [ ] **Step 1: Write the failing tests** (WorkerTest with FakeLiveSessions)

```java
@Test void enterOpensTheConversationOfAWaitingIssue()          // issue in WAITS_FOR_YOU + MERGE_NEEDS_APPROVAL, label factory:enter by bbossola → open called once, label removed, LIVE_CONVERSATION
@Test void enterWaitsForTheRunningPhaseToEnd()                 // agent in a phase that waits; add factory:enter → stopPhase never called; after the phase ends → open called; the result of the phase is kept
@Test void enterFromAnotherAccountIsIgnored()                  // note once, no open
@Test void continueStartsTheIssueAgain()                       // live issue, requestContinue → the next poll advances it
@Test void anEndedSessionAddsANoteOnce()                       // endSession → note once after 2 polls; a new factory:enter opens again
@Test void aRestartFindsTheOpenConversation()                  // worker 1 enters; worker 2 on the same fakes; requestContinue → worker 2 continues
@Test void blockClosesTheLiveSession()
@Test void shutDownKeepsTheLiveSession()                       // closeCalls empty after shutDown
```

- [ ] **Step 2: Run them and see them fail.**
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run them and see them pass.**
- [ ] **Step 5: Commit** — "Read factory:enter and the continue request in the worker".

---

### Task 6: Main, the checks, and the documents

**Files:**
- Modify: `Main.java`, `README.md`, `docs/superpowers/specs/2026-11-01-factory-design.md` (section 8), `factory.example.yaml`
- Test: `MainTest.java`

- [ ] **Step 1: Write the failing tests** — `MainTest`: `verifyCommands` names `tmux` and `jq` when one of them is missing.
- [ ] **Step 2: Run them and see them fail.**
- [ ] **Step 3: Implement:**
  - `verifyCommands` checks `tmux` and `jq` too.
  - Main calls `LiveFiles.install()` before the first poll and builds one `TmuxLiveSessions` (timeout 90 s) for the workflow, the worker, and the phase stopper.
  - README: a "Live mode" section: how to enter (`factory:enter`, or a question), where the link appears, `/factory:continue`, what happens after it (the rules of Task 4), and the limits of Remote Control (section 8.3).
  - Spec section 8: the marker is `<live-directory>/issue-<n>.continue` (outside the worktree), the hook needs `jq`, the worker answers the trust prompt on the screen, `env -i` for the environment, the rules after continue (Task 4), and that the shutdown keeps a live session.
- [ ] **Step 4: `./mvnw verify`.** Expected: BUILD SUCCESS, 0 failures.
- [ ] **Step 5: Commit** — "Wire live mode in the command line and describe it".

---

### Task 7: Pilot of live mode (the owner decides)

This task changes GitHub and needs the owner on the phone. Do each step only after the owner says yes.

- [ ] **Step 1:** Ask the owner for one small Heidi Fix issue that needs a decision (the agent will ask), or use `factory:enter` on any issue. Use the merge target `factory-pilot`.
- [ ] **Step 2:** Run the worker (same setup as the plan 1 pilot). The owner opens the link on the phone, discusses, and types `/factory:continue`.
- [ ] **Step 3:** Report each step and each defect: the link in the comment, the trust prompt, the continue, the next phase, the result.
