# The Anthropic API key provider — implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or
> superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for
> tracking.

**Goal:** The factory can use the Claude subscription or an Anthropic API key. The owner selects one provider
for each factory.

**Architecture:** A `Provider` interface has two implementations: `SubscriptionProvider` and `ApiKeyProvider`.
`Main` creates the provider once and gives it to the classes that need it. A `PhaseGuard` interface decides
when a new phase may start: `UsageTracker` in subscription mode, `BudgetTracker` in API key mode.

**Tech Stack:** Java 21, Maven, JUnit 5, AssertJ, Jackson, tmux.

**Spec:** `docs/superpowers/specs/2026-11-23-api-key-provider-design.md`

**Branch:** `feature/api-key-provider`

---

## Rules for each task

- Write in Simplified Technical English: comments, messages, and commit messages. See `CLAUDE.md`.
- Use full names. Do not write `ctx`, `repo`, `dir`, or `e`.
- A comment has one line, and only when the code is complex.
- Run the tests of a task with `./mvnw -q test -Dtest=<TestClass>`.
- Run all the tests with `./mvnw -q verify` before each commit.
- End each commit message with the line `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## File structure

New files:

| File | Responsibility |
|---|---|
| `src/main/java/com/meterian/factory/configuration/ProviderName.java` | The two provider names and their configuration values. |
| `src/main/java/com/meterian/factory/agent/CredentialMismatchException.java` | A session did not use the credential of the provider. Replaces `ApiKeyDetectedException`. |
| `src/main/java/com/meterian/factory/provider/Provider.java` | The interface of a provider. |
| `src/main/java/com/meterian/factory/provider/SubscriptionProvider.java` | The provider that uses the claude login. |
| `src/main/java/com/meterian/factory/provider/ApiKeyProvider.java` | The provider that uses an Anthropic API key through `apiKeyHelper`. |
| `src/main/java/com/meterian/factory/provider/ApiKeyFiles.java` | Writes the helper script and its settings file in a private folder. |
| `src/main/java/com/meterian/factory/provider/Providers.java` | Creates the provider of the configuration. |
| `src/main/java/com/meterian/factory/agent/UnavailableLiveSessions.java` | The live sessions of API key mode: none opens. |
| `src/main/java/com/meterian/factory/usage/PhaseGuard.java` | Tells when a new phase may start. |
| `src/main/java/com/meterian/factory/usage/BudgetStatus.java` | The cost of today and the daily budget, for the page. |
| `src/main/java/com/meterian/factory/usage/DollarAmount.java` | Formats an amount in US dollars. |
| `src/main/java/com/meterian/factory/usage/CostEvents.java` | Writes and reads the `cost` events of `events.jsonl`. |
| `src/main/java/com/meterian/factory/usage/BudgetTracker.java` | The guard of API key mode. |
| `src/main/java/com/meterian/factory/setup/ProviderChoice.java` | The provider that `create` writes to `worker.yaml`. |
| `src/test/resources/stream/success-without-windows.jsonl` | A successful session without a `rate_limit_event`. |
| `src/test/resources/stream/api-key-helper.jsonl` | A successful session with the source `apiKeyHelper` and no usage windows. |

Changed files: see each task.

---

## Task 1: Check the facts with real sessions (done)

The owner and Claude ran these checks on 2026-11-23. Section 9 of the spec gives the results. The plan below
uses them:

- A subscription session sends usage windows. A session with an API key sends none.
- `CLAUDE_CODE_SUBPROCESS_ENV_SCRUB` does not work on this machine. The plan does not use it.
- With `apiKeyHelper`, a print-mode session gives the source `"apiKeyHelper"`. Its Bash tool does not see
  the key, and `acceptEdits` works.
- An interactive session with the key gets `400 Your credit balance is too low`. Thus API key mode has no
  live mode.

The scripts of the checks are `/tmp/factory-check-1.sh` to `/tmp/factory-check-5.sh`. They are not part of
the repository.

- [x] **Step 1: Run the checks and record the results in the spec**

---

## Task 2: Rename the API key exception

The name `ApiKeyDetectedException` is wrong when an API key is a correct credential. This task changes only
names.

**Files:**
- Rename: `src/main/java/com/meterian/factory/agent/ApiKeyDetectedException.java` to
  `src/main/java/com/meterian/factory/agent/CredentialMismatchException.java`
- Modify: all the files that use the old names (about 30 files in `src/main` and `src/test`)

- [ ] **Step 1: Rename the class file**

```bash
git mv src/main/java/com/meterian/factory/agent/ApiKeyDetectedException.java \
       src/main/java/com/meterian/factory/agent/CredentialMismatchException.java
```

- [ ] **Step 2: Replace the names in all Java files**

```bash
git grep -l -E 'ApiKeyDetectedException|foundAnApiKey|apiKeyFound|stopOnApiKey|EXIT_API_KEY' -- '*.java' \
  | xargs sed -i -E \
      -e 's/ApiKeyDetectedException/CredentialMismatchException/g' \
      -e 's/foundAnApiKey/foundACredentialMismatch/g' \
      -e 's/apiKeyFound/credentialMismatchFound/g' \
      -e 's/stopOnApiKey/stopOnCredentialMismatch/g' \
      -e 's/EXIT_API_KEY/EXIT_CREDENTIAL_MISMATCH/g'
```

- [ ] **Step 3: Give the class its Javadoc**

`src/main/java/com/meterian/factory/agent/CredentialMismatchException.java`:

```java
package com.meterian.factory.agent;

/** A claude session did not use the credential of the provider. The worker stops the factory. */
public class CredentialMismatchException extends Exception {

    public CredentialMismatchException(String message) {
        super(message);
    }
}
```

- [ ] **Step 4: Change the log line of the worker**

In `src/main/java/com/meterian/factory/worker/Worker.java`, in `stopOnCredentialMismatch`, replace:

```java
        LOGGER.log(System.Logger.Level.ERROR, "The issue #{0} found an API key: {1}", issueNumber,
                exception.getMessage());
```

with:

```java
        LOGGER.log(System.Logger.Level.ERROR, "The issue #{0} found a credential mismatch: {1}", issueNumber,
                exception.getMessage());
```

- [ ] **Step 5: Run all the tests**

Run: `./mvnw -q verify`
Expected: BUILD SUCCESS.

- [ ] **Step 6: Commit**

```bash
git add -A src
git commit -m "Rename the API key exception to CredentialMismatchException

An API key is a correct credential in API key mode.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

## Task 3: Read the provider and the budget from the configuration

**Files:**
- Create: `src/main/java/com/meterian/factory/configuration/ProviderName.java`
- Modify: `src/main/java/com/meterian/factory/configuration/ClaudeSettings.java`
- Modify: `src/main/java/com/meterian/factory/configuration/WorkerLimits.java`
- Modify: `src/main/java/com/meterian/factory/configuration/ConfigurationLoader.java`
- Test: `src/test/java/com/meterian/factory/configuration/ConfigurationLoaderTest.java`

- [ ] **Step 1: Write the failing tests**

Add to `ConfigurationLoaderTest` (it has the helper `load(String)` and the constant `REQUIRED_KEYS`). Add the
imports `java.math.BigDecimal` and `static org.assertj.core.api.Assertions.assertThatThrownBy` if they are not
there.

```java
    @Test
    void theProviderIsTheSubscriptionByDefault() throws Exception {
        FactoryConfiguration configuration = load(REQUIRED_KEYS);

        assertThat(configuration.claude().provider()).isEqualTo(ProviderName.SUBSCRIPTION);
        assertThat(configuration.limits().dailyBudgetUsd()).isEmpty();
    }

    @Test
    void readsTheApiKeyProviderAndTheBudget() throws Exception {
        FactoryConfiguration configuration = load(REQUIRED_KEYS + """
                claude:
                  provider: anthropic-api-key
                limits:
                  daily-budget-usd: 20.50
                """);

        assertThat(configuration.claude().provider()).isEqualTo(ProviderName.ANTHROPIC_API_KEY);
        assertThat(configuration.limits().dailyBudgetUsd()).hasValueSatisfying(
                budget -> assertThat(budget).isEqualByComparingTo("20.50"));
    }

    @Test
    void refusesAnUnknownProvider() {
        assertThatThrownBy(() -> load(REQUIRED_KEYS + """
                claude:
                  provider: ollama
                """))
                .isInstanceOf(ConfigurationException.class)
                .hasMessage("Configuration key claude.provider must be subscription or anthropic-api-key.");
    }

    @Test
    void theApiKeyProviderNeedsTheBudget() {
        assertThatThrownBy(() -> load(REQUIRED_KEYS + """
                claude:
                  provider: anthropic-api-key
                """))
                .isInstanceOf(ConfigurationException.class)
                .hasMessage("The provider anthropic-api-key needs the key limits.daily-budget-usd.");
    }

    @Test
    void refusesABudgetOfZero() {
        assertThatThrownBy(() -> load(REQUIRED_KEYS + """
                limits:
                  daily-budget-usd: 0
                """))
                .isInstanceOf(ConfigurationException.class)
                .hasMessage("Configuration key limits.daily-budget-usd must be more than 0.");
    }
```

- [ ] **Step 2: Run the tests to see them fail**

Run: `./mvnw -q test -Dtest=ConfigurationLoaderTest`
Expected: compilation failure: `ProviderName` and `provider()` do not exist.

- [ ] **Step 3: Create `ProviderName`**

`src/main/java/com/meterian/factory/configuration/ProviderName.java`:

```java
package com.meterian.factory.configuration;

import java.util.Arrays;
import java.util.Optional;

/** The provider of the claude sessions. The value of claude.provider in worker.yaml selects one. */
public enum ProviderName {
    SUBSCRIPTION("subscription"),
    ANTHROPIC_API_KEY("anthropic-api-key");

    private final String configurationValue;

    ProviderName(String configurationValue) {
        this.configurationValue = configurationValue;
    }

    public String configurationValue() {
        return configurationValue;
    }

    public static Optional<ProviderName> findByConfigurationValue(String value) {
        return Arrays.stream(values()).filter(name -> name.configurationValue.equals(value)).findFirst();
    }
}
```

- [ ] **Step 4: Add the provider to `ClaudeSettings`**

Replace the record header and keep the constants:

```java
public record ClaudeSettings(String command, List<String> disallowedTools, ProviderName provider) {

    public static final String DEFAULT_COMMAND = "claude";

    public static final List<String> DEFAULT_DISALLOWED_TOOLS = List.of(
            "Bash(git push*)", "Bash(git remote*)", "Bash(git config*)", "Bash(gh *)",
            "Bash(ssh *)", "Bash(scp *)", "Bash(curl *)", "Bash(wget *)",
            "WebFetch", "WebSearch");

    /** The subscription is the default provider. */
    public ClaudeSettings(String command, List<String> disallowedTools) {
        this(command, disallowedTools, ProviderName.SUBSCRIPTION);
    }

    public static ClaudeSettings createDefaults() {
        return new ClaudeSettings(DEFAULT_COMMAND, DEFAULT_DISALLOWED_TOOLS);
    }
}
```

- [ ] **Step 5: Add the budget to `WorkerLimits`**

```java
package com.meterian.factory.configuration;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Optional;

/** @param dailyBudgetUsd the daily budget in US dollars, necessary only in API key mode */
public record WorkerLimits(int parallelIssues, Duration pollInterval, double usageThreshold,
                           Optional<BigDecimal> dailyBudgetUsd) {

    public WorkerLimits(int parallelIssues, Duration pollInterval, double usageThreshold) {
        this(parallelIssues, pollInterval, usageThreshold, Optional.empty());
    }

    public static WorkerLimits createDefaults() {
        return new WorkerLimits(2, Duration.ofSeconds(60), 0.90);
    }
}
```

- [ ] **Step 6: Read the two keys in `ConfigurationLoader`**

Add the import `java.math.BigDecimal`.

In `load`, replace the `return new FactoryConfiguration(...)` statement with:

```java
        WorkerLimits limits = readLimits(root);
        ClaudeSettings claude = readClaudeSettings(root);
        verifyBudgetOfProvider(claude, limits);
        return new FactoryConfiguration(
                RepositoryName.parse(root.requireText("repository")),
                workDirectory,
                root.requirePath("gatekeeper-directory"),
                root.findPath("live-directory").orElse(workDirectory.resolve("live")),
                limits,
                readBuildEnvironment(root),
                readIsolationMode(root),
                claude,
                readWebSettings(root),
                readTerminalCommand(root));
```

In `readLimits`, replace the `return new WorkerLimits(...)` statement with:

```java
        return new WorkerLimits(
                readPositiveInteger(section, "parallel-issues", defaults.parallelIssues()),
                readPollInterval(section, defaults.pollInterval()),
                readUsageThreshold(section, defaults.usageThreshold()),
                readDailyBudget(section));
```

Replace `readClaudeSettings` with:

