# More than one factory

More than one person can run a factory on the same repository at the same time. This page tells how the
factories share the issues, and how you give an issue to another person.

Each person runs a worker with a GitHub token of their own account. The assignee of an issue decides which
factory works on it: each factory assigns an issue to the account of its token. A change of the assignee counts,
also when another account makes it.

In this page, the **earlier factory** is the factory that loses an issue, and the **new factory** is the factory
of the new assignee.

## How a factory takes an issue

- A factory takes an issue that has the label `factory` and no assignee.
- The factory assigns the issue to the account of its token. Then it reads the issue again.
- A stopped factory does not take a new issue. It does not assign the issue, and it writes nothing on it. An
  issue with a status comment of this factory is not new, so the factory assigns the issue to the account of
  its token again.
- When the issue has more than one assignee, the earliest "assigned" event among the current assignees wins.
  When one of these assignees has no "assigned" event, the factory does not take the issue. In the two cases, a
  factory that loses removes the account of its token from the assignees.
- An issue of another account gets no work and no comment. It gets no card on the board of this factory.

## How a factory stops when another person takes the issue

The factory checks the assignee at each poll and before each action. It also checks the assignee just before
each push, each change to the pull request, the merge, and the removal of an approval. The factory never
interrupts a running agent or a running build.

When another account becomes the assignee, the factory starts no new action. It writes one comment: "The factory
of `<login>` stops work on this issue, because the issue has a new assignee. The branch and the pull request
stay. The factory of the new assignee continues the work." An open live session stays open. A merged issue, in
the state with the action `finish`, stays as it is.

### The gatekeeper protects the commits

The **gatekeeper** is a local git repository. Each push of the worker goes through it. The gatekeeper forwards a
push to GitHub only when the branch on GitHub is at the expected commit. The expected commit is the last commit
that the gatekeeper forwarded. For a new factory, it is the commit where the new factory continued the work. Thus a factory never removes a commit of another
factory.

- When GitHub refuses the push and the issue has another assignee, the factory stops, as for a new assignee.
  Otherwise, the worker counts a failed push (a failed forward check).
- When the branch does not exist on GitHub, the factory makes the branch again. There is no commit to protect.

GitHub can refuse every push, because the branch on GitHub has commits that the factory does not
know. For example, another person pushes to the branch, or the gatekeeper folder moves. After three failed
forward checks (the default), the factory blocks the issue and writes a note. See the retries in
[Configuration](configuration.md). Check the branch on GitHub. To let the factory
replace the branch, delete the branch on GitHub, and then add the label `factory:start-again`.

## How the new factory continues

- The new factory writes a new status comment. This moment is the **takeover**. The issue is then in the
  [start state](glossary.md) of the current version of `.factory`.
- When the branch `factory/issue-<n>` exists on GitHub, the new factory continues from it. The action `prepare`
  makes the worktree from `origin/factory/issue-<n>`. Then the issue goes to the first state with the action
  `build`, in the order of the transitions from the start state. The new factory starts a new agent, builds
  again, reviews again, and uses the open pull request. A workflow without a `build` state continues with the
  event `done` of `prepare`.
- When the branch does not exist, the new factory starts the issue from the beginning.
- The work before the first push stays on the first machine. The Claude session stays there too.

The new factory keeps some waits of the earlier factory:

- An issue in a state without an action, for example `blocked`, goes to the [block state](glossary.md) of the
  workflow of the new factory.
- An issue that waits for your approval of a new text goes to the [text-change state](glossary.md).
- The new factory keeps the issue text that the earlier factory accepted. When the text changed, the issue waits for
  `factory:approve` before the next action. A status comment without the hash of the issue text
  counts as a change.
- A wait for the merge approval stays only when the pushed branch exists. The issue goes
  to the state where the new workflow waits for this approval, for example `waits-for-approval`. After the approval, the issue goes
  to the start state: the factory makes the worktree from the branch, builds, and reviews again. Then it asks
  for the approval a second time. Without the branch, the issue starts from the beginning.
- The new factory does not keep a wait in a state with the action `conversation`. This is a wait for an answer
  to a question, or for the end of a live conversation. The new factory continues the work and writes a note: the
  question stays on the machine of the earlier factory.

A wait keeps the card on the board, so it holds a slot. See [Configuration](configuration.md). When no slot is
free, the takeover waits, and the poll tries again later. The board shows the card with the note "The work of
the earlier owner waits for a free slot of parallel-issues." The new factory writes no status comment
before the takeover. A takeover into the start state or into the block state needs no slot.

A status comment of an account that was never an assignee of the issue never sets the state. A factory that
gets an issue back continues as a new factory. This is also true when the factory was off while the issue had
another assignee.

## Give an issue to another person

Change the assignee in one edit: remove the old assignee and add the new assignee in the same change. On the
GitHub page of the issue, open the "Assignees" menu, clear the old account, select the new account, and then
close the menu.

Do not change the assignee in two separate edits:

- If you add the new assignee first, the issue has two assignees for a short time. The old assignee has the
  earlier "assigned" event, so its factory keeps the issue. The factory of the new assignee removes its own assignee.
  Then you remove the old assignee, and the issue has no assignee.
- If you remove the old assignee first, the issue has no assignee. Any factory can then take the issue.

An issue without an assignee goes to the first factory that polls. Assign the issue again in one edit.

## Upgrade

An older factory ignores the assignees. Thus upgrade all the factories of a repository at the same time.

Before the upgrade, look at the issues with the label `factory`. Remove their human assignees, or assign them to
the account of the token.

The labels are always orders. See "Who can add a label" in [Issues and labels](issues-and-labels.md).
