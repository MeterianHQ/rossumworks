# Configuration

This page tells how you configure the worker: the file `worker.yaml` and the system properties of Java. The
workflow, the prompts, the build command, the target branch, and the allowed tools are not here. They are in
the folder `.factory` of your repository. See [Factory definition](factory-definition.md).

## The file `worker.yaml`

`create` writes the configuration file `<work folder>/worker.yaml`. See [Getting started](getting-started.md).
The file [`factory.example.yaml`](../factory.example.yaml) shows all the keys. The worker reads the file at
start, so a change has an effect only after a restart.

The file holds only the facts of the machine of the worker:

| Key | Value |
|---|---|
| `repository` | Necessary. Your repository on GitHub, as `owner/name`. |
| `work-directory` | Necessary. The work folder. At start, the worker clones your repository into `<work folder>/repository` when this folder does not exist. A clone that exists does not change. |
| `gatekeeper-directory` | Necessary. The folder of the gatekeeper. The **gatekeeper** is a local git repository. Each push of the worker goes through it, and it forwards the push to GitHub only when the push is safe. See [More than one factory](multiple-factories.md). |
| `live-directory` | The folder of the live files. The default is `<work folder>/live`. See [Live mode](live-mode.md). |
| `limits` | The limits of the worker. See "The limits" below. |
| `build.environment` | The variables of this machine for the build, for example `JAVA_HOME`. A variable here replaces a variable with the same name in `.factory`. |
| `claude.provider` | `subscription` (the default) or `anthropic-api-key`. See [Providers](providers.md). |
| `claude.command` | The `claude` command. The default is `claude`. |
| `claude.disallowed-tools` | The tools that the sessions of an issue cannot use. The default list has `git push`, `git remote`, `git config`, `gh`, `ssh`, `scp`, `curl`, and `wget` in Bash, and `WebFetch` and `WebSearch`. |
| `isolation.mode` | How the worker isolates the agent. The only mode now is `same-user`: the agent runs as your user. |
| `live.terminal-command` | The terminal command of the live mode. See [Live mode](live-mode.md). |
| `web` | The address, the port, and the password hash of the board. See [Web page](web-page.md). |

### The limits

| Key | Value | Default |
|---|---|---|
| `limits.parallel-issues` | The number of **slots**. A slot is a place for one card in work. See "The slots" below. | `2` |
| `limits.poll-interval` | The time between two polls of GitHub. | `60s` |
| `limits.usage-threshold` | With the subscription, no new agent, review, or risk check starts when a usage window is at this value or more. | `0.90` |
| `limits.daily-budget-usd` | With an API key, the maximum cost of one day, in US dollars. See [Providers](providers.md). | None. It is necessary with an API key. |

### The slots

Each card on the board holds a slot, also a card that waits for you. A card in Queued, a card in Done, and a
blocked card hold no slot.

- The worker takes a card from Queued, or continues a blocked card, only when a slot is free.
- A card that already holds a slot continues when no running action and no live conversation use its slot. An
  example is a card after your approval. Thus the worker does not stop when the board has more cards than slots, for example after you
  make `parallel-issues` smaller.
- When a poll cannot check an issue that is assigned to the account of the worker, the worker takes no new card
  in that poll. For example, GitHub gives an error.
- Two more things need a free slot. One is the label `factory:enter` on a blocked card. The other is a card that
  waits for a person, and that comes from the factory of another person. The poll checks all the other cards
  before it decides these two. See [Live mode](live-mode.md) and [More than one factory](multiple-factories.md).
- An issue that left Queued gets a slot before an issue in Queued, also after a restart of the worker.
- The button Stop of the board waits for the running actions and the live conversations. It does not wait for a
  card that waits for you.

### An old configuration file

An earlier version of the factory had more keys in this file. The worker does not start when the file has one of
these keys: `target`, `merge`, `accounts`, `prompts-directory`, `limits.fix-loops`, or `claude.allowed-tools`.
These settings are now in `.factory`. The message tells you to run `create`, which writes the new
configuration.

## The retries

The worker tries again after two types of technical failure. You set the number of tries with a system property
of Java:

| Retry | System property | Default number of tries |
|---|---|---|
| A review session fails, for example `claude` stops. The reviews run again. | `factory.retries.review-session` | `2` |
| After a push, GitHub does not show the commit yet. The next poll checks again. | `factory.retries.forward-check` | `3` |

For example: `java -Dfactory.retries.review-session=4 -jar rossumworks-$VERSION.jar run`.

The value is the number of tries, and the first try counts. Thus the value `2` gives one try and one retry.
After the last try, the issue goes to the [block state](glossary.md) of its workflow.

A value that is not a whole number of 1 or more stops the worker at start, with the exit code 1. The message
names the property.