```java
    private static ClaudeSettings readClaudeSettings(ConfigurationSection root) throws ConfigurationException {
        ConfigurationSection section = root.findSection("claude").orElse(null);
        if (section == null) {
            return ClaudeSettings.createDefaults();
        }
        return new ClaudeSettings(
                section.findText("command").orElse(ClaudeSettings.DEFAULT_COMMAND),
                section.findTextList("disallowed-tools").orElse(ClaudeSettings.DEFAULT_DISALLOWED_TOOLS),
                readProvider(section));
    }

    private static ProviderName readProvider(ConfigurationSection section) throws ConfigurationException {
        String key = "provider";
        String value = section.findText(key).orElse(ProviderName.SUBSCRIPTION.configurationValue());
        Optional<ProviderName> provider = ProviderName.findByConfigurationValue(value);
        if (provider.isEmpty()) {
            throw section.createInvalidKeyException(key, "must be subscription or anthropic-api-key");
        }
        return provider.get();
    }

    private static Optional<BigDecimal> readDailyBudget(ConfigurationSection section)
            throws ConfigurationException {
        String key = "daily-budget-usd";
        Optional<Double> value = section.findNumber(key);
        if (value.isPresent() && value.get() <= 0.0) {
            throw section.createInvalidKeyException(key, "must be more than 0");
        }
        return value.map(BigDecimal::valueOf);
    }

    private static void verifyBudgetOfProvider(ClaudeSettings claude, WorkerLimits limits)
            throws ConfigurationException {
        if (claude.provider() == ProviderName.ANTHROPIC_API_KEY && limits.dailyBudgetUsd().isEmpty()) {
            throw new ConfigurationException("The provider anthropic-api-key needs the key limits.daily-budget-usd.");
        }
    }
```

- [ ] **Step 7: Run the tests to see them pass**

Run: `./mvnw -q test -Dtest=ConfigurationLoaderTest`
Expected: all tests pass.

- [ ] **Step 8: Run all the tests and commit**

```bash
./mvnw -q verify
git add src
git commit -m "Read the provider and the daily budget from worker.yaml

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

## Task 4: Read the cost of a session

**Files:**
- Modify: `src/main/java/com/meterian/factory/agent/SessionReport.java`
- Modify: `src/main/java/com/meterian/factory/agent/StreamEventParser.java`
- Modify: `src/main/java/com/meterian/factory/agent/ClaudeAgentRunner.java:153-157`
- Test: `src/test/java/com/meterian/factory/agent/StreamEventParserTest.java`

- [ ] **Step 1: Write the failing tests**

Add to `StreamEventParserTest`. Add the import `java.time.Clock` if it is not there.

```java
    @Test
    void readsTheCostOfTheSession() {
        StreamEventParser parser = new StreamEventParser(Clock.systemUTC());

        parser.accept("{\"type\":\"result\",\"subtype\":\"success\",\"is_error\":false,\"result\":\"ok\","
                + "\"total_cost_usd\":0.1222932}");

        assertThat(parser.report().costUsd()).hasValueSatisfying(
                cost -> assertThat(cost).isEqualByComparingTo("0.1222932"));
    }

    @Test
    void aSessionWithoutResultHasNoCost() {
        StreamEventParser parser = new StreamEventParser(Clock.systemUTC());

        parser.accept("{\"type\":\"system\",\"subtype\":\"init\",\"apiKeySource\":\"none\"}");

        assertThat(parser.report().costUsd()).isEmpty();
    }
```

- [ ] **Step 2: Run the tests to see them fail**

Run: `./mvnw -q test -Dtest=StreamEventParserTest`
Expected: compilation failure: `costUsd()` does not exist.

- [ ] **Step 3: Add the cost to `SessionReport`**

```java
package com.meterian.factory.agent;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * The result of one claude session. A stopped session did not fail: the worker asked it to end.
 *
 * @param costUsd the cost of the session in US dollars, from the result event
 */
public record SessionReport(
        boolean success,
        String resultText,
        Optional<String> apiKeySource,
        Optional<ContextUsage> contextUsage,
        List<UsageWindow> usageWindows,
        boolean stopped,
        Optional<BigDecimal> costUsd) {

    public SessionReport(boolean success, String resultText, Optional<String> apiKeySource,
            Optional<ContextUsage> contextUsage, List<UsageWindow> usageWindows) {
        this(success, resultText, apiKeySource, contextUsage, usageWindows, false, Optional.empty());
    }

    public SessionReport(boolean success, String resultText, Optional<String> apiKeySource,
            Optional<ContextUsage> contextUsage, List<UsageWindow> usageWindows, boolean stopped) {
        this(success, resultText, apiKeySource, contextUsage, usageWindows, stopped, Optional.empty());
    }
}
```

- [ ] **Step 4: Read `total_cost_usd` in `StreamEventParser`**

Add the import `java.math.BigDecimal`. Replace `buildReport` with:

```java
    private SessionReport buildReport(JsonNode resultEvent) {
        boolean success = "success".equals(resultEvent.path("subtype").asText())
                && !resultEvent.path("is_error").asBoolean(true);
        return new SessionReport(
                success,
                resultEvent.path("result").asText(""),
                apiKeySource,
                findContextUsage(resultEvent),
                List.copyOf(usageWindows),
                false,
                findCost(resultEvent));
    }

    private static Optional<BigDecimal> findCost(JsonNode resultEvent) {
        JsonNode cost = resultEvent.path("total_cost_usd");
        return cost.isNumber() ? Optional.of(cost.decimalValue()) : Optional.empty();
    }
```

- [ ] **Step 5: Keep the cost when the runner changes a report**

In `ClaudeAgentRunner.buildReport`, replace:

```java
            return new SessionReport(false, report.resultText(), report.apiKeySource(),
                    report.contextUsage(), report.usageWindows(), run.isStopRequested());
```

with:

```java
            return new SessionReport(false, report.resultText(), report.apiKeySource(),
                    report.contextUsage(), report.usageWindows(), run.isStopRequested(), report.costUsd());
```

- [ ] **Step 6: Run the tests to see them pass**

Run: `./mvnw -q test -Dtest=StreamEventParserTest`
Expected: all tests pass.

- [ ] **Step 7: Run all the tests and commit**

```bash
./mvnw -q verify
git add src
git commit -m "Read the cost of each claude session

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

## Task 5: Add the phase guard

**Files:**
- Create: `src/main/java/com/meterian/factory/usage/PhaseGuard.java`
- Create: `src/main/java/com/meterian/factory/usage/BudgetStatus.java`
- Create: `src/main/java/com/meterian/factory/usage/DollarAmount.java`
- Modify: `src/main/java/com/meterian/factory/usage/UsageTracker.java`
- Modify: `src/main/java/com/meterian/factory/workflow/PhaseRunner.java`
- Modify: `src/main/java/com/meterian/factory/workflow/IssueWorkflow.java:27,80,95`
- Test: `src/test/java/com/meterian/factory/usage/UsageTrackerTest.java`
- Test: `src/test/java/com/meterian/factory/usage/DollarAmountTest.java`

- [ ] **Step 1: Write the failing tests**

Add to `UsageTrackerTest`. Add the imports `com.meterian.factory.agent.SessionReport`, `java.time.Clock`,
`java.time.Duration`, `java.time.Instant`, `java.util.List`, and `java.util.Optional` if they are not there.

```java
    @Test
    void recordsTheUsageWindowsOfASession() {
        Instant now = Instant.parse("2026-11-23T10:00:00Z");
        UsageTracker tracker = new UsageTracker(0.90, Clock.fixed(now, java.time.ZoneOffset.UTC));
        UsageWindow full = new UsageWindow("five_hour", 0.95, now.plus(Duration.ofHours(1)), "allowed", now);

        tracker.recordSession(7, new SessionReport(true, "ok", Optional.of("none"), Optional.empty(), List.of(full)));

        assertThat(tracker.resumesAt()).contains(now.plus(Duration.ofHours(1)));
        assertThat(tracker.waitReason()).isEqualTo("usage limit");
        assertThat(tracker.findBudgetStatus()).isEmpty();
    }
```

Create `src/test/java/com/meterian/factory/usage/DollarAmountTest.java`:

```java
package com.meterian.factory.usage;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class DollarAmountTest {

    @Test
    void formatsTheAmountWithTwoDecimals() {
        assertThat(DollarAmount.format(new BigDecimal("4.125"))).isEqualTo("$4.13");
        assertThat(DollarAmount.format(new BigDecimal("20"))).isEqualTo("$20.00");
    }
}
```

- [ ] **Step 2: Run the tests to see them fail**

Run: `./mvnw -q test -Dtest='UsageTrackerTest,DollarAmountTest'`
Expected: compilation failure: `recordSession` and `DollarAmount` do not exist.

- [ ] **Step 3: Create the interface and the two value classes**

`src/main/java/com/meterian/factory/usage/PhaseGuard.java`:

```java
package com.meterian.factory.usage;

import com.meterian.factory.agent.SessionReport;
import java.time.Instant;
import java.util.Optional;

/** Tells when a new phase may start. The subscription uses the usage windows, an API key uses a budget. */
public interface PhaseGuard {

    void recordSession(int issueNumber, SessionReport report);

    /** The time when new phases may start again, or empty when they may start now. */
    Optional<Instant> resumesAt();

    /** The reason of the wait, for the wait event of events.jsonl. */
    String waitReason();

    /** The cost of today and the budget, only in API key mode. */
    Optional<BudgetStatus> findBudgetStatus();
}
```

`src/main/java/com/meterian/factory/usage/BudgetStatus.java`:

```java
package com.meterian.factory.usage;

import java.math.BigDecimal;

/** The cost of today and the daily budget, in US dollars. */
public record BudgetStatus(BigDecimal costToday, BigDecimal dailyBudget) {
}
```

`src/main/java/com/meterian/factory/usage/DollarAmount.java`:

```java
package com.meterian.factory.usage;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Formats an amount in US dollars, for example $4.12. */
public final class DollarAmount {

    private DollarAmount() {
    }

    public static String format(BigDecimal amount) {
        return "$" + amount.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }
}
```

- [ ] **Step 4: Make `UsageTracker` a `PhaseGuard`**

Change the class line to `public final class UsageTracker implements PhaseGuard {`. Add the import
`com.meterian.factory.agent.SessionReport`. Add the constant and the methods:

```java
    private static final String WAIT_REASON = "usage limit";

    @Override
    public void recordSession(int issueNumber, SessionReport report) {
        record(report.usageWindows());
    }

    @Override
    public String waitReason() {
        return WAIT_REASON;
    }

    @Override
    public Optional<BudgetStatus> findBudgetStatus() {
        return Optional.empty();
    }
```

Add `@Override` on the method `resumesAt`.

- [ ] **Step 5: Make `PhaseRunner` use the guard**

In `src/main/java/com/meterian/factory/workflow/PhaseRunner.java`:

- Replace the import `com.meterian.factory.usage.UsageTracker` with `com.meterian.factory.usage.PhaseGuard`.
- Remove the constant `USAGE_WAIT_REASON`.
- Replace the field `private final UsageTracker usage;` with `private final PhaseGuard guard;`.
- Change the Javadoc of the class to: `/** Runs one agent phase and records it in the guard, and the context of the agent in charge. */`

Replace the constructor, `mayStartPhase`, and `runAndRecordUsage` with:

```java
    PhaseRunner(AgentRunner agentRunner, PhaseGuard guard, ContextTracker contexts, WorkerEvents events) {
        this.agentRunner = agentRunner;
        this.guard = guard;
        this.contexts = contexts;
        this.events = events;
    }

    /** A phase that may not start reports the wait of the guard. */
    boolean mayStartPhase() {
        Optional<Instant> resumeTime = guard.resumesAt();
        resumeTime.ifPresent(until -> events.writeWait(guard.waitReason(), until));
        return resumeTime.isEmpty();
    }

    private SessionReport runAndRecordUsage(PhaseRequest request) throws AgentException, CredentialMismatchException {
        SessionReport report = agentRunner.runPhase(request);
        guard.recordSession(request.issueNumber(), report);
        return report;
    }
```

- [ ] **Step 6: Make `IssueWorkflow` take the guard**

In `src/main/java/com/meterian/factory/workflow/IssueWorkflow.java`:

- Replace the import `com.meterian.factory.usage.UsageTracker` with `com.meterian.factory.usage.PhaseGuard`.
- In the constructor parameters, replace `UsageTracker usage` with `PhaseGuard guard`.
- Replace `new PhaseRunner(agentRunner, usage, contexts, events)` with
  `new PhaseRunner(agentRunner, guard, contexts, events)`.
- In the Javadoc of the constructor, if a line starts with `@param usage`, replace it with
  `@param guard the guard that tells when a new phase may start`.

The tests give a `UsageTracker`. It is a `PhaseGuard`, so the tests do not change.

- [ ] **Step 7: Run the tests to see them pass**

Run: `./mvnw -q test -Dtest='UsageTrackerTest,DollarAmountTest,PhaseRunnerTest'`
Expected: all tests pass.

- [ ] **Step 8: Run all the tests and commit**

```bash
./mvnw -q verify
git add src
git commit -m "Add the phase guard and make the usage tracker one

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

## Task 6: Write and read the cost events

**Files:**
- Modify: `src/main/java/com/meterian/factory/events/WorkerEvents.java`
- Create: `src/main/java/com/meterian/factory/usage/CostEvents.java`
- Test: `src/test/java/com/meterian/factory/usage/CostEventsTest.java`

- [ ] **Step 1: Write the failing tests**

`src/test/java/com/meterian/factory/usage/CostEventsTest.java`:

```java
package com.meterian.factory.usage;

