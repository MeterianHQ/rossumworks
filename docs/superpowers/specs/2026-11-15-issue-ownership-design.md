# Issue ownership by assignee: design

Date: 2026-11-15. Status: approved by the owner on 2026-11-15.

> Note, 2026-11-15: main removed `accounts.allowed` (PR #26); the claim takes
> every `factory` label. A stopped factory does not claim a new issue. An issue
> with a record of this factory is not new.

## 1. Intent

Until now, only one person ran the factory. Now more people run their own
factories, on different machines, on the same repository. Each person has a
different GitHub identity.

The risk is the pick up. The status record of an issue is a comment that the
worker login writes, and a factory reads only its own comments. Thus two
factories with different tokens do not see the records of each other. Both
factories can take the same issue and work on it at the same time.

The owner wants the GitHub assignee to mark the ownership of an issue. A
factory assigns an issue to itself when it picks it up. A factory refuses to
pick up an issue, or to work on an issue, that has a different assignee.

## 2. Goals, non-goals, and anti-goals

Goals:

- Only one factory works on an issue at a time.
- The assignee of the issue tells which factory owns it.
- A person can give an issue to another person: they change the assignee.
  The factory of the new assignee continues from the work on GitHub.
- The README and the Claude memories describe the feature.

Non-goals:

- A move of the local work of a factory to another machine: the worktree
  before the first push, and the Claude session.
- A shared board for all the factories. Each factory shows only its own
  issues.

Anti-goals:

- A running agent or build that stops because of an assignee change.
- Two factories that push to the same branch at the same time.

## 3. Decisions of the owner

- The identity is the account of the factory token, that is the worker login.
  Each person runs a factory with a token of their own account.
- The pick up is the main risk.
- When the assignee of an issue in work changes to another account, the
  factory stops work on the issue as soon as possible. It never interrupts a
  running agent or build. It removes the issue from its board.
- The factory of the new assignee continues from the work of the first
  factory. It does not start again from the beginning.
- The proposal of section 6 for the continuation is accepted.
- The work includes the README and the memories.
- A factory assigns itself to an issue with no assignee, also when the issue
  has a status record of another factory.
- Labels are always orders. The assignee is not an order: it only tells which
  factory owns the issue. Thus a factory accepts an assignee change from any
  account. The factory does not check who added a label.
- The factory of the new assignee keeps a blocked or a waiting state of the
  old record.

## 4. The pick up

The `factory` label still gives an issue to the factories. The assignee
decides which factory takes it.

At each poll, for an open issue with the label `factory`:

| Assignees of the issue | What the factory does |
|---|---|
| None | Assigns the issue to the worker login, then does the race check of section 4.1. |
| Only the worker login | Works on the issue as now. |
| Only other accounts | Does not pick up the issue. Writes nothing on the issue. |
| The worker login and other accounts | Applies the rule of section 4.1: the earliest "assigned" event wins (my call, changed at the plan: one rule for all the cases). |

The factory assigns itself before it takes the issue into the queue, that is
before it writes its first status comment.

### 4.1 The race check

GitHub accepts more than one assignee on an issue. Two factories can poll at
the same second, and both can add their own assignee.

After it assigns itself, the factory reads the issue and its events again:

- When the worker login is the only assignee, the factory keeps the issue.
- When there are other assignees, the earliest "assigned" event among the
  current assignees wins (my call). The event id breaks a tie of the time.
- A factory that loses removes its own assignee and leaves the issue.

All the factories read the same events, so all come to the same decision.

## 5. An issue in work

The factory checks the assignees at each poll. It checks them again before it
starts each step.

An issue in work with no assignee gets the rule of the pick up: the factory
assigns itself and does the race check (my call). This also covers the issues
in work at the upgrade, which have no assignee.

The factory owns an issue when it is an assignee and its "assigned" event is
the earliest among the current assignees (my call). This is the same rule as
the race check. Thus a short race of another factory never stops an owner.

A factory assigns itself to an issue with no assignee also when the issue has
a status record of another login (decision of the owner). Then the factory
continues from the work of the other factory, with the rule of section 6.

The factory checks the ownership also inside a running step, just before each
push, each change to the pull request, and the merge (my call). A step that
is no longer the owner skips these actions and ends. Thus the old factory
never pushes over the work of the new factory. A short time between the check
and the push stays, and the design accepts it.

When the factory is no longer the owner:

- The running step ends normally. The factory never interrupts a running agent
  or build for this check.
- The factory starts no new step on the issue.
- The factory removes the issue from its board.
- The factory writes one comment on the issue (my call). The comment tells
  that the factory stops because the issue has a new assignee.
- The factory deletes nothing: the worktree, the branch, and the pull request
  stay (my call).
- An open live session stays open, because the owner talks to the agent in it
  (my call). After the session ends, the factory starts no step.
- The stop is final for the local work (my call). When the issue comes back,
  the factory applies the rule of section 6, as a new owner.

## 6. The continuation by the new assignee

What a second factory can see on GitHub:

- The branch `factory/issue-<n>`. A factory pushes it for the first time at
  the end of the BUILD phase, after a build passes.
- The pull request, from the same moment.
- The status comment of the first factory, with its record.

What stays on the machine of the first factory:

- The worktree before the first push.
- The Claude session of the agent. Another machine cannot resume it.

The rule:

- The new factory continues from the pushed branch when one exists. It makes
  its worktree on that branch, not on the merge target. It starts a new agent
  session.
- When no branch exists on GitHub, the new factory starts again from
  PREPARE.
- The new factory reads the record of the first factory for two things: the
  pull request, and a blocked or a waiting state. It uses the existing pull
  request. It does not open a second pull request.
- The new factory runs the reviews again, because the new owner did not
  approve them. Its first phase on a pushed branch is BUILD (my call: the
  build is cheap and it proves the branch on this machine).
- The new factory keeps a blocked or a waiting state of the old record
  (decision of the owner). When the old record is "Blocked", the new factory
  starts no work until the owner removes the block. When the old record waits
  for an answer or for the merge approval, the new factory also waits. The new
  factory copies the status and the wait reason into its own record.
- The new factory writes its own status comment. From then on, it reads only
  its own record, as now.

When the issue comes back to the first factory, the same rule applies (my
call): its old record is out of date, so it continues from the pushed branch
in a new session.

## 7. The board

The board of a factory shows only the issues where the worker login is the
only assignee. My call: the board shows no card for an issue of another
person.

## 8. Documentation

- `README.md` gets a section "More than one factory on one repository". It
  explains the assignee rule, the race check, the stop, and the continuation.
  It tells that each person uses a token of their own account.
- The README section "Setup" tells that the token account must be able to
  assign issues in the target repository.
- A Claude memory note describes the feature for the next sessions.

## 9. Left to the builder

- The GitHub calls: read the assignees, add an assignee, remove an assignee,
  and read the "assigned" events. Use the client and the conditional request
  cache as the other calls do.
- Where the check sits: in `Worker`, in `IssueWorkflow`, or in a new class.
  Keep each class to one responsibility.
- The exact text of the stop comment.
- How the new factory recognizes that a record belongs to an earlier owner.
- How the new factory finds the pull request. It can find the pull request by
  its head branch, and not trust the number in the record of another login.
- The tests, with the fakes that the project already uses.
