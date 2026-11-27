# Glossary

This page gives the meaning of the terms that the documents of the factory use. Each term has one meaning, and
the documents use the same term for the same thing every time. The usual terms of git, GitHub, and software,
for example issue, pull request, worktree, or poll, are not here.

When a new document needs a new term, add the term to this page first.

| Term | Meaning |
|---|---|
| action | What the worker does in a state, for example `coding`, `build`, or `reviews`. An action ends with an event. See [Factory definition](factory-definition.md). |
| agent | The `claude` session that writes the change of an issue. The worker uses the same agent again for each fix of the issue. |
| block state | The state where a blocked issue goes. In the default workflow, it is `blocked`. The error messages call it the block target. |
| board | The web page of the worker. It shows each issue as a card. See [Web page](web-page.md). |
| build command | The command that compiles and tests each change, in `build.command` of the definition. The exit code 0 means that the build passed. See [Factory definition](factory-definition.md). |
| card | One issue on the board. |
| daily budget | With an API key, the maximum cost of one day, in `limits.daily-budget-usd`. See [Providers](providers.md). |
| definition | The folder `.factory` of your repository, or the definition folder. It holds the workflow, the prompts, the build command, the target branch, and the allowed tools. See [Factory definition](factory-definition.md). |
| definition copy | A copy of one version of the definition folder, in `<work folder>/definitions/<hash>/`. The worker writes it, and an issue reads its definition from it. |
| definition folder | A folder outside the repository that holds the definition. You give it with the option `--definition` of `run` and `create`. |
| Enter | Your request to open a live conversation: the label `factory:enter`, or the button Terminal or Browser. |
| enter state | The state where an issue goes when you open the live mode. In the default workflow, it is `live`. The error messages call it the enter target. |
| event | The result of an action, for example `passed` or `failed`. The workflow uses the event to select the next state. |
| factory | All the parts together: the worker, the board, the gatekeeper, the definition, and the `claude` sessions. |
| gate | A check that each change must pass before the merge: the build command, the reviews, the risk check, and the merge gate. See [FAQ](faq.md). |
| gatekeeper | A local git repository. Each push of the worker goes through it, and it forwards the push to GitHub only when the push is safe. See [More than one factory](multiple-factories.md). |
| hidden entry | A file or a folder whose name starts with a dot, for example `.git`. The worker skips the hidden entries of the definition folder. |
| live conversation | A conversation between you and the agent of an issue, through Remote Control. |
| live files | The files of the live mode in the live folder of the machine. See [Live mode](live-mode.md). |
| live mode | The mode where you talk to the agent in a live conversation. Only the subscription has it. See [Live mode](live-mode.md). |
| log event | One line of the file `events.jsonl`. See [Events](events.md). |
| merge gate | The action `merge-gate`. It merges the change, or waits for your approval. See [Factory definition](factory-definition.md). |
| native program | The program of the factory for one platform, for example `rossumworks-$VERSION-linux-x64`. GraalVM compiles it from the jar, so it needs no JDK. See [Getting started](getting-started.md). |
| note | A short text that the worker writes in the status comment. The card shows the newest note. |
| previous | The last state with an action that does not wait for you. See [Factory definition](factory-definition.md). |
| provider | The source of the access to Claude for the `claude` sessions, and of the payment: your Claude subscription, or an Anthropic API key. See [Providers](providers.md). |
| quarantine flag | The mark `com.apple.quarantine` that a browser puts on a downloaded file on macOS. macOS blocks a program with this mark and no Apple signature. `xattr -d com.apple.quarantine <program>` removes the mark. See [Getting started](getting-started.md). |
| record | The data of an issue that the worker keeps in the status comment, for example the state. |
| Remote Control | The function of Claude Code that opens a session on claude.ai. |
| request label | A label that tells the factory what to do: `factory`, `factory:approve`, `factory:block`, `factory:enter`, or `factory:start-again`. See [Issues and labels](issues-and-labels.md). |
| review | A `claude` session that checks the change of the agent. Each review is a new session. |
| review round | One run of all the reviews of the workflow. |
| risk check | A `claude` session that gives the risk of the change (low, medium, or high), and tells if the change is small. |
| session | A conversation with `claude`. The worker can continue the session of the agent. |
| slot | A place for one card in work. A card in Queued, a card in Done, and a blocked card hold no slot. `limits.parallel-issues` gives the number of slots. See [Configuration](configuration.md). |
| start state | The first state of the workflow. In the default workflow, it is `prepare`. |
| state | One step of the workflow. An issue is always in one state. |
| status comment | The comment that the worker writes on each issue. It holds the state of the issue in the workflow. |
| takeover | The moment when the factory of a new assignee starts its work on an issue. See [More than one factory](multiple-factories.md). |
| target branch | The branch that the pull requests of the factory merge into: `merge.target-branch` in `.factory/factory.yaml`. |
| text-change state | The state where an issue goes when its text changed. In the default workflow, it is `text-changed`. |
| usage window | A period of time, for example one week, with a limit on the use of the subscription. The worker reads the percent that you used. |
| work folder | The folder of the worker, in `work-directory` of `worker.yaml`. The default is `~/.rossumworks/<owner>-<name>`. |
| worker | The program that runs on your machine: the jar or the native program. It reads the issues on GitHub and does the work of the factory. |
| workflow | The states of an issue, and the transitions between them. The definition holds it. |
| you | The person who runs the factory. |