import static org.assertj.core.api.Assertions.assertThat;

import com.meterian.factory.events.EventsFile;
import com.meterian.factory.events.WorkerEvents;
import com.meterian.factory.testing.MutableClock;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CostEventsTest {

    private static final Instant YESTERDAY_EVENING = Instant.parse("2026-11-22T23:30:00Z");

    @TempDir
    Path directory;

    private final MutableClock clock = new MutableClock(YESTERDAY_EVENING);

    private CostEvents createCostEvents() {
        Path file = directory.resolve(EventsFile.FILE_NAME);
        return new CostEvents(file, new WorkerEvents(new EventsFile(file), clock));
    }

    @Test
    void writesOneCostEventForEachSession() throws Exception {
        createCostEvents().write(7, new BigDecimal("0.25"));

        assertThat(Files.readString(directory.resolve(EventsFile.FILE_NAME)))
                .isEqualTo("{\"time\":\"2026-11-22T23:30:00Z\",\"type\":\"cost\",\"issue\":7,\"cost_usd\":0.25}\n");
    }

    @Test
    void addsUpOnlyTheCostEventsOfTheDay() {
        CostEvents costEvents = createCostEvents();
        costEvents.write(7, new BigDecimal("5.00"));
        clock.advance(Duration.ofHours(1));
        costEvents.write(7, new BigDecimal("0.25"));
        costEvents.write(8, new BigDecimal("1.50"));

        BigDecimal cost = costEvents.readCostOf(LocalDate.parse("2026-11-23"), ZoneOffset.UTC);

        assertThat(cost).isEqualByComparingTo("1.75");
    }

    @Test
    void ignoresALineThatItCannotRead() throws Exception {
        CostEvents costEvents = createCostEvents();
        clock.advance(Duration.ofHours(1));
        costEvents.write(7, new BigDecimal("0.25"));
        Files.writeString(directory.resolve(EventsFile.FILE_NAME), "not json\n", StandardOpenOption.APPEND);

        BigDecimal cost = costEvents.readCostOf(LocalDate.parse("2026-11-23"), ZoneOffset.UTC);

        assertThat(cost).isEqualByComparingTo("0.25");
    }

    @Test
    void theCostIsZeroWithoutAFile() {
        assertThat(createCostEvents().readCostOf(LocalDate.parse("2026-11-23"), ZoneOffset.UTC)).isZero();
    }
}
```

- [ ] **Step 2: Run the tests to see them fail**

Run: `./mvnw -q test -Dtest=CostEventsTest`
Expected: compilation failure: `CostEvents` does not exist.

- [ ] **Step 3: Add `writeCost` to `WorkerEvents`**

Add the import `java.math.BigDecimal`. Add the method after `writeWait`:

```java
    /** The cost of one claude session in US dollars. The daily budget of API key mode adds up these lines. */
    public void writeCost(int issueNumber, BigDecimal costUsd) {
        write("cost", Optional.of(issueNumber), Map.of("cost_usd", costUsd));
    }
```

- [ ] **Step 4: Create `CostEvents`**

`src/main/java/com/meterian/factory/usage/CostEvents.java`:

```java
package com.meterian.factory.usage;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meterian.factory.events.WorkerEvents;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.stream.Stream;

/** Writes the cost of each session to events.jsonl, and reads the cost of one day from the same file. */
public final class CostEvents {

    private static final System.Logger LOGGER = System.getLogger(CostEvents.class.getName());
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String COST_TYPE = "cost";

    private final Path eventsFile;
    private final WorkerEvents events;

    public CostEvents(Path eventsFile, WorkerEvents events) {
        this.eventsFile = eventsFile;
        this.events = events;
    }

    public void write(int issueNumber, BigDecimal costUsd) {
        events.writeCost(issueNumber, costUsd);
    }

    /** A file that cannot be read gives a warning, and the cost starts at zero. */
    public BigDecimal readCostOf(LocalDate day, ZoneId zone) {
        if (!Files.isRegularFile(eventsFile)) {
            return BigDecimal.ZERO;
        }
        try (Stream<String> lines = Files.lines(eventsFile, StandardCharsets.UTF_8)) {
            return lines.map(line -> readCostOfLine(line, day, zone)).reduce(BigDecimal.ZERO, BigDecimal::add);
        } catch (IOException | UncheckedIOException exception) {
            LOGGER.log(System.Logger.Level.WARNING, "The file " + eventsFile
                    + " cannot be read. The cost of today starts at zero: " + exception.getMessage());
            return BigDecimal.ZERO;
        }
    }

    private BigDecimal readCostOfLine(String line, LocalDate day, ZoneId zone) {
        try {
            JsonNode event = JSON.readTree(line);
            if (event == null || !COST_TYPE.equals(event.path("type").asText())) {
                return BigDecimal.ZERO;
            }
            LocalDate eventDay = LocalDate.ofInstant(Instant.parse(event.path("time").asText()), zone);
            JsonNode cost = event.path("cost_usd");
            return eventDay.equals(day) && cost.isNumber() ? cost.decimalValue() : BigDecimal.ZERO;
        } catch (JsonProcessingException | DateTimeParseException exception) {
            LOGGER.log(System.Logger.Level.WARNING, "A line of " + eventsFile + " cannot be read: "
                    + exception.getMessage());
            return BigDecimal.ZERO;
        }
    }
}
```

- [ ] **Step 5: Run the tests to see them pass**

Run: `./mvnw -q test -Dtest='CostEventsTest,WorkerEventsTest'`
Expected: all tests pass.

- [ ] **Step 6: Run all the tests and commit**

```bash
./mvnw -q verify
git add src
git commit -m "Write and read the cost events of events.jsonl

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

## Task 7: Add the budget tracker

**Files:**
- Create: `src/main/java/com/meterian/factory/usage/BudgetTracker.java`
- Test: `src/test/java/com/meterian/factory/usage/BudgetTrackerTest.java`

- [ ] **Step 1: Write the failing tests**

`src/test/java/com/meterian/factory/usage/BudgetTrackerTest.java`:

```java
package com.meterian.factory.usage;

import static org.assertj.core.api.Assertions.assertThat;

import com.meterian.factory.agent.SessionReport;
import com.meterian.factory.events.EventsFile;
import com.meterian.factory.events.WorkerEvents;
import com.meterian.factory.testing.MutableClock;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BudgetTrackerTest {

    private static final Instant MORNING = Instant.parse("2026-11-23T09:00:00Z");
    private static final Instant MIDNIGHT = Instant.parse("2026-11-24T00:00:00Z");

    @TempDir
    Path directory;

    private final MutableClock clock = new MutableClock(MORNING);

    private CostEvents createCostEvents() {
        Path file = directory.resolve(EventsFile.FILE_NAME);
        return new CostEvents(file, new WorkerEvents(new EventsFile(file), clock));
    }

    private static SessionReport reportWithCost(String cost) {
        return new SessionReport(true, "ok", Optional.of("ANTHROPIC_API_KEY"), Optional.empty(), List.of(), false,
                Optional.of(new BigDecimal(cost)));
    }

    @Test
    void permitsNewPhasesBelowTheBudget() {
        BudgetTracker tracker = new BudgetTracker(new BigDecimal("10"), createCostEvents(), clock);

        tracker.recordSession(7, reportWithCost("9.99"));

        assertThat(tracker.resumesAt()).isEmpty();
        assertThat(tracker.findBudgetStatus()).contains(
                new BudgetStatus(new BigDecimal("9.99"), new BigDecimal("10")));
    }

    @Test
    void stopsNewPhasesUntilMidnightAtTheBudget() {
        BudgetTracker tracker = new BudgetTracker(new BigDecimal("10"), createCostEvents(), clock);

        tracker.recordSession(7, reportWithCost("6"));
        tracker.recordSession(8, reportWithCost("4"));

        assertThat(tracker.resumesAt()).contains(MIDNIGHT);
        assertThat(tracker.waitReason()).isEqualTo("daily budget");
    }

    @Test
    void startsAgainAtZeroOnTheNextDay() {
        BudgetTracker tracker = new BudgetTracker(new BigDecimal("10"), createCostEvents(), clock);
        tracker.recordSession(7, reportWithCost("12"));

        clock.set(MIDNIGHT.plus(Duration.ofMinutes(1)));

        assertThat(tracker.resumesAt()).isEmpty();
        assertThat(tracker.findBudgetStatus().orElseThrow().costToday()).isZero();
    }

    @Test
    void readsTheCostOfTodayAfterARestart() {
        new BudgetTracker(new BigDecimal("10"), createCostEvents(), clock).recordSession(7, reportWithCost("10"));

        BudgetTracker restarted = new BudgetTracker(new BigDecimal("10"), createCostEvents(), clock);

        assertThat(restarted.resumesAt()).contains(MIDNIGHT);
    }

    @Test
    void aSessionWithoutCostAddsNothing() {
        BudgetTracker tracker = new BudgetTracker(new BigDecimal("10"), createCostEvents(), clock);

        tracker.recordSession(7, new SessionReport(false, "The stream has no result line.", Optional.empty(),
                Optional.empty(), List.of()));

        assertThat(tracker.findBudgetStatus().orElseThrow().costToday()).isZero();
    }
}
```

`MutableClock` uses the zone UTC. Thus midnight is `2026-11-24T00:00:00Z` in these tests.

- [ ] **Step 2: Run the tests to see them fail**

Run: `./mvnw -q test -Dtest=BudgetTrackerTest`
Expected: compilation failure: `BudgetTracker` does not exist.

- [ ] **Step 3: Create `BudgetTracker`**

`src/main/java/com/meterian/factory/usage/BudgetTracker.java`:

```java
package com.meterian.factory.usage;

import com.meterian.factory.agent.SessionReport;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;

/**
 * The guard of API key mode. It adds up the cost of the sessions of today. At the daily budget, no new phase
 * starts until midnight, in the time zone of the clock.
 */
public final class BudgetTracker implements PhaseGuard {

    private static final System.Logger LOGGER = System.getLogger(BudgetTracker.class.getName());
    private static final String WAIT_REASON = "daily budget";

    private final BigDecimal dailyBudget;
    private final CostEvents costEvents;
    private final Clock clock;
    private LocalDate day;
    private BigDecimal costOfDay;

    public BudgetTracker(BigDecimal dailyBudget, CostEvents costEvents, Clock clock) {
        this.dailyBudget = dailyBudget;
        this.costEvents = costEvents;
        this.clock = clock;
        this.day = readToday();
        this.costOfDay = costEvents.readCostOf(day, clock.getZone());
    }

    @Override
    public synchronized void recordSession(int issueNumber, SessionReport report) {
        if (report.costUsd().isEmpty()) {
            LOGGER.log(System.Logger.Level.WARNING,
                    "The session of the issue #{0} gave no cost. The daily budget does not include it.", issueNumber);
            return;
        }
        startNewDayIfNecessary();
        BigDecimal cost = report.costUsd().get();
        costOfDay = costOfDay.add(cost);
        costEvents.write(issueNumber, cost);
    }

    @Override
    public synchronized Optional<Instant> resumesAt() {
        startNewDayIfNecessary();
        if (costOfDay.compareTo(dailyBudget) < 0) {
            return Optional.empty();
        }
        return Optional.of(day.plusDays(1).atStartOfDay(clock.getZone()).toInstant());
    }

    @Override
    public String waitReason() {
        return WAIT_REASON;
    }

    @Override
    public synchronized Optional<BudgetStatus> findBudgetStatus() {
        startNewDayIfNecessary();
        return Optional.of(new BudgetStatus(costOfDay, dailyBudget));
    }

    private void startNewDayIfNecessary() {
        LocalDate today = readToday();
        if (!today.equals(day)) {
            day = today;
            costOfDay = BigDecimal.ZERO;
        }
    }

    private LocalDate readToday() {
        return LocalDate.ofInstant(clock.instant(), clock.getZone());
    }
}
```

- [ ] **Step 4: Run the tests to see them pass**

Run: `./mvnw -q test -Dtest=BudgetTrackerTest`
Expected: all tests pass.

- [ ] **Step 5: Run all the tests and commit**

