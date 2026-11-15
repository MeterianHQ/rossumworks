# Issue ownership by assignee: implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A factory works only on the issues that the GitHub assignee gives to its worker login, and it continues the pushed work of an earlier owner.

**Architecture:** A new package `com.meterian.factory.ownership` holds the pure rule (`OwnershipRule`), the claim at the pick up (`IssueClaim`), and the check inside a running step (`OwnershipGuard`). The worker uses the claim at each poll, and two new worker classes write the stop (`OwnershipRelease`) and the continuation (`OwnershipTakeover`). The record gets one new field, `handOver`, so that a stopped record and a continuation survive a restart.

**Tech Stack:** Java 21, Maven (`./mvnw verify`), JUnit 5, Jackson, the GitHub REST API.

**Spec:** `docs/superpowers/specs/2026-11-15-issue-ownership-design.md`

## Global Constraints

- The build needs a JDK 21 or later. Build and test with `./mvnw verify`.
- The package root is `com.meterian.factory`. Do not add `module-info.java` files.
- Write code comments, commit messages, and documents in Simplified Technical English (ASD-STE100).
- Apply the single responsibility principle to each class and each method.
- A method name is a verb. Do not use truncated names (`ctx`, `repo`, `dir`, `e`).
- A code comment has one line, and only for complex code. Javadoc can have more lines.
- Compare GitHub logins without case (`equalsIgnoreCase`), as `RequestReader` does.
- The labels keep the rule of `accounts.allowed`. An assignee change from any account counts.
- A running agent or build never stops because of an assignee change.
- End each commit message with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## Review Focus

1. The token cannot assign the issue: GitHub answers 201 and ignores the assignee. The factory must not work on the issue, and must log a warning. Test: Task 3, `claimRefusesAnIssueWhenGitHubIgnoresTheAssignee`.
2. The assignee login and the token login differ only in case (`BBossola`, `bbossola`). The factory is the owner. Test: Task 2, `ownerMatchesTheLoginWithoutCase`.
3. A status record from before this change has no field `handOver`. It must parse, with an empty hand-over. Test: Task 4, `recordWithoutHandOverReadsAsEmpty`.
4. The owner changes while a live session is open. The session stays open, and no step starts after it. Test: Task 8, `liveSessionStaysOpenWhenTheAssigneeChanges`.
5. Many polls see the same new assignee. The stop comment appears only once. Test: Task 7, `releaseWritesTheStopCommentOnce`.

---

### Task 1: The assignees in the GitHub client

**Files:**
- Modify: `src/main/java/com/meterian/factory/github/Issue.java`
- Create: `src/main/java/com/meterian/factory/github/AssignmentEvent.java`
- Modify: `src/main/java/com/meterian/factory/github/GitHubClient.java`
- Modify: `src/main/java/com/meterian/factory/github/HttpGitHubClient.java` (`toIssue`, new calls)
- Modify: `src/test/java/com/meterian/factory/testing/FakeGitHubClient.java`
- Modify: `src/test/java/com/meterian/factory/testing/FakeGitHubServer.java` (only if the HTTP tests need new routes)
- Test: `src/test/java/com/meterian/factory/github/HttpGitHubClientTest.java`, `src/test/java/com/meterian/factory/testing/FakeGitHubClientTest.java`

**Interfaces:**
- Produces: `record Issue(int number, String title, String body, Set<String> labels, Set<String> assignees)`, plus the constructor `Issue(int number, String title, String body, Set<String> labels)` that sets `assignees` to `Set.of()`. The compact constructor copies `assignees` with `Set.copyOf`.
- Produces: `record AssignmentEvent(long id, String assignee, Instant createdAt)`. It is one "assigned" event.
- Produces on `GitHubClient`:
  - `void addAssignee(int issueNumber, String login)`
  - `void removeAssignee(int issueNumber, String login)`
  - `List<AssignmentEvent> assignmentEvents(int issueNumber)`
