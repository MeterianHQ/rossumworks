# The Anthropic API key provider — design

Status: approved by the owner on 2026-11-23.

This design changes constraints 1 to 3 of `docs/superpowers/specs/2026-11-01-factory-design.md`. The
factory can use the Claude subscription, as before, or an Anthropic API key. The owner selects one provider
for each factory.

## 1. Intent

The Consumer Terms, section 3, item 7, do not permit access "through automated or non-human means" with a
subscription. The exception is access with an Anthropic API key. The Claude Code documents also permit the
subscription in the unmodified `claude` CLI. Thus the factory keeps the subscription, and adds the API key
as a second provider. The owner selects the provider that agrees with the use of the factory.

## 2. Goals, non-goals, anti-goals

Goals:

- The owner selects the provider in the worker configuration.
- The worker checks each session against the provider. When a session does not agree, the worker stops the
  factory (fail closed).
- In API key mode, a daily budget prevents unlimited cost.
- The key does not reach the build processes or the subprocesses of the agent.

Non-goals:

- Other providers, for example Ollama, Bedrock, or Vertex.
- A provider for each phase. One factory has one provider.
- The cost of the live sessions in the budget.

Anti-goals:

- A session that uses a credential that the owner did not select.
- A key in a file that the factory commits, or in a file that other users can read.

## 3. Configuration and the key

```yaml
claude:
  provider: anthropic-api-key   # optional; default: subscription
limits:
  daily-budget-usd: 20.00       # necessary when the provider is anthropic-api-key
```

The values of `claude.provider` are `subscription` and `anthropic-api-key`. Another value is a
configuration error.

The worker reads the key from the variable `ANTHROPIC_API_KEY`. There is no other source of the key. The
worker configuration does not contain the key.

The start checks:

| Provider | `ANTHROPIC_API_KEY` set | Result |
|---|---|---|
| `subscription` | no | The worker starts. |
| `subscription` | yes | The worker does not start. |
| `anthropic-api-key` | yes | The worker starts. |
| `anthropic-api-key` | no | The worker does not start. |

In API key mode, the worker also needs `limits.daily-budget-usd`. The value must be more than 0. If the
value is not set, the worker does not start. In subscription mode, the worker ignores the budget.

Each message that stops the start tells the owner what to change.

`create` asks for the provider. Enter takes `subscription`. If the owner selects `anthropic-api-key`,
`create` also asks for the daily budget. `create` makes the same start checks as `run`.

## 4. The environment of the processes

`ClaudeCommand.environment` removes all `ANTHROPIC_*` and `CLAUDE_CODE_USE_*` variables, as before. Then
the `Provider` adds its variables to the environment of each `claude` process:

- `SubscriptionProvider` adds no variable.
- `ApiKeyProvider` adds `ANTHROPIC_API_KEY` and `CLAUDE_CODE_SUBPROCESS_ENV_SCRUB=1`.

This rule applies to the phases, to `UsageProbe`, and to the live sessions. `BuildRunner` does not use the
`Provider`. Thus the build processes do not get the key.

`CLAUDE_CODE_SUBPROCESS_ENV_SCRUB=1` tells `claude` to remove the key from its subprocesses: the Bash tool,
the hooks, and the MCP servers. A test on `claude` 2.1.330 shows that a hook does not see the key with this
variable. Section 9 gives the checks that are still necessary.

## 5. The check of each session

The `Provider` gives the expected credential. `ClaudeAgentRunner` and `UsageProbe` compare each session with
it.

Provider `subscription`:

1. The `init` event must have `apiKeySource` = `"none"`.
2. A successful session must send a `rate_limit_event` with usage windows.

Rule 2 is new. An `ANTHROPIC_AUTH_TOKEN` in the `env` block of `~/.claude/settings.json` also gives
`"none"`. Only the subscription sends the usage windows. Thus rule 2 proves that the session uses the
subscription.

Provider `anthropic-api-key`:

1. The `init` event must have `apiKeySource` = `"ANTHROPIC_API_KEY"`.

When a check fails, the worker stops the session and stops the factory. The exit code is 2, as before.

Names:

- `ApiKeyDetectedException` becomes `CredentialMismatchException`.
- `ClaudeAgentRunner.verifyNoApiKey` becomes `verifyCredential`.
- `StartChecks.verifyNoApiKey` becomes `verifyCredential`.
- `Worker.foundAnApiKey` becomes `foundACredentialMismatch`.

## 6. The daily budget

This section applies only to API key mode.

The cost of a session:

- The `result` event of each session has `total_cost_usd`.
- `StreamEventParser` reads this value.
- `SessionReport` gets the field `Optional<BigDecimal> costUsd`.

The record of the cost:

- After each session with a cost, the worker writes one `cost` event to `events.jsonl`. The event has the
  time, the issue number, and the cost.