```bash
./mvnw -q verify
git add src
git commit -m "Add the daily budget tracker of API key mode

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

## Task 8: Add the providers and the API key files

**Files:**
- Create: `src/main/java/com/meterian/factory/provider/Provider.java`
- Create: `src/main/java/com/meterian/factory/provider/SubscriptionProvider.java`
- Create: `src/main/java/com/meterian/factory/provider/ApiKeyProvider.java`
- Create: `src/main/java/com/meterian/factory/provider/ApiKeyFiles.java`
- Create: `src/main/java/com/meterian/factory/provider/Providers.java`
- Test: `src/test/java/com/meterian/factory/provider/SubscriptionProviderTest.java`
- Test: `src/test/java/com/meterian/factory/provider/ApiKeyProviderTest.java`
- Test: `src/test/java/com/meterian/factory/provider/ApiKeyFilesTest.java`

- [ ] **Step 1: Write the failing tests**

`src/test/java/com/meterian/factory/provider/SubscriptionProviderTest.java`:

```java
package com.meterian.factory.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.meterian.factory.agent.CredentialMismatchException;
import com.meterian.factory.agent.SessionReport;
import com.meterian.factory.agent.UsageWindow;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SubscriptionProviderTest {

    private static final Instant NOW = Instant.parse("2026-11-23T10:00:00Z");
    private final SubscriptionProvider provider = new SubscriptionProvider();

    @Test
    void acceptsOnlyTheSubscriptionSource() {
        assertThatCode(() -> provider.verifyCredentialSource("none")).doesNotThrowAnyException();
        assertThatThrownBy(() -> provider.verifyCredentialSource("apiKeyHelper"))
                .isInstanceOf(CredentialMismatchException.class)
                .hasMessage("The session uses the credential source apiKeyHelper. "
                        + "The provider subscription expects none.");
    }

    @Test
    void refusesASuccessfulSessionWithoutUsageWindows() {
        SessionReport report = new SessionReport(true, "ok", Optional.of("none"), Optional.empty(), List.of());

        assertThatThrownBy(() -> provider.verifyFinishedSession(report))
                .isInstanceOf(CredentialMismatchException.class)
                .hasMessage("The session sent no usage windows. The provider subscription expects them.");
    }

    @Test
    void acceptsASuccessfulSessionWithUsageWindows() {
        UsageWindow window = new UsageWindow("five_hour", 0.2, NOW.plusSeconds(3600), "allowed", NOW);
        SessionReport report = new SessionReport(true, "ok", Optional.of("none"), Optional.empty(), List.of(window));

        assertThatCode(() -> provider.verifyFinishedSession(report)).doesNotThrowAnyException();
    }

    @Test
    void acceptsAFailedSessionWithoutUsageWindows() {
        SessionReport report = new SessionReport(false, "error", Optional.of("none"), Optional.empty(), List.of());

        assertThatCode(() -> provider.verifyFinishedSession(report)).doesNotThrowAnyException();
    }

    @Test
    void addsNoArgumentAndHasTheLiveMode() {
        assertThat(provider.phaseArguments()).isEmpty();
        assertThat(provider.hasLiveMode()).isTrue();
        assertThat(provider.readsUsageWindows()).isTrue();
        assertThat(provider.describe())
                .isEqualTo("The provider is subscription: the sessions use the claude login of the owner.");
    }
}
```

`src/test/java/com/meterian/factory/provider/ApiKeyProviderTest.java`:

```java
package com.meterian.factory.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.meterian.factory.agent.CredentialMismatchException;
import com.meterian.factory.agent.SessionReport;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ApiKeyProviderTest {

    private static final Path SETTINGS_FILE = Path.of("/work/credentials/api-key-settings.json");
    private final ApiKeyProvider provider = new ApiKeyProvider(SETTINGS_FILE, new BigDecimal("20"));

    @Test
    void acceptsOnlyTheHelperSource() {
        assertThatCode(() -> provider.verifyCredentialSource("apiKeyHelper")).doesNotThrowAnyException();
        assertThatThrownBy(() -> provider.verifyCredentialSource("none"))
                .isInstanceOf(CredentialMismatchException.class)
                .hasMessage("The session uses the credential source none. "
                        + "The provider anthropic-api-key expects apiKeyHelper.");
        assertThatThrownBy(() -> provider.verifyCredentialSource("ANTHROPIC_API_KEY"))
                .isInstanceOf(CredentialMismatchException.class);
    }

    @Test
    void acceptsASuccessfulSessionWithoutUsageWindows() {
        SessionReport report = new SessionReport(true, "ok", Optional.of("apiKeyHelper"), Optional.empty(),
                List.of());

        assertThatCode(() -> provider.verifyFinishedSession(report)).doesNotThrowAnyException();
    }

    @Test
    void givesTheSettingsFileToThePhases() {
        assertThat(provider.phaseArguments()).containsExactly("--settings", SETTINGS_FILE.toString());
    }

    @Test
    void hasNoLiveModeAndNoUsageWindows() {
        assertThat(provider.hasLiveMode()).isFalse();
        assertThat(provider.readsUsageWindows()).isFalse();
    }

    @Test
    void describesTheProviderAndTheBudget() {
        assertThat(provider.describe()).isEqualTo(
                "The provider is anthropic-api-key: the sessions use an Anthropic API key. "
                        + "The daily budget is $20.00.");
    }
}
```

`src/test/java/com/meterian/factory/provider/ApiKeyFilesTest.java`:

```java
package com.meterian.factory.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ApiKeyFilesTest {

    private static final String KEY = "sk-ant-test-key";

    @TempDir
    Path workDirectory;

    @Test
    void writesTheFilesForTheOwnerOnly() throws Exception {
        Path settings = ApiKeyFiles.write(workDirectory, KEY);

        Path folder = workDirectory.resolve("credentials");
        assertThat(settings).isEqualTo(folder.resolve("api-key-settings.json"));
        assertThat(permissionsOf(folder)).isEqualTo("rwx------");
        assertThat(permissionsOf(folder.resolve("api-key-helper.sh"))).isEqualTo("rwx------");
        assertThat(permissionsOf(settings)).isEqualTo("rw-------");
    }

    @Test
    void theHelperPrintsTheKey() throws Exception {
        ApiKeyFiles.write(workDirectory, KEY);

        Process process = new ProcessBuilder(workDirectory.resolve("credentials/api-key-helper.sh").toString())
                .start();
        assertThat(new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8))
                .isEqualTo(KEY + "\n");
    }

    @Test
    void theSettingsFileNamesTheHelperAndNotTheKey() throws Exception {
        Path settings = ApiKeyFiles.write(workDirectory, KEY);

        assertThat(Files.readString(settings))
                .contains("\"apiKeyHelper\" : \"" + workDirectory.resolve("credentials/api-key-helper.sh") + "\"")
                .doesNotContain(KEY);
    }

    @Test
    void makesAFolderThatExistsPrivate() throws Exception {
        Path folder = Files.createDirectory(workDirectory.resolve("credentials"));
        Files.setPosixFilePermissions(folder, PosixFilePermissions.fromString("rwxr-xr-x"));

        ApiKeyFiles.write(workDirectory, KEY);

        assertThat(permissionsOf(folder)).isEqualTo("rwx------");
    }

    @Test
    void refusesASymbolicLink() throws Exception {
        Path target = Files.createDirectory(workDirectory.resolve("elsewhere"));
        Files.createSymbolicLink(workDirectory.resolve("credentials"), target);

        assertThatThrownBy(() -> ApiKeyFiles.write(workDirectory, KEY))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("is a symbolic link");
    }

    private static String permissionsOf(Path path) throws IOException {
        return PosixFilePermissions.toString(Files.getPosixFilePermissions(path));
    }
}
```

- [ ] **Step 2: Run the tests to see them fail**

Run: `./mvnw -q test -Dtest='SubscriptionProviderTest,ApiKeyProviderTest,ApiKeyFilesTest'`
Expected: compilation failure: the package `com.meterian.factory.provider` does not exist.

- [ ] **Step 3: Create the interface**

`src/main/java/com/meterian/factory/provider/Provider.java`:

```java
package com.meterian.factory.provider;

import com.meterian.factory.agent.CredentialMismatchException;
import com.meterian.factory.agent.SessionReport;
import com.meterian.factory.configuration.ProviderName;
import com.meterian.factory.usage.CostEvents;
import com.meterian.factory.usage.PhaseGuard;
import com.meterian.factory.usage.UsageTracker;
import java.time.Clock;
import java.util.List;

/** The provider of the claude sessions: the subscription or an Anthropic API key. Main creates it once. */
public interface Provider {

    ProviderName name();

    /** The line that run prints at start. It never contains the key. */
    String describe();

    /** The extra arguments of each claude -p phase. They come before the tool lists. */
    List<String> phaseArguments();

    /** Checks the apiKeySource of the init event, as soon as the stream gives it. */
    void verifyCredentialSource(String apiKeySource) throws CredentialMismatchException;

    /** Checks a session after its end. */
    void verifyFinishedSession(SessionReport report) throws CredentialMismatchException;

    boolean hasLiveMode();

    /** True when the sessions send usage windows, so that the usage probe can read them. */
    boolean readsUsageWindows();

    PhaseGuard createPhaseGuard(UsageTracker usage, CostEvents costEvents, Clock clock);
}
```

- [ ] **Step 4: Create the subscription provider**

`src/main/java/com/meterian/factory/provider/SubscriptionProvider.java`:

```java
package com.meterian.factory.provider;

import com.meterian.factory.agent.CredentialMismatchException;
import com.meterian.factory.agent.SessionReport;
import com.meterian.factory.configuration.ProviderName;
import com.meterian.factory.usage.CostEvents;
import com.meterian.factory.usage.PhaseGuard;
import com.meterian.factory.usage.UsageTracker;
import java.time.Clock;
import java.util.List;

/** The sessions use the claude login of the owner. Only the subscription sends usage windows. */
public final class SubscriptionProvider implements Provider {

    private static final String SUBSCRIPTION_SOURCE = "none";

    @Override
    public ProviderName name() {
        return ProviderName.SUBSCRIPTION;
    }

    @Override
    public String describe() {
        return "The provider is subscription: the sessions use the claude login of the owner.";
    }

    @Override
    public List<String> phaseArguments() {
        return List.of();
    }

    @Override
    public void verifyCredentialSource(String apiKeySource) throws CredentialMismatchException {
        if (!SUBSCRIPTION_SOURCE.equals(apiKeySource)) {
            throw new CredentialMismatchException("The session uses the credential source " + apiKeySource
                    + ". The provider subscription expects none.");
        }
    }

    /** An ANTHROPIC_AUTH_TOKEN also gives the source none. The usage windows prove the subscription. */
    @Override
    public void verifyFinishedSession(SessionReport report) throws CredentialMismatchException {
        if (report.success() && report.usageWindows().isEmpty()) {
            throw new CredentialMismatchException(
                    "The session sent no usage windows. The provider subscription expects them.");
        }
    }

    @Override
    public boolean hasLiveMode() {
        return true;
    }

    @Override
    public boolean readsUsageWindows() {
        return true;
    }

    @Override
    public PhaseGuard createPhaseGuard(UsageTracker usage, CostEvents costEvents, Clock clock) {
        return usage;
    }
}
```

- [ ] **Step 5: Create the API key provider**

`src/main/java/com/meterian/factory/provider/ApiKeyProvider.java`:

```java
package com.meterian.factory.provider;

import com.meterian.factory.agent.CredentialMismatchException;
import com.meterian.factory.agent.SessionReport;
import com.meterian.factory.configuration.ProviderName;
import com.meterian.factory.usage.BudgetTracker;
import com.meterian.factory.usage.CostEvents;
import com.meterian.factory.usage.DollarAmount;
import com.meterian.factory.usage.PhaseGuard;
import com.meterian.factory.usage.UsageTracker;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;

/**
 * The sessions get an Anthropic API key from the apiKeyHelper of a settings file. A daily budget limits the cost.
 * The key is never in an environment, so the commands of the agent do not get it.
 */
public final class ApiKeyProvider implements Provider {

    private static final String HELPER_SOURCE = "apiKeyHelper";

    private final Path settingsFile;
    private final BigDecimal dailyBudgetUsd;

    /** @param settingsFile the settings file of {@link ApiKeyFiles}, which names the helper */
    public ApiKeyProvider(Path settingsFile, BigDecimal dailyBudgetUsd) {
        this.settingsFile = settingsFile;
        this.dailyBudgetUsd = dailyBudgetUsd;
    }

    @Override
    public ProviderName name() {
        return ProviderName.ANTHROPIC_API_KEY;
    }

    @Override
    public String describe() {
        return "The provider is anthropic-api-key: the sessions use an Anthropic API key. The daily budget is "
                + DollarAmount.format(dailyBudgetUsd) + ".";
    }

    @Override
    public List<String> phaseArguments() {
        return List.of("--settings", settingsFile.toString());
    }

    @Override
    public void verifyCredentialSource(String apiKeySource) throws CredentialMismatchException {
        if (!HELPER_SOURCE.equals(apiKeySource)) {
            throw new CredentialMismatchException("The session uses the credential source " + apiKeySource
                    + ". The provider anthropic-api-key expects apiKeyHelper.");
        }
    }

    @Override
    public void verifyFinishedSession(SessionReport report) {
    }

    /** An interactive claude gets "credit balance too low" with a key that works in print mode. */
    @Override
    public boolean hasLiveMode() {
        return false;
    }

    @Override
    public boolean readsUsageWindows() {
        return false;
    }

    @Override
    public PhaseGuard createPhaseGuard(UsageTracker usage, CostEvents costEvents, Clock clock) {
        return new BudgetTracker(dailyBudgetUsd, costEvents, clock);
    }
}
```

- [ ] **Step 6: Create the API key files**

`src/main/java/com/meterian/factory/provider/ApiKeyFiles.java`:

```java
package com.meterian.factory.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.meterian.factory.agent.ShellQuoting;
import com.meterian.factory.file.AtomicFileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;

/** Writes the apiKeyHelper script and its settings file in a folder that only the owner can read. */
public final class ApiKeyFiles {

    public static final String FOLDER_NAME = "credentials";