- Produces on `FakeGitHubClient`:
  - `void assignBy(int issueNumber, String assignee)`: adds the assignee and an event
  - `void unassign(int issueNumber, String assignee)`
  - `Set<String> assigneesOf(int issueNumber)`
  - `void ignoreTheAssignees()`: `addAssignee` then does nothing, as GitHub does for a login without access
  - `void whenAssigning(Runnable action)`: runs the action once, after the next `addAssignee`

- [ ] **Step 1: Write the failing tests**

In `HttpGitHubClientTest`:
- `issueReadsTheAssignees`: a JSON issue with `"assignees":[{"login":"alice"},{"login":"bob"}]` gives `issue.assignees()` equal to `Set.of("alice", "bob")`.
- `addAssigneeSendsTheLogin`: the server receives `POST /repos/o/r/issues/7/assignees` with the body `{"assignees":["alice"]}`.
- `removeAssigneeSendsTheLogin`: the server receives `DELETE /repos/o/r/issues/7/assignees` with the body `{"assignees":["alice"]}`.
- `assignmentEventsReadOnlyAssignedEvents`: of the events `labeled`, `assigned` (id 11, `alice`, `2026-11-15T10:00:00Z`), and `unassigned`, the result is `List.of(new AssignmentEvent(11, "alice", Instant.parse("2026-11-15T10:00:00Z")))`.

In `FakeGitHubClientTest`:
- `assignByRecordsTheAssigneeAndTheEvent`: after `assignBy(1, "alice")`, `issue(1).assignees()` is `Set.of("alice")` and `assignmentEvents(1)` has one event for `alice`.
- `ignoredAssigneeLeavesTheIssueUnassigned`: after `ignoreTheAssignees()` and `addAssignee(1, "worker")`, `assigneesOf(1)` is empty.

- [ ] **Step 2: Run the tests and make sure that they fail**

Run: `./mvnw -q test -Dtest='HttpGitHubClientTest,FakeGitHubClientTest'`
Expected: compilation errors for `assignees()`, `AssignmentEvent`, and the new calls.

- [ ] **Step 3: Implement the calls**

`toIssue` reads `item.path("assignees")[*].login`. `assignmentEvents` uses `readAllPages` on `/issues/<n>/events`, as `lastLabelActors` does, and keeps the events with `event == "assigned"`. `removeAssignee` accepts a 404 as "nothing to do". In the fake, the event id is a counter, and the event time is `Instant.EPOCH.plusSeconds(id)`, so that a later event is later. `openIssuesWithLabel`, `issue`, and `assignBy` use the same assignee set.

- [ ] **Step 4: Run all the tests and make sure that they pass**