- At start, `CostEvents` reads `events.jsonl` and adds up the `cost` events of today.
- Today starts at midnight, in the time zone of the worker machine.
- `CostEvents` ignores a line that it cannot read, and writes a warning in the log.

The guard:

- The interface `PhaseGuard` has the methods `mayStartPhase` and `resumesAt`.
- `UsageTracker` implements `PhaseGuard`, as before.
- `BudgetTracker` implements `PhaseGuard`. It keeps the cost of today and the budget.
- The `Provider` gives the guard. The worker asks the guard before each new phase.
- When the cost of today is equal to the budget or more, no new phase starts until midnight. The worker
  writes a `wait` event, as it does for the usage windows.
- A phase that runs continues to its end. Thus the cost of the day can go above the budget, by the cost of
  the running phases.

The budget does not include these costs:

- The live sessions. They run in tmux without stream-json, so the worker cannot read their cost.
- A session that ends without a `result` event, for example after a crash. The worker writes a warning in
  the log.

Thus the README tells the owner to also set a spend limit on the key in the Anthropic Console.

The board and the probe:

- In API key mode, the board shows "Cost today: $4.12 of $20.00" in place of the usage windows.
- In API key mode, the worker does not run `UsageProbe`.
- In API key mode, `limits.usage-threshold` has no effect.

## 7. The live mode

Remote Control needs a claude.ai subscription. It does not work with an API key.

- The `Provider` tells if Remote Control is available. `ApiKeyProvider` says no.
- In API key mode, `TmuxLiveSessions` does not add `--remote-control`. It does not wait for the link.
- `LiveSessions.open` returns `Optional<String>` in place of `String`. In API key mode, the result is empty.
- In API key mode, the board shows the command that joins the session:
  `tmux -L <server> attach -t <session>`.
- The terminal window opens as before, when `live.terminal-command` is set.
- The note of `LiveMode` tells the owner to use the tmux command when the terminal window does not open.

The launch script contains the environment, and thus the key. `LiveFiles` already writes the script with the
permissions `rwx------`, in a folder with the permissions `rwx------`.

## 8. The components

New classes:

- `Provider`: the interface. It gives the environment additions, the credential check, the guard, and the
  Remote Control availability.
- `SubscriptionProvider` and `ApiKeyProvider`: the two implementations.
- `ProviderName`: the enum with `SUBSCRIPTION` and `ANTHROPIC_API_KEY`.
- `PhaseGuard`: the interface of the guard.
- `BudgetTracker`: the cost of today and the budget.
- `CostEvents`: writes the `cost` events and reads the cost of today.
- `CredentialMismatchException`: replaces `ApiKeyDetectedException`.

Changed classes: `ClaudeSettings`, `WorkerLimits`, `ConfigurationLoader`, `StartChecks`, `ClaudeCommand`,
`ClaudeAgentRunner`, `StreamEventParser`, `SessionReport`, `UsageTracker`, `UsageProbe`, `TmuxLiveSessions`,
`LiveSessions`, `LiveMode`, `WorkerEvents`, `Worker`, `Main`, `CreateCommand`, and the board classes.

`Main` creates the `Provider` once, from the configuration. The other classes get the `Provider` in their
constructors. No other class reads the provider name.

## 9. Checks with real sessions

These checks come first in the plan. The code depends on them.

1. A subscription session always sends a `rate_limit_event` with usage windows.
2. An API key session sends no usage windows.
3. An API key session gives `apiKeySource` = `"ANTHROPIC_API_KEY"` and a `total_cost_usd`.
4. With `CLAUDE_CODE_SUBPROCESS_ENV_SCRUB=1`, the Bash tool does not see the key.
5. With `CLAUDE_CODE_SUBPROCESS_ENV_SCRUB=1`, `--permission-mode acceptEdits` still works, and no sandbox
   starts.

The API key checks use very short sessions. Their cost is less than one cent each.

If check 4 or check 5 fails, the factory does not support API key mode. The plan then stops and asks the
owner.

## 10. Tests

Unit tests:

- The four start cases of section 3, and the budget checks.
- The check of each session for each provider. New stream files: an API key session, and a subscription
  session without usage windows.
- `BudgetTracker`: below the budget, at the budget, and the reset at midnight.
- `CostEvents`: the sum of today, the events of yesterday, and a line that it cannot read.
- `TmuxLiveSessions` in API key mode: no `--remote-control`, and no wait for a link.
- `ClaudeCommand` and the providers: the key and the scrub variable reach `claude`, but not `BuildRunner`.

The tests never use a real key, the real `claude`, or the Anthropic API.

## 11. Documents

- `docs/superpowers/specs/2026-11-01-factory-design.md`: constraints 1 to 3 refer to this design.
- `README.md`: the provider, the login or the key, the budget, the Console spend limit, and the live mode
  without Remote Control.
- `factory.example.yaml`: the `provider` key and the `daily-budget-usd` key, as comments.