    private static final String HELPER_FILE_NAME = "api-key-helper.sh";
    private static final String SETTINGS_FILE_NAME = "api-key-settings.json";
    private static final String PRIVATE_DIRECTORY_PERMISSIONS = "rwx------";
    private static final String PRIVATE_EXECUTABLE_PERMISSIONS = "rwx------";
    private static final String PRIVATE_FILE_PERMISSIONS = "rw-------";
    private static final ObjectMapper JSON = new ObjectMapper();

    private ApiKeyFiles() {
    }

    /** Returns the path of the settings file that names the helper. */
    public static Path write(Path workDirectory, String apiKey) throws IOException {
        Path folder = preparePrivateFolder(workDirectory.resolve(FOLDER_NAME));
        Path helper = folder.resolve(HELPER_FILE_NAME);
        AtomicFileWriter.write(helper, createHelper(apiKey), PRIVATE_EXECUTABLE_PERMISSIONS);
        Path settings = folder.resolve(SETTINGS_FILE_NAME);
        AtomicFileWriter.write(settings, createSettings(helper), PRIVATE_FILE_PERMISSIONS);
        return settings;
    }

    private static Path preparePrivateFolder(Path folder) throws IOException {
        if (Files.isSymbolicLink(folder)) {
            throw new IOException("The folder " + folder + " is a symbolic link. Use a real folder.");
        }
        if (!Files.exists(folder, LinkOption.NOFOLLOW_LINKS)) {
            Files.createDirectories(folder.getParent());
            Files.createDirectory(folder, PosixFilePermissions.asFileAttribute(
                    PosixFilePermissions.fromString(PRIVATE_DIRECTORY_PERMISSIONS)));
        }
        Files.setPosixFilePermissions(folder, PosixFilePermissions.fromString(PRIVATE_DIRECTORY_PERMISSIONS));
        return folder;
    }

    private static byte[] createHelper(String apiKey) {
        String script = "#!/bin/sh\n" + ShellQuoting.join(List.of("printf", "%s\\n", apiKey)) + "\n";
        return script.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] createSettings(Path helper) throws IOException {
        ObjectNode root = JSON.createObjectNode();
        root.put("apiKeyHelper", helper.toString());
        return JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(root);
    }
}
```

`ShellQuoting.join` puts each word in single quotes. Thus the helper is `printf '%s\n' '<key>'`.

- [ ] **Step 7: Create the factory of the providers**

`src/main/java/com/meterian/factory/provider/Providers.java`:

```java
package com.meterian.factory.provider;

import com.meterian.factory.Main.StartupException;
import com.meterian.factory.configuration.FactoryConfiguration;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

/** Creates the provider of the configuration. The start checks run first, so the key and the budget exist. */
public final class Providers {

    private static final String API_KEY_VARIABLE = "ANTHROPIC_API_KEY";

    private Providers() {
    }

    public static Provider create(FactoryConfiguration configuration, Map<String, String> environment)
            throws StartupException {
        return switch (configuration.claude().provider()) {
            case SUBSCRIPTION -> new SubscriptionProvider();
            case ANTHROPIC_API_KEY -> createApiKeyProvider(configuration, environment.get(API_KEY_VARIABLE));
        };
    }

    private static Provider createApiKeyProvider(FactoryConfiguration configuration, String apiKey)
            throws StartupException {
        try {
            Path settingsFile = ApiKeyFiles.write(configuration.workDirectory(), apiKey);
            return new ApiKeyProvider(settingsFile, configuration.limits().dailyBudgetUsd().orElseThrow());
        } catch (IOException exception) {
            throw new StartupException("The factory cannot write the API key files in "
                    + configuration.workDirectory().resolve(ApiKeyFiles.FOLDER_NAME) + ": " + exception.getMessage());
        }
    }
}
```

- [ ] **Step 8: Run the tests to see them pass**

Run: `./mvnw -q test -Dtest='SubscriptionProviderTest,ApiKeyProviderTest,ApiKeyFilesTest'`
Expected: all tests pass.

- [ ] **Step 9: Run all the tests and commit**

```bash
./mvnw -q verify
git add src
git commit -m "Add the subscription provider and the API key provider

The API key provider gives the key through an apiKeyHelper script in a
private folder, so that no environment contains the key.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

## Task 9: Check each session against the provider

**Files:**
- Modify: `src/main/java/com/meterian/factory/agent/ClaudeCommand.java`
- Modify: `src/main/java/com/meterian/factory/agent/ClaudeAgentRunner.java`
- Modify: `src/main/java/com/meterian/factory/Main.java` (one line, so that it compiles)
- Create: `src/test/resources/stream/success-without-windows.jsonl`
- Create: `src/test/resources/stream/api-key-helper.jsonl`
- Test: `src/test/java/com/meterian/factory/agent/ClaudeCommandTest.java`
- Test: `src/test/java/com/meterian/factory/agent/ClaudeAgentRunnerTest.java`

- [ ] **Step 1: Create the two stream files**

```bash
grep -v '"type":"rate_limit_event"' src/test/resources/stream/success.jsonl \
  > src/test/resources/stream/success-without-windows.jsonl
grep -v '"type":"rate_limit_event"' src/test/resources/stream/api-key.jsonl \
  | sed 's/"apiKeySource":"ANTHROPIC_API_KEY"/"apiKeySource":"apiKeyHelper"/' \
  > src/test/resources/stream/api-key-helper.jsonl
grep -c '"apiKeySource":"apiKeyHelper"' src/test/resources/stream/api-key-helper.jsonl
```

Expected: `1`.

- [ ] **Step 2: Write the failing tests**

Add to `ClaudeCommandTest` (it has the fields `settings` and the helper `requestOf(boolean)`):

```java
    @Test
    void theProviderArgumentsComeBeforeTheToolLists() {
        List<String> arguments = ClaudeCommand.arguments(settings, requestOf(true),
                List.of("--settings", "/work/credentials/api-key-settings.json"));

        assertThat(arguments).containsSubsequence("--settings", "/work/credentials/api-key-settings.json",
                "--allowedTools");
    }
```

In `ClaudeAgentRunnerTest`, add the imports `com.meterian.factory.provider.ApiKeyProvider`,
`com.meterian.factory.provider.Provider`, `com.meterian.factory.provider.SubscriptionProvider`, and
`java.math.BigDecimal`. Replace the helper `runnerFor(String, Map, Map)` with these two helpers:

```java
    private ClaudeAgentRunner runnerFor(String stream, Map<String, String> options, Map<String, String> parent)
            throws IOException, URISyntaxException {
        return runnerFor(stream, options, parent, new SubscriptionProvider());
    }

    private ClaudeAgentRunner runnerFor(String stream, Map<String, String> options, Map<String, String> parent,
            Provider provider) throws IOException, URISyntaxException {
        return new ClaudeAgentRunner(settingsFor(stream, options), Clock.systemUTC(), directory, provider, parent,
                issueNumber -> { });
    }
```

Add the constant and the tests:

```java
    private static final Provider API_KEY_PROVIDER = new ApiKeyProvider(
            Path.of("/work/credentials/api-key-settings.json"), new BigDecimal("20"));

    @Test
    void stopsASubscriptionSessionWithoutUsageWindows() throws Exception {
        ClaudeAgentRunner runner = runnerFor("success-without-windows.jsonl", "", "0");

        assertThatThrownBy(() -> runner.runPhase(requestOf(7)))
                .isInstanceOf(CredentialMismatchException.class)
                .hasMessage("The session sent no usage windows. The provider subscription expects them.");
    }

    @Test
    void acceptsAHelperSessionInApiKeyMode() throws Exception {
        ClaudeAgentRunner runner = runnerFor("api-key-helper.jsonl", Map.of(), System.getenv(), API_KEY_PROVIDER);

        assertThat(runner.runPhase(requestOf(7)).success()).isTrue();
    }

    @Test
    void stopsASubscriptionSessionInApiKeyMode() throws Exception {
        ClaudeAgentRunner runner = runnerFor("success.jsonl", Map.of(), System.getenv(), API_KEY_PROVIDER);

        assertThatThrownBy(() -> runner.runPhase(requestOf(7)))
                .isInstanceOf(CredentialMismatchException.class)
                .hasMessage("The session uses the credential source none. "
                        + "The provider anthropic-api-key expects apiKeyHelper.");
    }

    @Test
    void givesTheSettingsFileAndNoKeyInApiKeyMode() throws Exception {
        Map<String, String> parent = Map.of("ANTHROPIC_API_KEY", "from-the-shell", "PATH", System.getenv("PATH"));

        runnerFor("api-key-helper.jsonl", Map.of(), parent, API_KEY_PROVIDER).runPhase(requestOf(7));

        assertThat(Files.readAllLines(directory.resolve("arguments.txt")))
                .containsSubsequence("--settings", "/work/credentials/api-key-settings.json");
        assertThat(Files.readString(directory.resolve("environment.txt")))
                .doesNotContain("ANTHROPIC_API_KEY", "from-the-shell");
    }
```

- [ ] **Step 3: Run the tests to see them fail**

Run: `./mvnw -q test -Dtest='ClaudeCommandTest,ClaudeAgentRunnerTest'`
Expected: compilation failure: `ClaudeCommand.arguments(ClaudeSettings, PhaseRequest, List)` and the new
constructor of `ClaudeAgentRunner` do not exist.

- [ ] **Step 4: Add the provider arguments in `ClaudeCommand`**

Replace the method `arguments` with these two methods:

```java
    /** The prompt is not an argument: the runner writes it to the standard input, after the tool lists. */
    public static List<String> arguments(ClaudeSettings settings, PhaseRequest request) {
        return arguments(settings, request, List.of());
    }

    /** @param providerArguments the arguments of the provider; they come before the tool lists */
    public static List<String> arguments(ClaudeSettings settings, PhaseRequest request,
                                         List<String> providerArguments) {
        List<String> arguments = new ArrayList<>();
        arguments.add(settings.command());
        arguments.add("-p");
        arguments.add(request.newSession() ? "--session-id" : "--resume");
        arguments.add(request.sessionId().toString());
        arguments.addAll(List.of("--output-format", "stream-json", "--verbose"));
        arguments.addAll(List.of("--permission-mode", "acceptEdits"));
        arguments.addAll(humanLanguageArguments());
        arguments.addAll(providerArguments);
        arguments.add("--allowedTools");
        arguments.addAll(request.allowedTools());
        arguments.add("--disallowedTools");
        arguments.addAll(settings.disallowedTools());
        return arguments;
    }
```

- [ ] **Step 5: Make `ClaudeAgentRunner` use the provider**

In `src/main/java/com/meterian/factory/agent/ClaudeAgentRunner.java`:

- Add the import `com.meterian.factory.provider.Provider`.
- Remove the constant `NO_API_KEY`.
- Add the field `private final Provider provider;`.

Replace the two constructors with:

```java
    public ClaudeAgentRunner(ClaudeSettings settings, Clock clock, Path claudeHome, Provider provider) {
        this(settings, clock, claudeHome, provider, System.getenv(), issueNumber -> { });
    }

    ClaudeAgentRunner(ClaudeSettings settings, Clock clock, Path claudeHome, Provider provider,
            Map<String, String> parentEnvironment, IntConsumer afterRegistration) {
        this.claudeHome = claudeHome;
        this.settings = settings;
        this.clock = clock;
        this.provider = provider;
        this.parentEnvironment = parentEnvironment;
        this.afterRegistration = afterRegistration;
    }
```

In `startProcess`, replace:

```java
        command.addAll(ClaudeCommand.arguments(settings, request));
```

with:

```java
        command.addAll(ClaudeCommand.arguments(settings, request, provider.phaseArguments()));
```

The environment does not change: `ClaudeCommand.environment(parentEnvironment)` still removes every
`ANTHROPIC_*` variable.

In `readSession`, replace `verifyNoApiKey(parser, run);` with `verifyCredential(parser, run);`.

Replace the method `verifyNoApiKey` with:

```java
    private void verifyCredential(StreamEventParser parser, Run run) throws CredentialMismatchException {
        Optional<String> source = parser.apiKeySource();
        if (source.isEmpty()) {
            return;
        }
        try {
            provider.verifyCredentialSource(source.get());
        } catch (CredentialMismatchException exception) {
            run.end();
            throw exception;
        }
    }
```

In `buildReport`, after the `if (report.success() && report.apiKeySource().isEmpty())` block, add:

```java
        provider.verifyFinishedSession(report);
```

- [ ] **Step 6: Keep `Main` compiling**

In `src/main/java/com/meterian/factory/Main.java`, add the import
`com.meterian.factory.provider.SubscriptionProvider`, and replace:

```java
        ClaudeAgentRunner agentRunner = new ClaudeAgentRunner(configuration.claude(), Clock.systemUTC(), claudeHome);
```

with:

```java
        ClaudeAgentRunner agentRunner = new ClaudeAgentRunner(configuration.claude(), Clock.systemUTC(), claudeHome,
                new SubscriptionProvider());
```

Task 10 replaces `new SubscriptionProvider()` with the provider of the configuration.

- [ ] **Step 7: Run the tests to see them pass**

Run: `./mvnw -q test -Dtest='ClaudeCommandTest,ClaudeAgentRunnerTest'`
Expected: all tests pass.

- [ ] **Step 8: Run all the tests and commit**