Run: `./mvnw -q verify`
Expected: exit code 0. The old tests compile, because of the four-argument constructor.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/meterian/factory/github src/test/java/com/meterian/factory
git commit -m "Read and change the assignees of an issue"
```

### Task 2: The ownership rule

**Files:**
- Create: `src/main/java/com/meterian/factory/ownership/Ownership.java`
- Create: `src/main/java/com/meterian/factory/ownership/OwnershipRule.java`
- Test: `src/test/java/com/meterian/factory/ownership/OwnershipRuleTest.java`

**Interfaces:**
- Consumes: `Issue.assignees()`, `AssignmentEvent` (Task 1).
- Produces: `enum Ownership { OWNED, UNASSIGNED, OWNED_BY_ANOTHER }`.
- Produces: `static Ownership OwnershipRule.decide(Issue issue, String workerLogin, Supplier<List<AssignmentEvent>> events)`. The rule calls `events` only when the issue has more than one assignee and the worker login is one of them.

The rule:
- No assignee: `UNASSIGNED`.
- The worker login is not an assignee: `OWNED_BY_ANOTHER`.
- The worker login is the only assignee: `OWNED`.
- More assignees: for each current assignee, take its newest "assigned" event. The earliest of these events wins. A smaller event id breaks a tie of the time. A current assignee with no event wins over all the others (my call: the factory gives way when it cannot prove the order). The winner is the worker login: `OWNED`. Otherwise: `OWNED_BY_ANOTHER`.

- [ ] **Step 1: Write the failing tests**

- `issueWithoutAssigneeIsUnassigned`: `decide(issue with no assignees, "worker", ...)` is `UNASSIGNED`.
- `issueOfAnotherAccountIsOwnedByAnother`: assignees `{"alice"}` gives `OWNED_BY_ANOTHER`.
- `onlyAssigneeIsTheOwner`: assignees `{"worker"}` gives `OWNED`, and the events supplier is never called.
- `ownerMatchesTheLoginWithoutCase`: assignees `{"Worker"}` and the login `"worker"` give `OWNED`.
- `earliestAssignmentWins`: assignees `{"worker", "alice"}`, events `worker` at 10:00 and `alice` at 10:01 give `OWNED`. The reverse times give `OWNED_BY_ANOTHER`.
- `eventIdBreaksATie`: the same time, `alice` id 5 and `worker` id 6, gives `OWNED_BY_ANOTHER`.
- `newestAssignmentOfAnAssigneeCounts`: `worker` at 10:00, `alice` at 10:01, `worker` again at 10:02 (after an unassign) gives `OWNED_BY_ANOTHER`.
- `assigneeWithoutEventWins`: assignees `{"worker", "alice"}`, only a `worker` event, gives `OWNED_BY_ANOTHER`.

- [ ] **Step 2: Run the tests and make sure that they fail**

Run: `./mvnw -q test -Dtest=OwnershipRuleTest`
Expected: compilation error, `OwnershipRule` does not exist.

- [ ] **Step 3: Implement `OwnershipRule.decide`**

- [ ] **Step 4: Run the tests and make sure that they pass**

Run: `./mvnw -q test -Dtest=OwnershipRuleTest`
Expected: all tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/meterian/factory/ownership src/test/java/com/meterian/factory/ownership
git commit -m "Decide the owner of an issue from its assignees"
```

### Task 3: The claim at the pick up

**Files:**
- Create: `src/main/java/com/meterian/factory/ownership/IssueClaim.java`
- Test: `src/test/java/com/meterian/factory/ownership/IssueClaimTest.java`

**Interfaces:**
- Consumes: `OwnershipRule.decide`, `GitHubClient.addAssignee`, `removeAssignee`, `issue`, `assignmentEvents`.
- Produces: `IssueClaim(GitHubClient gitHub, String workerLogin)` and `Ownership claim(Issue issue)`.

`claim` decides on the issue of the poll. On `UNASSIGNED`, it adds the worker login, reads the issue again, and decides again. If the second decision is `OWNED_BY_ANOTHER`, it removes the worker login. If the second decision is `UNASSIGNED`, GitHub ignored the assignee: `claim` logs a warning with the issue number and the login, and returns `UNASSIGNED`. The caller works only on `OWNED`.

- [ ] **Step 1: Write the failing tests**

- `claimAssignsAnIssueWithoutAssignee`: the result is `OWNED`, and `assigneesOf(n)` is `Set.of("worker")`.
- `claimKeepsAnIssueOfTheWorker`: an issue assigned to `worker` gives `OWNED` with no `addAssignee` call.
- `claimLeavesAnIssueOfAnotherAccount`: an issue assigned to `alice` gives `OWNED_BY_ANOTHER`, and the assignees stay `{"alice"}`.
- `claimLosesTheRaceAndRemovesItself`: call `assignBy(n, "alice")`, then `claim` with the old `Issue` object of the poll, which has no assignee. The `alice` event is first. The result is `OWNED_BY_ANOTHER`, and the assignees are `{"alice"}`.
- `claimWinsTheRaceAndKeepsTheIssue`: the hook assigns `alice` after the worker event. The result is `OWNED`, and the assignees are `{"worker", "alice"}`.
- `claimRefusesAnIssueWhenGitHubIgnoresTheAssignee`: after `ignoreTheAssignees()`, the result is `UNASSIGNED`.

