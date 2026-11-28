# Frequently asked questions

This page gives the answers to the questions that users ask about the factory. Each answer links to the page
that tells more.

## General

### Is the factory specific to Claude?

Yes. The worker does all the work with `claude` sessions: the agent, the reviews, and the risk check. You can
change the `claude` command with `claude.command` in `worker.yaml`, but the new command must accept the same
arguments as `claude`. The provider can be your Claude subscription or an Anthropic API key. See
[Providers](providers.md).

If you want the factory to work with a different tool, send a pull request. We are happy to look at it.

### Can I use any board?

No. The factory reads the issues only from GitHub, and it uses only its own board. It does not read Jira,
GitLab, or a GitHub Projects board. A GitHub Projects board can show the same issues, but the factory uses only
the labels and the assignee.

If you want the factory to work with a different board, send a pull request. We are happy to look at it.

### Is the board a Trello board to manage the agents?

No. The board is not Trello. It is a web page of the worker, and it shows each issue of the factory as a card.
The board looks like a Trello board, but it works differently:

- You do not make the cards. A card shows when you add the label `factory` to an issue on GitHub.
- You do not move the cards. The worker moves each card from column to column, as the issue goes through the
  workflow.
- You control the work with the buttons on a card: Approve, Block, Start again, Continue, and Terminal or
  Browser for the live mode.

Each button, except Continue, adds a label on GitHub. Thus you can also do this work from GitHub with the
labels. For Continue, type `/factory:continue` in the live conversation. See [Web page](web-page.md).

## Installation

### Do I install the factory on a server, or on the machine of each developer?

You can do either. The worker runs on one Linux machine with a clone of the repository. One worker is enough
for a team. Each developer can also run a worker on their own machine, with their own GitHub token. The
assignee of an issue decides which factory works on it. See [More than one factory](multiple-factories.md).

### Can I run the worker on a VM in the cloud?

Yes. Use a Linux VM, and start the worker as a `systemd` service. Keep the board on `127.0.0.1`, and open it
from your computer through an SSH tunnel. Use the button Browser for the live mode, because the VM has no
desktop. See [A worker on a cloud VM](cloud-worker.md).

### Must I define the build and test pipeline in git, or can I use my local pipeline?

You must define the build command in git, in `.factory/factory.yaml`. But the command runs locally, on the
machine of the worker, in the worktree of the issue. The CI of GitHub can also gate the merge: name its check
runs in `merge.required-checks`, and the merge gate waits until they pass on the head commit of the pull
request. Without the key, the local build is the only build. The settings of the machine, for example
`JAVA_HOME`, go in `build.environment` of `worker.yaml`, and `worker.yaml` is not in git. See
[Factory definition](factory-definition.md).

### Where do I put my skills, my commands, and similar items?

Put them where `claude` usually finds them. The worker does not limit the setting sources of `claude`, so each
session loads:

- `CLAUDE.md` and `.claude/` of the repository, from the branch of the issue. Commit them in git.
- `~/.claude/` of the user that runs the worker. This folder holds your own skills and MCP servers.

A command that the agent runs with Bash must also be in `claude.allowed-tools` of `.factory/factory.yaml`. If
it is not in the list, the session refuses it.

### Can I suggest or force the model for each step?

Not for each step. All the sessions use the same model: the agent, the reviews, and the risk check. The worker
gives `claude` no model, so `claude` uses the model of its settings. To select this model, set the key `model`
in one of these files:

- `.claude/settings.json` of the repository. Commit it in git. Then each factory of the repository uses the
  same model.
- `~/.claude/settings.json` of the user that runs the worker. Then the model applies only to this machine.

The variable `ANTHROPIC_MODEL` does not work. The worker removes each variable that starts with `ANTHROPIC_`
from the environment of the sessions.

If you want a model for each step, send a pull request. We are happy to look at it.

## The work on an issue

### When I add the label to an issue, can more than one factory start work on it?

No. Only one factory works on an issue. The assignee of the issue decides which factory works on it.

- A factory takes an issue with the label `factory` and no assignee. It assigns the issue to the account of
  its token, and then it reads the issue again.
- Two factories can assign the issue at the same time. Then the factory with the earliest "assigned" event
  wins. The other factory removes its account from the assignees, and does no work on the issue.