```bash
./mvnw -q verify
git add src
git commit -m "Check each claude session against the provider

A subscription session must also send usage windows. In API key mode,
each phase gets the settings file of the key helper.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

## Task 10: Start the worker with the provider

**Files:**
- Modify: `src/main/java/com/meterian/factory/StartChecks.java`
- Modify: `src/main/java/com/meterian/factory/Main.java`
- Modify: `src/main/java/com/meterian/factory/web/PageModel.java`
- Modify: `src/main/java/com/meterian/factory/web/PageModelSource.java`
- Test: `src/test/java/com/meterian/factory/MainTest.java`
- Test: `src/test/java/com/meterian/factory/web/PageModelSourceTest.java`
- Test: `src/test/java/com/meterian/factory/web/WebServerTest.java`
- Test: `src/test/java/com/meterian/factory/web/BoardPageTest.java`

- [ ] **Step 1: Write the failing tests**

In `MainTest`, replace the test `refusesToStartWithAnApiKey` with the tests below. Add the helper
`writeApiKeyConfiguration` next to `writeConfiguration`.

```java
    private Path writeApiKeyConfiguration() throws IOException {
        Path file = temporaryDirectory.resolve("api-key.yaml");
        Files.writeString(file, """
                repository: MeterianHQ/sample
                claude:
                  command: claude
                  provider: anthropic-api-key
                limits:
                  daily-budget-usd: 20
                work-directory: %s
                gatekeeper-directory: %s
                """.formatted(temporaryDirectory.resolve("work"), temporaryDirectory.resolve("gatekeeper.git")));
        return file;
    }

    private Map<String, String> withVariable(Map<String, String> environment, String name, String value) {
        Map<String, String> changed = new HashMap<>(environment);
        changed.put(name, value);
        return changed;
    }

    @Test
    void refusesTheSubscriptionWithAnApiKey() throws IOException {
        Path configuration = writeConfiguration("claude");

        int exitCode = runMain(new String[] {"run", "--config", configuration.toString()},
                withVariable(createEnvironmentWithAllCommands(), "ANTHROPIC_API_KEY", "secret"));

        assertThat(exitCode).isEqualTo(1);
        assertThat(errorText()).contains("Remove ANTHROPIC_API_KEY: the provider is subscription. "
                + "To use the key, set claude.provider to anthropic-api-key.").doesNotContain("secret");
    }

    @Test
    void refusesTheApiKeyProviderWithoutAKey() throws IOException {
        int exitCode = runMain(new String[] {"run", "--config", writeApiKeyConfiguration().toString()},
                createEnvironmentWithAllCommands());

        assertThat(exitCode).isEqualTo(1);
        assertThat(errorText()).contains("Set ANTHROPIC_API_KEY: the provider is anthropic-api-key. "
                + "To use the claude login, remove claude.provider.");
    }

    @Test
    void printsTheApiKeyProviderAtStart() throws IOException {
        List<Provider> started = new ArrayList<>();

        int exitCode = Main.run(new String[] {"run", "--config", writeApiKeyConfiguration().toString()},
                withVariable(createEnvironmentWithAllCommands(), "ANTHROPIC_API_KEY", "secret"), errors,
                () -> {
                    throw new AssertionError("The test reached gh");
                },
                (loaded, provider, retries, token, errorStream) -> {
                    started.add(provider);
                    return 0;
                }, createRecordingGit(new ArrayList<>()));

        assertThat(exitCode).isZero();
        assertThat(started).singleElement().isInstanceOf(ApiKeyProvider.class);
        assertThat(errorText()).contains("The provider is anthropic-api-key: the sessions use an Anthropic API "
                + "key. The daily budget is $20.00.").doesNotContain("secret");
        assertThat(temporaryDirectory.resolve("work/credentials/api-key-settings.json")).isRegularFile();
    }

    @Test
    void printsTheSubscriptionProviderAtStart() throws IOException {
        int exitCode = Main.run(new String[] {"run", "--config", writeConfiguration("claude").toString()},
                createEnvironmentWithAllCommands(), errors,
                () -> {
                    throw new AssertionError("The test reached gh");
                },
                (loaded, provider, retries, token, errorStream) -> 0, createRecordingGit(new ArrayList<>()));

        assertThat(exitCode).isZero();
        assertThat(errorText())
                .contains("The provider is subscription: the sessions use the claude login of the owner.");
    }
```

Add the imports `com.meterian.factory.provider.ApiKeyProvider`, `com.meterian.factory.provider.Provider`, and
`java.util.HashMap` if they are not there.

Each other `WorkerStarter` lambda in `MainTest` gets the new parameter. Run:

```bash
sed -i -E 's/\((configuration|loaded), retries, token, errorStream\) ->/(\1, provider, retries, token, errorStream) ->/' \
  src/test/java/com/meterian/factory/MainTest.java