- [ ] **Step 2: Run the tests and make sure that they fail**

Run: `./mvnw -q test -Dtest=IssueClaimTest`
Expected: compilation error, `IssueClaim` does not exist.

- [ ] **Step 3: Implement `IssueClaim.claim`**

- [ ] **Step 4: Run the tests and make sure that they pass**

Run: `./mvnw -q test -Dtest=IssueClaimTest`
Expected: all tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/meterian/factory/ownership src/test/java/com/meterian/factory/ownership
git commit -m "Claim an issue with the assignee and settle a race"
```

### Task 4: The hand-over in the record

**Files:**
- Create: `src/main/java/com/meterian/factory/state/HandOver.java`
- Modify: `src/main/java/com/meterian/factory/state/IssueRecord.java` (new last field, every wither)
- Modify: `src/main/java/com/meterian/factory/state/StatusComment.java`
- Test: `src/test/java/com/meterian/factory/state/IssueRecordTest.java`, `src/test/java/com/meterian/factory/state/StatusCommentTest.java`

**Interfaces:**
- Produces: `enum HandOver { RELEASED, CONTINUES_PUSHED_BRANCH }`. `RELEASED`: this factory stopped because another account owns the issue. `CONTINUES_PUSHED_BRANCH`: the next PREPARE makes the worktree from the branch on GitHub.
- Produces on `IssueRecord`: the field `Optional<HandOver> handOver` after `afterConversation`. The compact constructor reads `null` as `Optional.empty()`. `IssueRecord withHandOver(HandOver newHandOver)` and `IssueRecord withoutHandOver()`. Every existing wither keeps the hand-over. `IssueRecord.queued` has no hand-over.
- Produces on `StatusComment`: `static Optional<IssueRecord> findNewestRecordOfAnotherAuthor(List<IssueComment> comments, String workerLogin)`. It returns the record of the newest status comment whose author is not the worker login.

- [ ] **Step 1: Write the failing tests**

- `recordWithoutHandOverReadsAsEmpty` (StatusCommentTest): a status comment with the JSON of a record from before this change parses, and `handOver()` is empty.
- `handOverSurvivesTheStatusComment` (StatusCommentTest): `render` then `findRecord` of a record `withHandOver(RELEASED)` gives `Optional.of(RELEASED)`.
- `withersKeepTheHandOver` (IssueRecordTest): `withPhase`, `withStatus`, `withNote`, and `withSessionId` keep `CONTINUES_PUSHED_BRANCH`.
- `findsTheNewestRecordOfAnotherAuthor` (StatusCommentTest): with records of `alice` (id 1), `bob` (id 2), and `worker` (id 3), the call with `"worker"` returns the record of `bob`.

- [ ] **Step 2: Run the tests and make sure that they fail**

Run: `./mvnw -q test -Dtest='IssueRecordTest,StatusCommentTest'`
Expected: compilation errors for `HandOver` and the new methods.

- [ ] **Step 3: Implement the field and the method**

The status comment shows no line for the hand-over. The note of Task 7 tells it to the people.

- [ ] **Step 4: Run all the tests and make sure that they pass**

Run: `./mvnw -q verify`
Expected: exit code 0.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/meterian/factory/state src/test/java/com/meterian/factory/state
git commit -m "Keep the hand-over of an issue in its record"
```

### Task 5: The worktree from the pushed branch

**Files:**
- Modify: `src/main/java/com/meterian/factory/workflow/IssueWorkspace.java`
- Modify: `src/main/java/com/meterian/factory/workflow/IssueWorkflow.java` (`prepare`)
- Modify: `src/main/java/com/meterian/factory/workflow/ApprovalCheck.java` (`continueAfterApproval`)
- Test: `src/test/java/com/meterian/factory/workflow/IssueWorkspaceTest.java`, `src/test/java/com/meterian/factory/workflow/IssueWorkflowTest.java`

