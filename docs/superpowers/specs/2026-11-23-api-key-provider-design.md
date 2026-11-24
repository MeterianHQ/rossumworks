# The Anthropic API key provider — design

Status: approved by the owner on 2026-11-23. Changed on 2026-11-23 after the checks with real sessions
(section 9): the sessions get the key from an `apiKeyHelper` script, and API key mode has no live mode.

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
- The live mode in API key mode (section 7).

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

After the start checks, `run` prints one line to the standard error. The line names the provider:

- `The provider is subscription: the sessions use the claude login of the owner.`
- `The provider is anthropic-api-key: the sessions use an Anthropic API key. The daily budget is $20.00.`

The line never contains the key. `run` prints it before the other start messages, for example before the
address of the web page.

When a session does not agree with the provider, the stop message also names the provider, for example
`The factory stopped: a claude session did not use the credential of the provider subscription.`

`create` asks for the provider. Enter takes `subscription`. If the owner selects `anthropic-api-key`,
`create` also asks for the daily budget, a number from 0.01 to 1000000. `create` makes the same start checks as `run`.

## 4. How a session gets the key

`ClaudeCommand.environment` removes all `ANTHROPIC_*` and `CLAUDE_CODE_USE_*` variables, as before. No
`claude` process gets `ANTHROPIC_API_KEY` in its environment, in the two modes.

In API key mode, the sessions get the key from an `apiKeyHelper` script:

- At start, `ApiKeyFiles` writes two files in the folder `<work-directory>/credentials`. The folder has the
  permissions `rwx------`.
- `api-key-helper.sh` prints the key. Its permissions are `rwx------`.
- `api-key-settings.json` contains `{"apiKeyHelper": "<path of api-key-helper.sh>"}`. Its permissions are
  `rw-------`.
- Each phase gets the arguments `--settings <path of api-key-settings.json>`, before the tool lists.
- `claude` runs the helper, and it uses the output as the key.

The worker process has the key in its environment. The worker removes it from each child process: `claude`,
the builds, git (and so the git hooks), tmux, and the other commands. Thus the Bash tool of the agent, the hooks,
and the builds do not get the key.
`BuildRunner` does not use the `Provider`. `UsageProbe` runs only in subscription mode.

The agent runs as the owner, so it can read the helper file. This is the same risk as the claude login in
`~/.claude`, which the agent can also read. To make this more difficult, the settings file also has the deny
rules `Read(//<credentials folder>/**)` and `Edit(//<credentials folder>/**)`. These rules stop the file tools,
and the Bash commands that `claude` can analyze, for example `cat`. They do not stop a script that opens the
file.

In subscription mode, the worker deletes the `credentials` folder at start. Thus a key of an earlier start in
API key mode does not stay on the disk. If the worker cannot delete the folder, it does not start.

The factory does not use `CLAUDE_CODE_SUBPROCESS_ENV_SCRUB`. On this machine, this variable forces the
permission mode to `default`, and it starts a `bwrap` sandbox that fails (section 9, check 3).

## 5. The check of each session

The `Provider` gives the expected credential. `ClaudeAgentRunner` compares each session with it.

Provider `subscription`:

1. The `init` event must have `apiKeySource` = `"none"`.
2. A successful session must send a `rate_limit_event` with usage windows.

Rule 2 is new. An `ANTHROPIC_AUTH_TOKEN` in the `env` block of `~/.claude/settings.json` also gives
`"none"`. Only the subscription sends the usage windows. Thus rule 2 proves that the session uses the
subscription.

Provider `anthropic-api-key`:

1. The `init` event must have `apiKeySource` = `"apiKeyHelper"`.

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
- `CostEvents` ignores a line that it cannot read, and writes a warning in the log. It replaces the bytes
  that are not UTF-8, so a bad line does not hide the other lines.
- If `CostEvents` cannot read the file, the worker does not start. The cost of today is then not known.

The guard:

- The interface `PhaseGuard` has the methods `recordSession`, `resumesAt`, `waitReason`, and
  `findBudgetStatus`.
- `UsageTracker` implements `PhaseGuard`, as before.
- `BudgetTracker` implements `PhaseGuard`. It keeps the cost of today and the budget.
- The `Provider` gives the guard. The worker asks the guard before each new phase. In a review round, the
  worker asks the guard before each review. When the guard stops a review, the issue keeps the verdicts
  of the finished reviews and stays in its state.
- When the cost of today is equal to the budget or more, no new phase starts until midnight. The worker
  writes a `wait` event, as it does for the usage windows.
- A phase that runs continues to its end. Thus the cost of the day can go above the budget, by the cost of
  the running phases.

The budget does not include a session that ends without a `result` event, for example after a crash. The
worker writes a warning in the log. Thus the README tells the owner to also set a spend limit on the key in
the Anthropic Console.

The board and the probe:

