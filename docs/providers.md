# Providers

The factory does its work with `claude` sessions. The **provider** gives these sessions their access to Claude,
and pays for them. This page tells how you select the provider, and what changes with each provider.

Read [Legal considerations](legal-considerations.md) before you select a provider. The Anthropic documents do
not agree about the use of a subscription from a script.

## The two providers

| | Subscription | API key |
|---|---|---|
| Value of `claude.provider` | `subscription` (the default) | `anthropic-api-key` |
| Access to Claude | Your `claude` login | The Anthropic API key in the variable `ANTHROPIC_API_KEY` |
| Payment | Your Claude subscription | The Anthropic account of the key, for each token |
| Limit | The usage windows of the subscription and `limits.usage-threshold` | The daily budget in `limits.daily-budget-usd` |
| Board | It shows the usage windows | It shows "Cost today" |
| Live mode | Yes | No |

One factory uses one provider for all its sessions. You select it in `worker.yaml` with the key
`claude.provider`. `create` asks for it. At start, the worker writes one line that names the provider.

## The subscription

- Log in with `claude`.
- Do not set `ANTHROPIC_API_KEY`. If it is set, the worker does not start.
- Each successful session must report its **usage windows**: how much of the subscription you used in a period,
  for example one week. Only the subscription reports them. A successful session without them stops the factory.
- When a usage window is at `limits.usage-threshold` or more, no new agent, review, or risk check
  starts until the window resets. The worker writes a line of the type `wait` in its log of events. See
  [Events](events.md).

At start, the worker runs one very short `claude -p` session to read the usage. Thus the board shows the usage
before the first action. This session has no tools, no MCP servers, and no saved session. It uses about 4,000
tokens. The worker keeps the values only when the session uses the subscription and ends with success. After
each action, the board shows the newest values. `claude` 2.1.316 gives only the weekly window.

## The API key

- Set the variable `ANTHROPIC_API_KEY` to the key. If it is not set, the worker does not start.
- Set `limits.daily-budget-usd` in `worker.yaml`, for example `20.00`. Without it, the worker does not start.

### How the sessions get the key

No `claude` session gets the key in its environment. The worker removes each variable that starts with
`ANTHROPIC_` or `CLAUDE_CODE_USE_` from the environment of each process that it starts: `claude`, the builds, `git` and its hooks, `tmux`, and the other commands. Thus the
commands of the agent and the build cannot read the key from their environment.

The sessions get the key from a small script:

- At start, the worker writes two files in `<work folder>/credentials`: `api-key-helper.sh` and
  `api-key-settings.json`. Only you can read them.
- `api-key-helper.sh` prints the key.
- `api-key-settings.json` names this script as the `apiKeyHelper` of `claude`.
- Each session gets `--settings <path of api-key-settings.json>`, so `claude` runs the script to get the key.

`api-key-settings.json` also has `Read` and `Edit` deny rules for the folder `credentials`. These rules stop
the file tools of the agent at this folder. They do not stop every command, for example a script that opens
the file.

When you change the provider back to `subscription`, the worker deletes the folder `credentials` at start.

### The daily budget

- After each session, the worker adds the cost of the session to the cost of today. It writes the cost to
  `events.jsonl` as a line of the type `cost`. See [Events](events.md).
- When the cost of today reaches the budget, no new agent, review, or risk check starts until midnight. The time
  zone is the time zone of the machine. A session that runs continues to its end. Thus the cost of a day can go
  above the budget, by the cost of the running sessions.
- In a review round, the worker checks the budget before each review.
- At start, the worker reads the cost of today from `events.jsonl`. It ignores a line that it cannot read. If
  it cannot read the file, it does not start.
- A session that ends without a result has no cost, for example after a crash. The budget does not include it.
  Thus also set a spend limit on the key in the Anthropic Console.

### No live mode

An interactive `claude` session gets the error "credit balance too low" with a key that works in `claude -p`.
Thus the API key provider has no live mode.

- When an issue needs you, the factory blocks it with a note. You answer on GitHub.
- The board does not show the buttons of the live mode, and the worker refuses them.

## The check of each session

The worker checks that each session uses the login or the API key of the provider that you selected. When a session does not agree with
the provider, the worker stops the session and the factory, with the exit code 2. The message names the
provider.

This check protects you from a session that uses a different login or a different key. For example, an
`ANTHROPIC_API_KEY` in the `env` block of `~/.claude/settings.json` stops the factory in subscription mode.