**Interfaces:**
- Consumes: `HandOver.CONTINUES_PUSHED_BRANCH` (Task 4).
- Produces: `void IssueWorkspace.createFromPushedBranch(int issueNumber, Path worktree)`. It does the same steps as `create`, but it fetches `WorkspaceLayout.branch(issueNumber)` from `origin` and uses `origin/<that branch>` as the start point.

`prepare` with `CONTINUES_PUSHED_BRANCH` calls `createFromPushedBranch`, removes the hand-over, and gives the status `IN_PROGRESS` and the phase `BUILD`. The BUILD phase then builds, pushes, and finds the open pull request with `openPullRequest`, as now. Without the hand-over, `prepare` does not change.

`continueAfterApproval` with `CONTINUES_PUSHED_BRANCH` and the wait reason `MERGE_NEEDS_APPROVAL` and a given approval: the record gets no wait reason, the status `IN_PROGRESS`, and the phase `PREPARE` (decision for the plan: the new owner did not see the reviews, so the reviews run again, and the owner approves the merge again after them). The approval label goes away at the BUILD phase, as now.

- [ ] **Step 1: Write the failing tests**

- `createFromPushedBranchStartsAtTheBranchOnOrigin` (IssueWorkspaceTest): with `TemporaryRepository`, push a commit to `factory/issue-7` on the origin. After the call, the head commit of the worktree is that commit.
- `prepareContinuesThePushedBranch` (IssueWorkflowTest): a record `queued(7).withHandOver(CONTINUES_PUSHED_BRANCH)` advances to phase `BUILD`, without the hand-over, with the worktree on the pushed commit.
- `prepareWithoutHandOverStartsFromTheMergeTarget` (IssueWorkflowTest): the existing behavior stays.
- `approvalOfAContinuationRunsTheReviewsAgain` (IssueWorkflowTest): a waiting record with `CONTINUES_PUSHED_BRANCH`, `MERGE_NEEDS_APPROVAL`, and the label `factory:approve` from an allowed account advances to phase `PREPARE`, then `BUILD`.

- [ ] **Step 2: Run the tests and make sure that they fail**

Run: `./mvnw -q test -Dtest='IssueWorkspaceTest,IssueWorkflowTest'`
Expected: the new tests fail.

- [ ] **Step 3: Implement `createFromPushedBranch`, and the changes to `prepare` and `continueAfterApproval`**

- [ ] **Step 4: Run all the tests and make sure that they pass**