- In API key mode, the board shows "Cost today: $4.12 of $20.00" in place of the usage windows.
- In API key mode, the worker does not run `UsageProbe`.
- In API key mode, `limits.usage-threshold` has no effect.

## 7. The live mode

API key mode has no live mode in this version. Section 9, check 5, gives the reason: with the same key, an
interactive `claude` gets `400 Your credit balance is too low`, but `claude -p` works.

- The `Provider` tells if the live mode is available. `ApiKeyProvider` says no.
- In API key mode, `Main` uses `UnavailableLiveSessions` in place of `TmuxLiveSessions`.
- `UnavailableLiveSessions.open` and `openTerminal` throw a `LiveSessionException` with the message
  `The live mode is not available with the provider anthropic-api-key. Answer on GitHub.`
- `ConversationAction` already blocks the issue when the open fails. The note of the block is
  `The live conversation did not open: <message>`. Thus the issue waits for the owner on GitHub.
- The other methods of `UnavailableLiveSessions` do nothing: no session is open, and no continue request
  exists.
- `TmuxLiveSessions`, `LiveMode`, and the card do not change.

## 8. The components

New classes:

- `Provider`: the interface. It gives the extra arguments of a phase, the credential check, the guard, and
  the live mode availability.
- `SubscriptionProvider` and `ApiKeyProvider`: the two implementations.
- `Providers`: creates the provider of the configuration.
- `ApiKeyFiles`: writes the helper and its settings file in the private folder.
- `ProviderName`: the enum with `SUBSCRIPTION` and `ANTHROPIC_API_KEY`.
- `PhaseGuard`: the interface of the guard.
- `BudgetTracker`: the cost of today and the budget.
- `CostEvents`: writes the `cost` events and reads the cost of today.
- `UnavailableLiveSessions`: the live sessions of API key mode.
- `CredentialMismatchException`: replaces `ApiKeyDetectedException`.

Changed classes: `ClaudeSettings`, `WorkerLimits`, `ConfigurationLoader`, `StartChecks`, `ClaudeCommand`,
`ClaudeAgentRunner`, `StreamEventParser`, `SessionReport`, `UsageTracker`, `PhaseRunner`, `IssueWorkflow`,
`WorkerEvents`, `Worker`, `Main`, `CreateCommand`, and the board classes.

`Main` creates the `Provider` once, from the configuration. The other classes get the `Provider` in their
constructors. Only `Providers`, `StartChecks`, and `create` read the provider name.

## 9. Checks with real sessions

The owner and Claude ran these checks on 2026-11-23, with `claude` 2.1.330 and 2.1.331:

| Check | Result |
|---|---|
| 1. A subscription session sends usage windows. | Passes: `"none"` and the window `seven_day`. |
| 2. An `ANTHROPIC_API_KEY` session sends no windows, and gives a cost. | Passes: no window, cost 0.024. |
| 3. With `CLAUDE_CODE_SUBPROCESS_ENV_SCRUB=1`, the Bash tool works without the key. | Fails: the mode becomes `default`, and `bwrap` fails with `setting up uid map: Permission denied`. |
| 3b. With `apiKeyHelper`, the source is `"apiKeyHelper"`, and the Bash tool does not see the key. | Passes: `"apiKeyHelper"` and `0`. |
| 4. With `apiKeyHelper`, `acceptEdits` lets the agent write a file. | Passes. |
| 5. An interactive session with `apiKeyHelper` answers. | Fails: the API answers `400 Your credit balance is too low` (requests `req_011ChLVEDeXDrvaow6eK7YjA`, `req_011ChLVEDpBegNU9ACnrBgiu`). The same key works in print mode. |

Check 5 also shows that the status line of the interactive `claude` changes with the version and the
permission mode. Thus a text such as `? for shortcuts` is not a safe sign that a session is ready.

## 10. Tests

Unit tests:

- The four start cases of section 3, and the budget checks.
- The provider line at start, for each provider. The test also checks that the line does not contain the key.
- The check of each session for each provider. New stream files: a session with the source `apiKeyHelper`,
  and a subscription session without usage windows.
- `ApiKeyFiles`: the permissions of the folder and the files, and the output of the helper.
- `ClaudeAgentRunner` in API key mode: the arguments contain `--settings`, and the environment has no key.
- `BudgetTracker`: below the budget, at the budget, and the reset at midnight.
- `CostEvents`: the sum of today, the events of yesterday, and a line that it cannot read.
- `UnavailableLiveSessions`: `open` and `openTerminal` throw the message of section 7.

The tests never use a real key, the real `claude`, or the Anthropic API.

## 11. Documents

- `docs/superpowers/specs/2026-11-01-factory-design.md`: constraints 1 to 3 refer to this design.
- `README.md`: the provider, the login or the key, the budget, the Console spend limit, and no live mode in
  API key mode.
- `factory.example.yaml`: the `provider` key and the `daily-budget-usd` key, as comments.
- `docs/legal-considerations.md`: the API key provider removes the risk of item 7.