```

In `PageModelSourceTest` and `WebServerTest`, the constructor of `PageModelSource` gets the guard after the
usage tracker. In `PageModelSourceTest`, replace `new PageModelSource(board, factorySwitch, usage, contexts,`
with `new PageModelSource(board, factorySwitch, usage, usage, contexts,`. In `WebServerTest`, replace
`new PageModelSource(board, factorySwitch, new UsageTracker(0.90, clock), new ContextTracker(),` with:

```java
        UsageTracker usage = new UsageTracker(0.90, clock);
        return new PageModelSource(board, factorySwitch, usage, usage, new ContextTracker(),
```

In `BoardPageTest`, the record `PageModel` gets the field `budget` after `usageResumesAt`:

```bash
sed -i -e 's/usageWindows, usageResumesAt, contexts,/usageWindows, usageResumesAt, Optional.empty(), contexts,/' \
       -e 's/model.usageResumesAt(), model.contexts()/model.usageResumesAt(), model.budget(), model.contexts()/' \
  src/test/java/com/meterian/factory/web/BoardPageTest.java
```

- [ ] **Step 2: Run the tests to see them fail**

Run: `./mvnw -q test -Dtest='MainTest,PageModelSourceTest,WebServerTest,BoardPageTest'`
Expected: compilation failure: the new `WorkerStarter` signature, the new `PageModelSource` constructor, and
`PageModel.budget()` do not exist.

- [ ] **Step 3: Replace the start check of the key**

In `src/main/java/com/meterian/factory/StartChecks.java`, add the import
`com.meterian.factory.configuration.ProviderName`, and replace `verifyNoApiKey` with:

```java
    /** The variable ANTHROPIC_API_KEY must agree with the provider of the configuration. */
    public static void verifyCredential(Map<String, String> environment, ProviderName provider)
            throws StartupException {
        boolean hasApiKey = !environment.getOrDefault(API_KEY_VARIABLE, "").isBlank();
        if (provider == ProviderName.SUBSCRIPTION && hasApiKey) {
            throw new StartupException("Remove ANTHROPIC_API_KEY: the provider is subscription. "
                    + "To use the key, set claude.provider to anthropic-api-key.");
        }
        if (provider == ProviderName.ANTHROPIC_API_KEY && !hasApiKey) {
            throw new StartupException("Set ANTHROPIC_API_KEY: the provider is anthropic-api-key. "
                    + "To use the claude login, remove claude.provider.");
        }
    }
```

Change the Javadoc of the class to:
`/** The checks that the commands run and create make before they start: the credential, the programs, the token. */`

- [ ] **Step 4: Add the budget to `PageModel`**

In `src/main/java/com/meterian/factory/web/PageModel.java`, add the import
`com.meterian.factory.usage.BudgetStatus`. After the component `Optional<Instant> usageResumesAt,` add the
component:

```java
        Optional<BudgetStatus> budget,
```

Add the line ` * @param budget            the cost of today and the daily budget, only in API key mode` to the Javadoc.

- [ ] **Step 5: Give `PageModelSource` the guard**

In `src/main/java/com/meterian/factory/web/PageModelSource.java`:

- Add the import `com.meterian.factory.usage.PhaseGuard`.
- Add the field `private final PhaseGuard guard;` after the field `usage`.
- In the constructor, add the parameter `PhaseGuard guard` after `UsageTracker usage`, and the line
  `this.guard = guard;` after `this.usage = usage;`.
- In `read`, replace `usage.resumesAt(), readContexts(snapshot),` with
  `guard.resumesAt(), guard.findBudgetStatus(), readContexts(snapshot),`.

- [ ] **Step 6: Change `Main`**

In `src/main/java/com/meterian/factory/Main.java`, add the imports:

```java
import com.meterian.factory.events.EventsFile;
import com.meterian.factory.provider.Provider;
import com.meterian.factory.provider.Providers;
import com.meterian.factory.provider.SubscriptionProvider;
import com.meterian.factory.usage.CostEvents;
import com.meterian.factory.usage.PhaseGuard;
```

(`EventsFile` can already be there.)

Replace the record `SharedState` with:

```java
    /** The objects in memory that the worker, the workflow, and the page share. Main makes each one once. */
    record SharedState(BoardState board, ContextTracker contexts, PageRequests pageRequests, UsageTracker usage,
                       PhaseGuard guard, FactorySwitch factorySwitch, LostIssues lostIssues, WorkerEvents events) {

        static SharedState create(FactoryConfiguration configuration) {
            return create(configuration, new SubscriptionProvider());
        }

        /** The budget of API key mode uses the time zone of this machine for its midnight. */
        static SharedState create(FactoryConfiguration configuration, Provider provider) {
            UsageTracker usage = new UsageTracker(configuration.limits().usageThreshold(), Clock.systemUTC());
            WorkerEvents events = new WorkerEvents(EventsFile.inWorkFolder(configuration.workDirectory()),
                    Clock.systemUTC());
            CostEvents costEvents = new CostEvents(configuration.workDirectory().resolve(EventsFile.FILE_NAME), events);
            return new SharedState(new BoardState(Clock.systemUTC()), new ContextTracker(),
                    new PageRequests(Clock.systemUTC()), usage,
                    provider.createPhaseGuard(usage, costEvents, Clock.systemDefaultZone()),
                    new FactorySwitch(configuration.workDirectory()), new LostIssues(), events);
        }
    }
```

Replace the interface `WorkerStarter` with:

```java
    /** Starts the worker after the checks. A test gives another starter, so that it never reaches the network. */
    @FunctionalInterface
    interface WorkerStarter {
        int start(FactoryConfiguration configuration, Provider provider, RetrySettings retries, String token,
                  PrintStream errors) throws InterruptedException, StartupException;
    }
```

In the third `run` method, replace the body of the `try` block with:

```java
            Path configurationFile = readConfigurationPath(arguments, finder);
            RetrySettings retries = readRetrySettings();
            FactoryConfiguration configuration = ConfigurationLoader.load(configurationFile);
            StartChecks.verifyCredential(environment, configuration.claude().provider());
            verifyCommands(configuration, environment.getOrDefault("PATH", ""));
            Gatekeeper.ensure(configuration.gatekeeperDirectory().toAbsolutePath(), configuration.repository().sshUrl());
            String token = StartChecks.readToken(environment, ghToken);
            cloneRepositoryIfMissing(configuration, git);
            Provider provider = Providers.create(configuration, environment);
            errors.println(provider.describe());
            return workerStarter.start(configuration, provider, retries, token, errors);
```

Change the signature of `runWorker` to:

```java
    private static int runWorker(FactoryConfiguration configuration, Provider provider, RetrySettings retries,
                                 String token, PrintStream errors)
            throws InterruptedException, StartupException {
```

In `runWorker`, make these changes:

- `SharedState.create(configuration)` becomes `SharedState.create(configuration, provider)`.
- In the creation of `ClaudeAgentRunner` (Task 9, step 6), replace `new SubscriptionProvider()` with
  `provider`. Remove the import `com.meterian.factory.provider.SubscriptionProvider` if no other line uses it.
  `SharedState.create(configuration)` uses it, so the import stays.
- Replace the `new UsageProbe(...)` statement with:

```java
        if (provider.readsUsageWindows()) {
            new UsageProbe(configuration.claude().command(), System.getenv(), Clock.systemUTC(),
                    UsageProbe.DEFAULT_TIMEOUT).startRecording(shared.usage());
        }
```

- Replace the last two lines of `runWorker` (the message about the API key and the return) with:

```java
        errors.println("The factory stopped: a claude session did not use the credential of the provider "
                + provider.name().configurationValue() + ".");
        return EXIT_CREDENTIAL_MISMATCH;
```

In `createWebServer`, replace `shared.usage(), shared.contexts(),` with
`shared.usage(), shared.guard(), shared.contexts(),`.

In `createWorkflow`, replace `MERGE_RETRY_DELAY, shared.usage(), layout,` with
`MERGE_RETRY_DELAY, shared.guard(), layout,`.

- [ ] **Step 7: Run the tests to see them pass**

Run: `./mvnw -q test -Dtest='MainTest,PageModelSourceTest,WebServerTest,BoardPageTest'`
Expected: all tests pass.

- [ ] **Step 8: Run all the tests and commit**

```bash
./mvnw -q verify
git add src
git commit -m "Start the worker with the provider of the configuration

The worker prints the provider at start. The usage probe runs only for the
subscription.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

## Task 11: Show the cost of today on the board

**Files:**
- Modify: `src/main/java/com/meterian/factory/web/BoardPage.java:82`
- Test: `src/test/java/com/meterian/factory/web/BoardPageTest.java`

- [ ] **Step 1: Write the failing test**

Add to `BoardPageTest`. Add the imports `com.meterian.factory.usage.BudgetStatus` and `java.math.BigDecimal`.

```java
    private static PageModel withBudget(PageModel model, BudgetStatus budget) {
        return new PageModel(model.board(), model.renderedAt(), model.issuesInWork(), model.parallelLimit(),
                model.limits(), model.reviewNames(), model.inBlockTarget(), model.isolationMode(), model.pollInterval(),
                model.usageWindows(), model.usageResumesAt(), Optional.of(budget), model.contexts(),
                model.continueRequested(), model.rememberedLabels(), model.enterPlaces(), model.repositoryFullName(),
                model.viewerOnThisMachine(), model.definitionWarning(), model.factoryState(), model.columns());
    }

    @Test
    void showsTheCostOfTodayInApiKeyMode() {
        PageModel model = withBudget(model(List.of(), Optional.of(NOW), List.of(), Optional.empty(), Map.of()),
                new BudgetStatus(new BigDecimal("4.123"), new BigDecimal("20")));

        String html = page.renderBoard(model);

        assertThat(html).contains("<div class=\"tile\"><span class=\"tile-label\">Cost today</span>"
                + "<span class=\"tile-value\">$4.12 of $20.00</span></div>");
    }
```

- [ ] **Step 2: Run the test to see it fail**

Run: `./mvnw -q test -Dtest=BoardPageTest#showsTheCostOfTodayInApiKeyMode`
Expected: FAIL: the HTML does not contain "Cost today".

- [ ] **Step 3: Add the tile**

In `src/main/java/com/meterian/factory/web/BoardPage.java`, add the import
`com.meterian.factory.usage.DollarAmount`. In `appendHealth`, after the line
`model.usageWindows().forEach(window -> appendUsageTile(html, window));`, add:

```java
        model.budget().ifPresent(budget -> appendTile(html, "Cost today",
                DollarAmount.format(budget.costToday()) + " of " + DollarAmount.format(budget.dailyBudget())));
```

- [ ] **Step 4: Run the tests to see them pass**

Run: `./mvnw -q test -Dtest=BoardPageTest`
Expected: all tests pass.

- [ ] **Step 5: Run all the tests and commit**

```bash
./mvnw -q verify
git add src
git commit -m "Show the cost of today on the board in API key mode

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

## Task 12: Refuse the live mode in API key mode

An interactive `claude` gets `400 Your credit balance is too low` with a key that works in print mode (spec,
section 9, check 5). Thus API key mode has no live mode. `ConversationAction` already blocks the issue when
the open fails, so the issue waits for the owner on GitHub.

**Files:**
- Create: `src/main/java/com/meterian/factory/agent/UnavailableLiveSessions.java`
- Modify: `src/main/java/com/meterian/factory/Main.java` (the creation of the live sessions)
- Test: `src/test/java/com/meterian/factory/agent/UnavailableLiveSessionsTest.java`

- [ ] **Step 1: Write the failing test**

`src/test/java/com/meterian/factory/agent/UnavailableLiveSessionsTest.java`:

```java
package com.meterian.factory.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class UnavailableLiveSessionsTest {

    private static final String MESSAGE =
            "The live mode is not available with the provider anthropic-api-key. Answer on GitHub.";

    private final UnavailableLiveSessions sessions = new UnavailableLiveSessions();

    @Test
    void refusesToOpenASession() {
        assertThatThrownBy(() -> sessions.open(7, UUID.randomUUID(), Path.of("/work"), List.of("Read")))
                .isInstanceOf(LiveSessionException.class)
                .hasMessage(MESSAGE);
    }

    @Test
    void refusesToOpenATerminal() {
        assertThatThrownBy(() -> sessions.openTerminal(7))
                .isInstanceOf(LiveSessionException.class)
                .hasMessage(MESSAGE);
    }

    @Test
    void hasNoOpenSessionAndNoContinueRequest() {
        sessions.requestContinue(7);

        assertThat(sessions.isOpen(7)).isFalse();
        assertThat(sessions.isContinueRequested(7)).isFalse();
    }
}
```

- [ ] **Step 2: Run the test to see it fail**

Run: `./mvnw -q test -Dtest=UnavailableLiveSessionsTest`
Expected: compilation failure: `UnavailableLiveSessions` does not exist.

- [ ] **Step 3: Check the constructors of `LiveSessionException`**

```bash
grep -n "public LiveSessionException" src/main/java/com/meterian/factory/agent/LiveSessionException.java
```

Expected: a constructor with one `String` parameter. If it is not there, add it:

```java
    public LiveSessionException(String message) {
        super(message);
    }
```

- [ ] **Step 4: Create `UnavailableLiveSessions`**

`src/main/java/com/meterian/factory/agent/UnavailableLiveSessions.java`:

```java
package com.meterian.factory.agent;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

/** The live sessions of API key mode: no session opens, so an issue that needs the owner waits on GitHub. */
public final class UnavailableLiveSessions implements LiveSessions {

    private static final String UNAVAILABLE_MESSAGE =
            "The live mode is not available with the provider anthropic-api-key. Answer on GitHub.";

    @Override
    public String open(int issueNumber, UUID sessionId, Path worktree, List<String> allowedTools)
            throws LiveSessionException {
        throw new LiveSessionException(UNAVAILABLE_MESSAGE);
    }

    @Override
    public boolean isOpen(int issueNumber) {
        return false;
    }

    @Override
    public void close(int issueNumber) {
    }

    @Override
    public boolean isContinueRequested(int issueNumber) {
        return false;
    }

    @Override
    public void openTerminal(int issueNumber) throws LiveSessionException {
        throw new LiveSessionException(UNAVAILABLE_MESSAGE);
    }

    @Override
    public void clearContinueRequest(int issueNumber) {
    }

    @Override
    public void requestContinue(int issueNumber) {
    }
}
```

- [ ] **Step 5: Select the live sessions in `Main`**

In `src/main/java/com/meterian/factory/Main.java`, add the imports
`com.meterian.factory.agent.LiveSessions` (if it is not there) and
`com.meterian.factory.agent.UnavailableLiveSessions`. In `runWorker`, replace:

```java
        TmuxLiveSessions liveSessions = new TmuxLiveSessions(configuration.claude(), liveFiles, System.getenv(),
                TmuxLiveSessions.DEFAULT_OPEN_TIMEOUT,
                configuration.terminalCommand());
```

with:

```java
        LiveSessions liveSessions = createLiveSessions(configuration, provider, liveFiles);
```

Add the method after `runWorker`:

```java
    /** API key mode has no live mode: an interactive claude gets no answer with the key. */
    private static LiveSessions createLiveSessions(FactoryConfiguration configuration, Provider provider,
                                                   LiveFiles liveFiles) {
        if (!provider.hasLiveMode()) {
            return new UnavailableLiveSessions();
        }
        return new TmuxLiveSessions(configuration.claude(), liveFiles, System.getenv(),
                TmuxLiveSessions.DEFAULT_OPEN_TIMEOUT, configuration.terminalCommand());
    }
```

If a later line of `runWorker` needs the type `TmuxLiveSessions`, the build tells you. All the users in
`src/main` take `LiveSessions` on 2026-11-23.

- [ ] **Step 6: Run the tests to see them pass**

Run: `./mvnw -q test -Dtest='UnavailableLiveSessionsTest,MainTest'`
Expected: all tests pass.

- [ ] **Step 7: Run all the tests and commit**

```bash
./mvnw -q verify
git add src
git commit -m "Refuse the live mode in API key mode

An interactive claude gets no answer with the key. The issue is blocked
with a note and waits for the owner on GitHub.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

## Task 13: Ask for the provider in the create command

**Files:**
- Create: `src/main/java/com/meterian/factory/setup/ProviderChoice.java`
- Modify: `src/main/java/com/meterian/factory/setup/CreateOptions.java`
- Modify: `src/main/java/com/meterian/factory/setup/SetupQuestions.java`
- Modify: `src/main/java/com/meterian/factory/setup/WorkerSettings.java`
- Modify: `src/main/java/com/meterian/factory/setup/WorkerConfigurationWriter.java`
- Modify: `src/main/java/com/meterian/factory/setup/SetupSummary.java`
- Modify: `src/main/java/com/meterian/factory/setup/CreateCommand.java:84-91`
- Test: `src/test/java/com/meterian/factory/setup/CreateOptionsTest.java`
- Test: `src/test/java/com/meterian/factory/setup/WorkerConfigurationWriterTest.java`
- Test: `src/test/java/com/meterian/factory/setup/CreateCommandTest.java`

- [ ] **Step 1: Write the failing tests**

Add to `CreateOptionsTest`. Add the imports `com.meterian.factory.configuration.ProviderName` and
`java.math.BigDecimal`, and `java.util.List` if it is not there.

```java
    @Test
    void readsTheProviderAndTheBudget() throws Exception {
        CreateOptions options = CreateOptions.parse(
                List.of("--provider", "anthropic-api-key", "--daily-budget-usd", "15.5"));

        assertThat(options.provider()).contains(ProviderName.ANTHROPIC_API_KEY);
        assertThat(options.dailyBudgetUsd()).hasValueSatisfying(budget -> assertThat(budget).isEqualByComparingTo("15.5"));
    }

    @Test
    void refusesAnUnknownProvider() {
        assertThatThrownBy(() -> CreateOptions.parse(List.of("--provider", "ollama")))
                .isInstanceOf(SetupException.class)
                .hasMessageStartingWith("The provider must be subscription or anthropic-api-key.");
    }

    @Test
    void refusesABudgetThatIsNotMoreThanZero() {
        assertThatThrownBy(() -> CreateOptions.parse(List.of("--daily-budget-usd", "0")))
                .isInstanceOf(SetupException.class)
                .hasMessageStartingWith("The daily budget must be a number more than 0.");
    }
```

Add to `WorkerConfigurationWriterTest`. The existing tests make a `WorkerSettings` with five values; the new
test uses the new sixth value. Add the imports `com.meterian.factory.configuration.ProviderName`,
`java.math.BigDecimal`, `java.nio.file.Path`, `java.util.Optional`, and `com.meterian.factory.configuration.RepositoryName`
if they are not there.

```java
    @Test
    void writesTheApiKeyProviderAndTheBudget() throws Exception {
        WorkerSettings settings = new WorkerSettings(RepositoryName.parse("MeterianHQ/sample"), Path.of("/work"),
                Optional.empty(), Optional.empty(), Optional.empty(),
                new ProviderChoice(ProviderName.ANTHROPIC_API_KEY, Optional.of(new BigDecimal("20"))));

        String text = WorkerConfigurationWriter.render(settings);

        assertThat(text).contains("claude:\n  provider: anthropic-api-key\nlimits:\n  daily-budget-usd: 20\n");
    }

    @Test
    void theSubscriptionGivesOnlyAComment() throws Exception {
        WorkerSettings settings = new WorkerSettings(RepositoryName.parse("MeterianHQ/sample"), Path.of("/work"),
                Optional.empty(), Optional.empty(), Optional.empty());

        String text = WorkerConfigurationWriter.render(settings);

        assertThat(text).contains("# claude:\n#   provider: anthropic-api-key")
                .doesNotContain("\nclaude:").doesNotContain("\nlimits:");
    }
```

- [ ] **Step 2: Run the tests to see them fail**

Run: `./mvnw -q test -Dtest='CreateOptionsTest,WorkerConfigurationWriterTest'`
Expected: compilation failure: `provider()`, `dailyBudgetUsd()`, and `ProviderChoice` do not exist.

- [ ] **Step 3: Create `ProviderChoice`**

`src/main/java/com/meterian/factory/setup/ProviderChoice.java`:

```java
package com.meterian.factory.setup;

import com.meterian.factory.configuration.ProviderName;
import java.math.BigDecimal;
import java.util.Optional;

/** The provider that create writes to worker.yaml. The API key provider always has a budget. */
public record ProviderChoice(ProviderName name, Optional<BigDecimal> dailyBudgetUsd) {

    public static ProviderChoice subscription() {
        return new ProviderChoice(ProviderName.SUBSCRIPTION, Optional.empty());
    }

    /** Returns the budget, or empty when the text is not a number more than 0. */
    public static Optional<BigDecimal> parseDailyBudget(String text) {
        try {
            BigDecimal budget = new BigDecimal(text.strip());
            return budget.signum() > 0 ? Optional.of(budget) : Optional.empty();
        } catch (NumberFormatException exception) {
            return Optional.empty();
        }
    }
}
```

- [ ] **Step 4: Read the two parameters in `CreateOptions`**

Add the imports `com.meterian.factory.configuration.ProviderName` and `java.math.BigDecimal`.

Add two components at the end of the record: `Optional<ProviderName> provider, Optional<BigDecimal> dailyBudgetUsd`.

Replace the constant `USAGE` with:

```java
    public static final String USAGE = "Usage: java -jar rossumworks.jar create [--repository owner/name]\n"
            + "       [--work-directory <path>] [--web <address:port> | --no-web]\n"
            + "       [--terminal-command \"<command>\"] [--build-command \"<command>\"]\n"
            + "       [--provider subscription|anthropic-api-key] [--daily-budget-usd <amount>] [--yes]";
```

In `parse`, add the two variables before the loop:

```java
        Optional<ProviderName> provider = Optional.empty();
        Optional<BigDecimal> dailyBudgetUsd = Optional.empty();
```

Add two cases to the `switch`, before `default`:

```java
                case "--provider" -> provider = Optional.of(parseProvider(readValue(arguments, ++index, parameter)));
                case "--daily-budget-usd" ->
                        dailyBudgetUsd = Optional.of(parseDailyBudget(readValue(arguments, ++index, parameter)));
```

Replace the `return` statement with:

```java
        return new CreateOptions(repository, workDirectory, web, noWeb, terminalCommand, buildCommand, yes,
                provider, dailyBudgetUsd);
```

Add the two methods:

```java
    private static ProviderName parseProvider(String value) throws SetupException {
        return ProviderName.findByConfigurationValue(value).orElseThrow(() -> new SetupException(
                "The provider must be subscription or anthropic-api-key.\n" + USAGE));
    }

    private static BigDecimal parseDailyBudget(String value) throws SetupException {
        return ProviderChoice.parseDailyBudget(value).orElseThrow(() -> new SetupException(
                "The daily budget must be a number more than 0.\n" + USAGE));
    }
```

- [ ] **Step 5: Ask the questions in `SetupQuestions`**

Add the imports `com.meterian.factory.configuration.ProviderName`, `java.math.BigDecimal`, and
`java.util.Optional` if they are not there. Add the methods after `askTerminalCommand`:

```java
    /** Without a terminal, the default is the subscription. Enter also takes the subscription. */
    public ProviderChoice askProvider(CreateOptions options) throws SetupException {
        ProviderName name = options.provider().isPresent() ? options.provider().get() : askProviderName();
        if (name == ProviderName.SUBSCRIPTION) {
            return ProviderChoice.subscription();
        }
        BigDecimal budget = options.dailyBudgetUsd().isPresent() ? options.dailyBudgetUsd().get() : askDailyBudget();
        return new ProviderChoice(name, Optional.of(budget));
    }

    private ProviderName askProviderName() throws SetupException {
        if (!console.isInteractive()) {
            return ProviderName.SUBSCRIPTION;
        }
        while (true) {
            String answer = console.ask("The provider (subscription or anthropic-api-key)",
                    ProviderName.SUBSCRIPTION.configurationValue());
            Optional<ProviderName> name = ProviderName.findByConfigurationValue(answer.strip());
            if (name.isPresent()) {
                return name.get();
            }
            console.show("Type subscription or anthropic-api-key.");
        }
    }

    private BigDecimal askDailyBudget() throws SetupException {
        if (!console.isInteractive()) {
            throw new SetupException("Give --daily-budget-usd with --provider anthropic-api-key.");
        }
        while (true) {
            Optional<BigDecimal> budget = ProviderChoice.parseDailyBudget(
                    console.ask("The daily budget in US dollars", ""));
            if (budget.isPresent()) {
                return budget.get();
            }
            console.show("Type a number more than 0, for example 20.");
        }
    }
```

- [ ] **Step 6: Add the provider to `WorkerSettings`**

Add the component `ProviderChoice provider` at the end of the record, and this constructor:

```java
    /** The subscription is the default provider. */
    public WorkerSettings(RepositoryName repository, Path workDirectory, Optional<WebPage> web,
                          Optional<List<String>> terminalCommand, Optional<String> javaHome) {
        this(repository, workDirectory, web, terminalCommand, javaHome, ProviderChoice.subscription());
    }
```

- [ ] **Step 7: Write the provider in `WorkerConfigurationWriter`**

In `render`, add `appendProvider(text, settings);` after `appendBuildEnvironment(text, settings);`. Add the
method:

```java
    private static void appendProvider(StringBuilder text, WorkerSettings settings) {
        ProviderChoice provider = settings.provider();
        if (provider.dailyBudgetUsd().isEmpty()) {
            text.append("# claude:\n#   provider: anthropic-api-key   # needs ANTHROPIC_API_KEY\n")
                    .append("# limits:\n#   daily-budget-usd: 20\n");
            return;
        }
        text.append("claude:\n  provider: ").append(provider.name().configurationValue()).append('\n');
        text.append("limits:\n  daily-budget-usd: ").append(provider.dailyBudgetUsd().get().toPlainString())
                .append('\n');
    }
```

Some tests of `WorkerConfigurationWriterTest` compare the full text. In each of them, add the four comment lines
of the subscription after the lines of `build`:

```
# claude:
#   provider: anthropic-api-key   # needs ANTHROPIC_API_KEY
# limits:
#   daily-budget-usd: 20
```

- [ ] **Step 8: Show the provider in `SetupSummary`**

In `describe(WorkerSettings settings)`, after the line of `JAVA_HOME`, add the line of the provider. The
method becomes:

```java
    static String describe(WorkerSettings settings) {
        return "The command writes the file " + settings.configurationFile() + ":\n"
                + "Repository: " + settings.repository().fullName() + '\n'
                + "Work folder: " + settings.workDirectory() + '\n'
                + "Web page: " + describeWebPage(settings.web()) + '\n'
                + "Terminal command: " + settings.terminalCommand().map(command -> String.join(" ", command))
                        .orElse("none") + '\n'
                + "JAVA_HOME: " + settings.javaHome().orElse("not set") + '\n'
                + "Provider: " + describeProvider(settings.provider()) + '\n';
    }

    private static String describeProvider(ProviderChoice provider) {
        return provider.name().configurationValue() + provider.dailyBudgetUsd()
                .map(budget -> ", daily budget " + DollarAmount.format(budget)).orElse("");
    }
```

Add the import `com.meterian.factory.usage.DollarAmount`.

- [ ] **Step 9: Use the provider in `CreateCommand`**

In `create`, remove the first line `StartChecks.verifyNoApiKey(environment);`. After the line
`Optional<List<String>> terminalCommand = questions.askTerminalCommand(options);`, add:

```java
        ProviderChoice provider = questions.askProvider(options);
        StartChecks.verifyCredential(environment, provider.name());
```

Replace:

```java
        WorkerSettings settings = new WorkerSettings(repository, workDirectory, web, terminalCommand,
                findJavaHome(facts));
```

with:

```java
        WorkerSettings settings = new WorkerSettings(repository, workDirectory, web, terminalCommand,
                findJavaHome(facts), provider);
```

- [ ] **Step 10: Change the old message in `CreateCommandTest`**

Find the old message:

```bash
grep -n "the factory uses only the Claude subscription" src/test/java/com/meterian/factory/setup/CreateCommandTest.java
```

Replace each match with `the provider is subscription`. The check now runs after the questions. If the test
fails because it does not answer the new question, add the answer `""` (Enter) for the provider question in the
answers of its `FakeSetupConsole`, after the answer of the terminal command.

- [ ] **Step 11: Run the tests to see them pass**

Run: `./mvnw -q test -Dtest='CreateOptionsTest,WorkerConfigurationWriterTest,CreateCommandTest,SetupQuestionsTest'`
Expected: all tests pass. If a `SetupQuestionsTest` or `CreateCommandTest` test fails because the console gets
one more question, add the answer `""` for the provider question to its fake console.

- [ ] **Step 12: Run all the tests and commit**

```bash
./mvnw -q verify
git add src
git commit -m "Ask for the provider in the create command

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

## Task 14: Update the documents

**Files:**
- Modify: `README.md`
- Modify: `factory.example.yaml`
- Modify: `docs/superpowers/specs/2026-11-01-factory-design.md` (section 3)
- Modify: `docs/legal-considerations.md` (section 6)

- [ ] **Step 1: Update `factory.example.yaml`**

After the `limits:` block, change it to:

```yaml
limits:                 # all keys optional
  parallel-issues: 2
  poll-interval: 60s
  usage-threshold: 0.90         # subscription only
  # daily-budget-usd: 20.00     # necessary when claude.provider is anthropic-api-key
```

In the `claude:` block, add the line:

```yaml
  # provider: anthropic-api-key   # default: subscription; anthropic-api-key needs ANTHROPIC_API_KEY
```

Run: `./mvnw -q test -Dtest=ConfigurationLoaderTest#loadsTheExampleConfiguration`
Expected: PASS.

- [ ] **Step 2: Update the README**

Make these changes in `README.md`. Write in Simplified Technical English.

1. Line 2: replace "A simple AI factory that uses your Claude subscription and runs on GitHub." with
   "A simple AI factory that uses your Claude subscription or an Anthropic API key, and runs on GitHub."
2. In "Configuration", in the item of `limits`, add `daily-budget-usd`. Add an item for `claude.provider`:
   "`claude.provider`: `subscription` (the default) or `anthropic-api-key`. See "Provider"."
3. In "Setup", replace step 1 with: "1. Select the provider (see "Provider"). For the subscription, log in to
   the `claude` CLI and do not set `ANTHROPIC_API_KEY`. For an API key, set `ANTHROPIC_API_KEY`."
4. Add a section "## Provider" after "## Legal considerations", with this content:

```markdown
## Provider

The factory uses one provider for all its claude sessions. The key `claude.provider` of `worker.yaml` selects
it. At start, the worker prints the provider in one line.

- `subscription`: the sessions use your `claude` login. Do not set `ANTHROPIC_API_KEY`: if it is set, the
  worker does not start. Each session must send usage windows. A session without them stops the factory.
- `anthropic-api-key`: the worker reads the key from `ANTHROPIC_API_KEY`. If it is not set, the worker does
  not start. The key `limits.daily-budget-usd` is necessary.

In API key mode:

- At start, the worker writes `<work-directory>/credentials/api-key-helper.sh` and `api-key-settings.json`.
  Only you can read them. Each phase gets `--settings <path of api-key-settings.json>`, and `claude` gets
  the key from the helper. No environment contains the key, so the commands of the agent and the builds do
  not get it.
- The worker adds up the cost of each session of today in `events.jsonl`. At the budget, no new phase starts
  until midnight, in the time zone of the machine. A phase that runs continues to its end.
- The budget does not include a session that ends without a result. Thus also set a spend limit on the key in
  the Anthropic Console.
- The board shows "Cost today" in place of the usage windows.
- There is no live mode. An interactive `claude` gets "credit balance too low" with a key that works in print
  mode. When an issue needs you, the factory blocks it with a note, and you answer on GitHub.
```

5. In the section "The events file", add the event type `cost` with its fields `issue` and `cost_usd`.
6. In "Live mode", add one sentence: "API key mode has no live mode. See "Provider"."
7. In "Setup", where the README tells about the exit code 2, replace "a `claude` session uses an API key"
   with "a `claude` session does not use the credential of the provider".

Run: `./mvnw -q test -Dtest=DocumentExamplesTest`
Expected: PASS. This test checks the examples of the documents.

- [ ] **Step 3: Update constraints 1 to 3 of the design**

In `docs/superpowers/specs/2026-11-01-factory-design.md`, section 3, replace constraint 1 and constraint 2 with:

```markdown
1. **Unmodified CLI.** The factory runs the unmodified `claude` CLI. It never uses the Claude Agent SDK, or an
   ACP adapter for Claude. The provider is the subscription of the owner or an Anthropic API key. See
   `docs/superpowers/specs/2026-11-23-api-key-provider-design.md`.
2. **One credential.** The worker removes every `ANTHROPIC_*` variable from the environment of each `claude`
   process. In API key mode, each phase gets the key from an `apiKeyHelper` script. Each session must use the
   credential of the provider. If it does not, the worker stops the session and stops the factory (fail
   closed).
```

Keep constraint 3. Keep the reason about the Agent SDK from the earlier commit as the second paragraph of
constraint 1.

- [ ] **Step 4: Update the legal document**

In `docs/legal-considerations.md`, section 6, replace the item "Or use an Anthropic API key. The design ...
adds this provider." with: "Or use an Anthropic API key. Set `claude.provider: anthropic-api-key` in
`worker.yaml`. See the section "Provider" of the README."

- [ ] **Step 5: Run all the tests and commit**

```bash
./mvnw -q verify
git add README.md factory.example.yaml docs
git commit -m "Describe the API key provider in the documents

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

## Task 15: Check the whole change

- [ ] **Step 1: Run the full build**

Run: `./mvnw verify`
Expected: BUILD SUCCESS.

- [ ] **Step 2: Look for old names**

```bash
git grep -n -E 'ApiKeyDetected|verifyNoApiKey|foundAnApiKey|uses only the Claude subscription' -- src README.md
```

Expected: no output.

- [ ] **Step 3: Look for the key in the outputs**

```bash
git grep -n 'sk-ant-' -- src/main
```

Expected: no output. Only the tests contain the fake key `sk-ant-test-key`.

- [ ] **Step 4: Run the factory with each provider**

Run the worker on a test repository with `provider: subscription`, then with `provider: anthropic-api-key`
and a budget of `1`. For each run, check:

- The first line names the correct provider.
- One issue goes through one phase.
- In API key mode, `events.jsonl` has a `cost` event, and the board shows "Cost today".
- In API key mode, the phase gets `--settings`, and its `init` event gives `"apiKeyHelper"`.
- In API key mode, the button Enter blocks the issue with the note "The live mode is not available with the
  provider anthropic-api-key. Answer on GitHub."

Write the results in the pull request description.