Run: `./mvnw -q verify`
Expected: exit code 0.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/meterian/factory/workflow src/test/java/com/meterian/factory/workflow
git commit -m "Continue an issue from the branch that an earlier owner pushed"
```

### Task 6: The ownership check inside a running step

**Files:**
- Create: `src/main/java/com/meterian/factory/ownership/OwnershipGuard.java`
- Create: `src/main/java/com/meterian/factory/ownership/GitHubOwnershipGuard.java`
- Create: `src/main/java/com/meterian/factory/ownership/OwnershipLostException.java`
- Modify: `src/main/java/com/meterian/factory/workflow/PullRequestPublisher.java` (`publish`, `publishReviewNotes`)
- Modify: `src/main/java/com/meterian/factory/workflow/IssueWorkflow.java` (constructor, passes the guard)
- Modify: `src/main/java/com/meterian/factory/merge/Merger.java` (before `squashMerge`, line 184)
- Test: `src/test/java/com/meterian/factory/ownership/GitHubOwnershipGuardTest.java`, `IssueWorkflowTest`, `MergerTest`

**Interfaces:**
- Consumes: `OwnershipRule.decide` (Task 2).
- Produces: `interface OwnershipGuard { void verifyStillOwner(int issueNumber); static OwnershipGuard none() }`. `none()` never throws; the existing tests use it.
- Produces: `GitHubOwnershipGuard(GitHubClient gitHub, String workerLogin)`. It reads `gitHub.issue(issueNumber)` and throws `OwnershipLostException` only for `OWNED_BY_ANOTHER`. `UNASSIGNED` does not throw: the next poll claims the issue again.
- Produces: `final class OwnershipLostException extends RuntimeException`, with the constructor `OwnershipLostException(int issueNumber)` and the method `int issueNumber()`.

The guard runs before the push in `publish`, before the edit of the body in `publishReviewNotes`, and before `squashMerge` in `Merger`. The exception goes up out of `IssueWorkflow.advance`. Task 8 catches it in the worker.

- [ ] **Step 1: Write the failing tests**

- `guardAcceptsTheOwner`, `guardAcceptsAnIssueWithoutAssignee`, `guardRefusesAnotherOwner` (GitHubOwnershipGuardTest).
- `buildDoesNotPushWhenAnotherAccountOwnsTheIssue` (IssueWorkflowTest): with a guard that throws, the phase BUILD throws `OwnershipLostException`, and the gatekeeper and GitHub get no push and no pull request.
- `mergerDoesNotMergeWhenAnotherAccountOwnsTheIssue` (MergerTest): `squashMergeCalls()` stays empty.

- [ ] **Step 2: Run the tests and make sure that they fail**

Run: `./mvnw -q test -Dtest='GitHubOwnershipGuardTest,IssueWorkflowTest,MergerTest'`
Expected: compilation errors for the new types.

- [ ] **Step 3: Implement the guard and the three calls**

- [ ] **Step 4: Run all the tests and make sure that they pass**

Run: `./mvnw -q verify`
Expected: exit code 0.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/meterian/factory src/test/java/com/meterian/factory
git commit -m "Check the owner before each push, each pull request change, and the merge"
```

### Task 7: The stop and the continuation records

**Files:**
- Create: `src/main/java/com/meterian/factory/worker/OwnershipRelease.java`
- Create: `src/main/java/com/meterian/factory/worker/OwnershipTakeover.java`
- Test: `src/test/java/com/meterian/factory/worker/OwnershipReleaseTest.java`, `src/test/java/com/meterian/factory/worker/OwnershipTakeoverTest.java`

**Interfaces:**
- Consumes: `HandOver`, `StatusComment.findRecord`, `StatusComment.findNewestRecordOfAnotherAuthor` (Task 4), `StatusPublisher.publish(Issue, IssueRecord, IssueRecord)`, `GitHubClient.findBranchHead`, `openPullRequest`, `WorkspaceLayout.branch(int)`.
- Produces: `OwnershipRelease(GitHubClient gitHub, StatusPublisher statusPublisher, String workerLogin)` and `void release(Issue issue)`.
- Produces: `OwnershipTakeover(GitHubClient gitHub, StatusPublisher statusPublisher, String workerLogin)` and `IssueRecord takeOver(Issue issue, List<IssueComment> comments, Optional<IssueRecord> ownRecord)`.

`release` reads the own record. When the record exists and has no `RELEASED`, it publishes the record `withHandOver(RELEASED)` and the note below, and it creates the stop comment below. It does nothing in all other cases. Exact copy:

- Note: `The factory of <worker login> stops work: the issue has a new assignee.`
- Comment: ``The factory of `<worker login>` stops work on this issue, because the issue has a new assignee. The branch and the pull request stay. The factory of the new assignee continues the work.``

`takeOver` builds the first record of a new owner and publishes it, with `ownRecord` as the record before:

1. Start from `IssueRecord.queued(number).withIssueTextHash(IssueText.computeHash(issue))`.
2. When `findBranchHead(branch)` is present: add `withHandOver(CONTINUES_PUSHED_BRANCH)`, and `withPullRequest` of `openPullRequest(branch)` when it is present. The number of the pull request never comes from the record of another author.
3. Take the earlier state from `findNewestRecordOfAnotherAuthor`, or else from `ownRecord`. When its status is `BLOCKED` or `WAITS_FOR_YOU`, copy only the status and the wait reason. Do not copy other fields of a record of another author.
4. Add the note: `The factory of <worker login> continues the work of an earlier owner.`
5. Keep the status comment id of `ownRecord`, when present, as `StartAgainRequests.createQueuedRecord` does.