- The factory checks the assignee before each action and before each push. The gatekeeper also stops a push
  that can remove a commit of another factory.

To select the factory, assign the issue to the account of that factory before you add the label. See
[More than one factory](multiple-factories.md).

### Is there a first gate where we discuss how to do the issue, when it is not simple?

The default workflow has no separate step for a discussion before the work starts. The label `factory` is the
gate. You have three ways to discuss the issue:

- **Before the label.** Discuss the issue on GitHub. Then write the decisions in the text of the issue, and add
  the label `factory`. The agent reads only the title and the text of the issue. It does not read the comments.
- **When the agent has a question.** The agent writes a short plan first. When a question changes what it
  builds, the agent stops and asks you. In the default workflow, the issue then goes to the live mode, and you
  answer in a live conversation.
- **At any time.** Add the label `factory:enter`, or click Terminal or Browser on the card. This opens a live
  conversation with the agent. Only the subscription has the live mode. See [Live mode](live-mode.md).

If you change the text of the issue during the work, the factory waits for your approval (`factory:approve`)
before it continues. You can also change the workflow in `.factory/factory.yaml`, for example to add an
approval state before the work. See [Factory definition](factory-definition.md).

If you want a real discussion step in the default workflow, send a pull request. We are happy to look at it.

### How does it work when a fix needs changes in more than one repository?

One factory works on one repository. The key `repository` of `worker.yaml` names it. A change across more than
one repository needs one issue in each repository, and one factory for each repository. The factory does not
know that the issues are related, so you control the order. For example, merge the change of the library
first, and give the issue of the application to the factory after that merge.

The key `repositories` of `.factory/factory.yaml` exists, but version 1 does not use it. If you want the
factory to do a change across repositories, send a pull request. We are happy to look at it.

### Does the factory try again? Can I set how many times? Are there gates?

Yes to all three.

- **The fixes.** When the build fails or a review rejects the change, the agent fixes it. The key `limit` on a
  state in `.factory/factory.yaml` sets the maximum number of entries. In the default workflow, the agent can
  enter `implement` 3 times, and the reviews can run 10 times. After the limit, the factory blocks the issue.
- **The technical retries.** Two system properties of Java set them. `factory.retries.review-session`
  (default `2`) is for a review session that fails. `factory.retries.forward-check` (default `3`) is for a
  push that GitHub does not show yet. See [Configuration](configuration.md).
- **The gates.** Each change goes through these gates, in this order:
  1. The build command. It must compile the program and pass the tests.
  2. Four reviews: correctness, security, architecture, and quality.
  3. The risk check. It gives the risk of the change: low, medium, or high.
  4. The merge gate. In the default mode `risk`, it merges only a small change with a low risk. Else, it
     waits for your approval. The mode `always-ask` always waits for your approval.

You can change the gates, the prompts of the reviews, and the limits in `.factory/factory.yaml`. See
[Factory definition](factory-definition.md).

## Limits

### Can I set a budget or a limit?

Yes. The limits are different for each provider.

- **With an API key:** set `limits.daily-budget-usd` in `worker.yaml`, for example `20.00`. This key is
  necessary with an API key. When the cost of today reaches the budget, no new session starts until midnight.
  A session that runs continues to its end, so the cost can go a little above the budget. Also set a spend
  limit on the key in the Anthropic Console.
- **With the subscription:** set `limits.usage-threshold` in `worker.yaml`. The default is `0.90`. When a usage
  window of the subscription is at this value or more, no new session starts until the window resets.

Other limits control how much work the factory does:

- `limits.parallel-issues` in `worker.yaml` gives the number of issues that the worker does at the same time.
  The default is `2`.
- `limit` on a state in `.factory/factory.yaml` stops a loop of fixes. In the default workflow, the agent can
  enter `implement` 3 times, and the reviews can run 10 times. After the limit, the factory blocks the issue.

The factory has no budget for one issue. If you want one, send a pull request. We are happy to look at it.
See [Providers](providers.md) and [Configuration](configuration.md).

## Ask a new question

When your question is not on this page, add it to the issue
[Questions for the FAQ](https://github.com/MeterianHQ/rossumworks/issues/81).
