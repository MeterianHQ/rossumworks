# Issues and labels

This page tells how you give an issue to the factory, and how you control the work on an issue with the labels.
The buttons of the board add the same labels. See [Web page](web-page.md).

## Give an issue to the factory

Add the label `factory` to the issue. At its next poll, the worker assigns the issue to the GitHub account of
its token, and writes the status comment on the issue. Then the issue follows the
[workflow](../README.md).

The factory needs no GitHub Project. The issue must have no assignee, or the assignee must be the account of
the token. An issue that is assigned to another person gets no work. See
[More than one factory](multiple-factories.md).

## The labels

The worker creates its labels in your repository at its first poll.

| Label | What it tells the factory |
|---|---|
| `factory` | Work on this issue. |
| `factory:approve` | Approve the merge, or accept a new text of the issue. You can add it before the issue waits for you. See the warning below. |
| `factory:block` | Stop the running action at once, and move the issue to the [block state](glossary.md). |
| `factory:enter` | Open the live mode on this issue. See [Live mode](live-mode.md). |
| `factory:start-again` | Discard the earlier work of a blocked issue, and start the issue again from the beginning. |
| `human` | Do not touch this issue. See "The label `human`" below. |
| `factory:is:<column>` | Only for display: the column of the issue on the board, for example `factory:is:in-review`. |

The worker creates the label of a column at its first use. The state of the issue is in the status comment. The worker removes the labels `factory:status:<name>` of an
earlier version of the factory.

> **Warning.** The label `factory:approve` also works before the issue waits for you. If you add it during the
> reviews, it approves the merge in advance. This includes a pull request that changes `.factory`. The reviews
> must still approve the change. When a later build passes, the worker removes the label, because the approval was
> for the earlier change.

### Continue or start again

There are two ways to continue a blocked issue:

- **Continue keeps the work.** In the default workflow, the issue continues in the action where it stopped.
  Use the button Continue of the board. With the subscription, you can also open a live conversation, and type
  `/factory:continue` there.
- **Start again discards the work.** The worker resets the status comment, removes the worktree, resets the
  branch, and starts a new agent. The issue uses the current version of `.factory`. An open pull request stays
  open and gets the new commit. If GitHub merged the pull request, the issue moves to Done, so the change does
  not run again.

## Who can add a label

The factory does not check who adds a label. It acts on each [request label](glossary.md) that it finds on an issue:
`factory`, `factory:block`, `factory:enter`, `factory:approve`, and `factory:start-again`. GitHub controls who
can add a label: usually only an account with triage access or more. Neither `.factory` nor `worker.yaml` has a
list of accounts.

> **Warning.** Two other paths add labels. With them, an account without triage access can give work to the
> factory:
>
> - An issue template or an issue form with `labels:` adds its labels for each author of an issue.
> - A GitHub Actions workflow adds labels with its own token.
>
> Do not put `factory` or a `factory:` label in an issue template, an issue form, or a GitHub Actions workflow.

## The label `human`

An issue with the label `human` is only for a person. The factory does not take the issue, also when it has
the label `factory`. The factory does not assign the issue, writes nothing on it, and shows no card for it.

An action that runs when the label comes continues to its end. Then the factory starts no next action. To stop
the running action at once, add the label `factory:block`. To give the issue back to the factory, remove the
label `human`.

## Notifications

The comments of the worker mention nobody.

- When the token belongs to you, GitHub sends you no notification for these comments.
- With a separate GitHub account for the worker, you get a notification only for the issues that you watch.
  Assign the issues to the account of the worker, or to nobody.

## The language of the agents

Each `claude` session of an issue gets this extra system prompt:

> When you write to a human, write in Simplified Technical English (ASD-STE100): short sentences, active voice,
> simple tenses, and one word for one thing.

This applies to the questions, the descriptions of the pull requests, and the live conversations. It applies also when your
repository has no such rule in its `CLAUDE.md`.