- [ ] **Step 1: Write the failing tests**

- `releaseMarksTheRecordAndWritesTheStopComment`: the own record gets `RELEASED`, and the issue gets one comment with the exact text.
- `releaseWritesTheStopCommentOnce`: two calls give one stop comment.
- `releaseWithoutOwnRecordDoesNothing`: no comment, no record.
- `takeOverContinuesThePushedBranch`: with the branch on GitHub and an open pull request #40, the record has `CONTINUES_PUSHED_BRANCH`, the pull request 40, the status `QUEUED`, and the phase `PREPARE`.
- `takeOverWithoutBranchStartsAgain`: no hand-over, no pull request, phase `PREPARE`.
- `takeOverKeepsABlock`: the record of `alice` is `BLOCKED` with `BLOCKED_BY_OWNER`. The new record has the same status and wait reason.
- `takeOverKeepsAWaitForApproval`: the same for `WAITS_FOR_YOU` with `MERGE_NEEDS_APPROVAL`.
- `takeOverIgnoresThePullRequestNumberOfAnotherAuthor`: the record of `alice` says pull request 99, GitHub has no open pull request. The new record has no pull request.

- [ ] **Step 2: Run the tests and make sure that they fail**

Run: `./mvnw -q test -Dtest='OwnershipReleaseTest,OwnershipTakeoverTest'`
Expected: compilation errors, the classes do not exist.

- [ ] **Step 3: Implement `release` and `takeOver`**

- [ ] **Step 4: Run the tests and make sure that they pass**

Run: `./mvnw -q test -Dtest='OwnershipReleaseTest,OwnershipTakeoverTest'`
Expected: all tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/meterian/factory/worker src/test/java/com/meterian/factory/worker
git commit -m "Write the stop and the continuation of an issue that changes owner"
```

### Task 8: The ownership in the poll of the worker

**Files:**
- Modify: `src/main/java/com/meterian/factory/worker/Worker.java` (`pollOnce`, `findStartableRecord`, `runIssue`, `mayContinue`, `releaseIssue`, constructor)
- Modify: `src/main/java/com/meterian/factory/Main.java` (wiring of `IssueClaim`, `GitHubOwnershipGuard`, `OwnershipRelease`, `OwnershipTakeover`)
- Test: `src/test/java/com/meterian/factory/worker/WorkerTest.java`, `src/test/java/com/meterian/factory/MainTest.java` if it checks the wiring

**Interfaces:**
- Consumes: `IssueClaim.claim` (Task 3), `OwnershipLostException` (Task 6), `OwnershipRelease.release`, `OwnershipTakeover.takeOver` (Task 7).

The changes, in the order of the poll:

1. `pollOnce` claims each labeled issue. Only the `OWNED` issues go to `board.recordPoll` and to `checkIssue`. Thus the board drops the cards of the other issues.
2. An `OWNED_BY_ANOTHER` issue in work goes into a new set `issuesOfAnotherOwner`. `mayContinue` is false for such an issue, so the loop starts no next step. A running step ends normally.
3. An `OWNED_BY_ANOTHER` issue not in work gets `ownershipRelease.release(issue)`.
4. `runIssue` catches `OwnershipLostException` before `RuntimeException`, and adds the issue to `issuesOfAnotherOwner`. It never blocks the issue for this exception.
5. `releaseIssue`: when the issue is in `issuesOfAnotherOwner` and no block is in progress, remove it from the set and call `ownershipRelease.release(gitHub.issue(number))`. A block in progress goes first, as now.
6. `findStartableRecord`, after `StatusComment.findRecord`:
   - The own record has `RELEASED`: return `takeOver(issue, comments, record)`, then the normal checks on the result.
   - No own record, and the label `factory` comes from an allowed account: return `takeOver(issue, comments, Optional.empty())` when `findNewestRecordOfAnotherAuthor` is present. Otherwise `takeIntoQueue`, as now.
7. A live session stays open. `findStartableRecord` is not called for an issue of another owner, so no step starts after the session.

- [ ] **Step 1: Write the failing tests**

In `WorkerTest`:
- `pollAssignsAnIssueWithoutAssignee`: after one poll, `assigneesOf(n)` is `{"worker"}`, and the issue has a status record.
- `pollLeavesAnIssueOfAnotherAccount`: an issue assigned to `alice` gets no comment and no status label, and the board shows no card for it.
- `runningStepEndsWhenTheAssigneeChanges`: the fake agent assigns `alice` (and unassigns `worker`) during IMPLEMENT. The agent run ends normally, no BUILD starts, and the issue gets the stop comment.
- `buildPushRefusedEndsTheLoopWithoutABlock`: the assignee changes during the build. The record has no status `BLOCKED`, and the issue gets the stop comment.
- `liveSessionStaysOpenWhenTheAssigneeChanges`: an issue with an open live session changes to `alice`. `FakeLiveSessions` still has the session open, and the next polls start no step.
- `newOwnerContinuesFromThePushedBranch`: the issue has a record of `alice` and the branch on GitHub. It is assigned to `worker`. After the polls, the worktree is on the pushed commit and the phase is past `BUILD`.
- `newOwnerKeepsABlock`: the record of `alice` is `BLOCKED`. The issue is assigned to `worker`. No step starts.
- `factoryThatComesBackContinuesAsANewOwner`: the own record has `RELEASED`, and the issue is assigned to `worker` again. The next record has `CONTINUES_PUSHED_BRANCH` when the branch exists.

- [ ] **Step 2: Run the tests and make sure that they fail**

Run: `./mvnw -q test -Dtest=WorkerTest`
Expected: the new tests fail.

- [ ] **Step 3: Implement the changes and the wiring in `Main`**

- [ ] **Step 4: Run all the tests and make sure that they pass**

Run: `./mvnw -q verify`
Expected: exit code 0.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/meterian/factory src/test/java/com/meterian/factory
git commit -m "Work only on the issues that the assignee gives to the factory"
```

### Task 9: The documentation

**Files:**
- Modify: `README.md`
- Create: `/home/bbossola/.claude/projects/-home-bbossola-projects-rocksolid-code-claude-ai-factory/memory/issue-ownership-by-assignee.md`
- Modify: `/home/bbossola/.claude/projects/-home-bbossola-projects-rocksolid-code-claude-ai-factory/memory/MEMORY.md`

- [ ] **Step 1: Add the README section "More than one factory on one repository"**

Write it in Simplified Technical English. Cover these points of the spec: each person uses a token of their own account; the pick up assigns the token account; the race check and the earliest "assigned" event; an issue of another account gets no work and no card; the stop after the running step, the stop comment, and the open live session; the continuation from the pushed branch, and the work that stays on the first machine; the kept block or wait, and the second approval of a merge.

- [ ] **Step 2: Change the README section "Setup"**

Add one sentence: the token account must be able to assign issues in the target repository. Without this access, GitHub ignores the assignee, and the factory does not work on the issue.

- [ ] **Step 3: Write the memory note**

The note `issue-ownership-by-assignee` (type `project`) says: the assignee decides the owner factory; the rule is in `com.meterian.factory.ownership`; the record field `handOver` keeps the stop and the continuation; the spec path. Link `[[document-features-in-readme-and-memory]]`. Add its line to `MEMORY.md`.

- [ ] **Step 4: Verify the build**

Run: `./mvnw -q verify`
Expected: exit code 0.

- [ ] **Step 5: Commit**

```bash
git add README.md
git commit -m "Describe the ownership of issues by assignee"
```
