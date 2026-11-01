# Factory plan 3: web page — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The owner sees the state of the factory on one web page, also on the phone, and can give the four actions Enter, Approve, Block, and Continue from it, after a password login.

**Architecture:** The worker keeps an in-memory board (`BoardState`). The poll and each published record update it, so the page never asks GitHub for data. A JDK HTTP server (`WebServer`) renders the board as HTML on the server, pushes a change signal with Server-Sent Events, and runs each action as one `POST`. Enter, Approve, and Block add a label with the token of the worker. Continue writes the continue marker of plan 2. The login is behind the interface `PageLogin`, with one implementation `PasswordLogin`.

**Tech Stack:** Java 21, Maven, the JDK HTTP server `com.sun.net.httpserver` (the pom names no web library, so the plan adds no dependency), virtual threads, PBKDF2 from the JDK (`PBKDF2WithHmacSHA256`), plain HTML, CSS, and one small JavaScript file. No JavaScript library and no CDN.

**Spec:** `docs/superpowers/specs/2026-11-01-factory-design.md`, section 9 (web page), with section 5 (state in labels and the factory-record of the status comment), section 8 (live mode, the continue marker), and section 10 (configuration).

## Global Constraints

- All the rules of plans 1 and 2 stay: subscription only, fail closed, push only `factory/*`, Simplified Technical English, clean code, full names, verb method names, TDD (RED then GREEN).
- **The page makes no GitHub request of its own.** The account has a shared limit of 5,000 requests per hour, and the pilot hit it. The page shows only the state that the worker knows from its last poll (the in-memory `BoardState`) and the data from the agent runs (context bar, usage windows).
- **An action costs only the requests that it needs.** Enter, Approve, and Block: one request (add one label). Continue: no request (a local marker file).
- The classes of `com.meterian.factory.web` never get a `GitHubClient`. They get only `LabelAdder` (one method: add one label). A test checks this.
- The GitHub token never goes to the browser. The page shows no token, no clone path, and no environment variable.
- The worker serves the page. It listens on `127.0.0.1` by default. The configuration value `web.address` opens it to the LAN. The default port is 8090.
- Login (section 9.6): the configuration holds a PBKDF2 hash, never the password. The command `hash-password` makes the hash. A correct password gives a random session cookie with `HttpOnly` and `SameSite=Strict`, valid for 30 days. The page itself needs the login. Each action is a `POST`. After 5 wrong passwords, the worker refuses new attempts for 15 minutes.
- The login is in one class, `PasswordLogin`, behind the interface `PageLogin`. The server knows only `PageLogin`, so another method can replace it.
- The server escapes all text from GitHub and from the agents (issue titles, labels, reasons, notes, links) before it writes HTML. The page sends a `Content-Security-Policy` that allows only its own script and style.
- A button shows only when its action is possible. The server checks the same rule again at the `POST`.
- The page updates itself with Server-Sent Events.
- Plan 3 does not change `HttpGitHubClient` or the poll logic of `Worker`. The branch `fix/notes-and-api-budget` changes them and merges first. Plan 3 touches `Worker` only at the three points W1, W2, and W3 of Task 3.

## Review Focus

1. The page, its board fragment, and its event stream make zero GitHub requests, also after many polls. One action makes exactly one request. Test in Task 8.
2. An issue title `<script>alert(1)</script>`, a note `"><img src=x onerror=alert(1)>`, and a live link `javascript:alert(1)` show as text, never as markup or as a link. Test in Task 7.
3. The worker account adds a label for a page action: the actor check accepts it. The same label from the worker account with no page action, or after a restart of the worker, is ignored with the usual note. Test in Task 1.
4. A page that is not current: the owner clicks a button whose action is no longer possible (another device approved, the issue moved). The server answers 409 and adds no label. Test in Task 8.
5. Login abuse: after 5 wrong passwords even the correct password fails for 15 minutes; a forged or expired cookie gives 401; a `POST` with the `Origin` of another site gives 403. Tests in Tasks 6 and 8.

---

## File structure

```
src/main/java/com/meterian/factory/
  board/         BoardUpdates (interface), BoardState, BoardSnapshot, IssueCard
  usage/         ContextTracker (new)
  agent/         LiveContextReader (new), LiveSessions (+ requestContinue), LiveFiles (+ requestContinue), TmuxLiveSessions
  workflow/      PageRequests (new), RequestReader (+ worker-account rule), StatusPublisher (+ BoardUpdates),
                 PhaseRunner (+ ContextTracker), IssueWorkflow (constructor)
  worker/        Worker (W1, W2, W3 only)
  configuration/ WebSettings (new), FactoryConfiguration (+ web), ConfigurationLoader (+ web section)
  web/           WebServer, PageLogin, LabelAdder, Html, PageModel, PageModelSource, BoardPage, CardRenderer,
                 PageAction, PageActions, PageHandler, EventsHandler, ActionHandler, ResourceHandler, SecurityHeaders
  web/login/     PasswordLogin, PasswordHash, LoginResult
  Main           (+ hash-password, the wiring of the web page)
src/main/resources/web/   page.css, page.js
src/test/java/com/meterian/factory/testing/MutableClock.java (moved from UsageTrackerTest)
```

---

### Task 1: Requests from the page

**Files:**
- Create: `workflow/PageRequests.java`, `src/test/java/com/meterian/factory/testing/MutableClock.java`
- Modify: `workflow/RequestReader.java`, `agent/LiveSessions.java`, `agent/LiveFiles.java`, `agent/TmuxLiveSessions.java`, `testing/FakeLiveSessions.java` (`requestContinue` gets `@Override`), `usage/UsageTrackerTest.java` (uses the moved clock)
- Test: `workflow/RequestReaderTest.java`, `agent/LiveFilesTest.java`, `worker/WorkerTest.java`

**Interfaces:**
- Produces:
  - `public final class PageRequests { PageRequests(Clock clock); void remember(int issueNumber, String label); boolean isRemembered(int issueNumber, String label) }` — thread safe; an entry is valid for 24 hours (`REQUEST_LIFETIME`).
  - `RequestReader(GitHubClient gitHub, List<String> allowedAccounts, String workerLogin, PageRequests pageRequests)`. The current two-argument constructor stays and keeps the current rule (no worker account, empty `PageRequests`).
  - `LiveSessions.requestContinue(int issueNumber)`; `LiveFiles.requestContinue(int issueNumber)` writes the date to `continueMarker(n)` atomically with the mode 0600; `TmuxLiveSessions` delegates to `LiveFiles`.
  - `public final class MutableClock extends Clock { MutableClock(Instant start); void set(Instant instant); void advance(Duration duration) }` (UTC).

The decision on the actor check: the current rule accepts a label only from an account of `accounts.allowed`. With a separate worker account (README, "Setup"), the actor of a page label is the worker account, so the current rule ignores it. The new rule in `isGivenByAllowedAccount`: the actor is allowed, **or** the actor is the worker login (case ignored) **and** `pageRequests.isRemembered(issueNumber, label)`. The page remembers the request before it adds the label. `PageRequests` is in memory only: after a restart, a page label that the poll did not read yet gets the note "is ignored", and the owner clicks again (fail closed). `OwnerApproval`, `EnterRequests`, and the block check use `RequestReader`, so all three get the rule.

- [ ] **Step 1: Write the failing tests**

```java
// RequestReaderTest (reader with workerLogin "factory-bot", allowed [bbossola], a MutableClock)
@Test void acceptsALabelOfTheWorkerAccountForAPageRequest()      // remember(7, "factory:approve"); addLabelBy(7, "factory:approve", "factory-bot") → true
@Test void ignoresALabelOfTheWorkerAccountWithoutAPageRequest()  // no remember → false
@Test void aPageRequestIsForOneIssueAndOneLabel()                // remember(7, approve) → (8, approve) false, (7, factory:block) false
@Test void aPageRequestEndsAfter24Hours()                        // clock.advance(24 h + 1 s) → false
@Test void theWorkerLoginIgnoresCase()                           // actor "Factory-Bot" → true
// LiveFilesTest
@Test void requestContinueWritesTheMarkerOfTheHook()             // requestContinue(7) → isContinueRequested(7), file mode rw-------
// WorkerTest
@Test void anApproveFromThePageMergesTheIssue()                  // issue waits MERGE_NEEDS_APPROVAL; remember + addLabelBy(n, approve, WORKER_LOGIN) → next poll squash-merges
@Test void aPageLabelAfterARestartIsIgnoredWithANote()           // addLabelBy(n, approve, WORKER_LOGIN) with an empty PageRequests → no merge, note "from factory-bot is ignored"
```

- [ ] **Step 2: Run them and see them fail** — `./mvnw -q test -Dtest='RequestReaderTest,LiveFilesTest,WorkerTest'`. Expected: compilation errors.
- [ ] **Step 3: Implement** `PageRequests`, the rule, `requestContinue`, and move `MutableClock`.
- [ ] **Step 4: Run them and see them pass.**
- [ ] **Step 5: Commit** — "Accept the labels that the worker adds for the page".

---

### Task 2: The board state and the context of the agent

**Files:**
- Create: `board/BoardUpdates.java`, `board/BoardState.java`, `board/BoardSnapshot.java`, `board/IssueCard.java`, `usage/ContextTracker.java`
- Test: `board/BoardStateTest.java`, `usage/ContextTrackerTest.java`

**Interfaces:**
- Produces:
  - `public interface BoardUpdates { void recordPoll(List<Issue> labeledIssues); void recordIssue(Issue issue, IssueRecord record); BoardUpdates IGNORED = ... }` — `IGNORED` does nothing (for tests that do not look at the board).
  - `public record IssueCard(int issueNumber, String title, Set<String> labels, IssueRecord record, Optional<Instant> doneAt)`.
  - `public record BoardSnapshot(Instant workerStartedAt, Optional<Instant> lastCheckAt, List<IssueCard> cards, long version)`.
  - `public final class BoardState implements BoardUpdates { BoardState(Clock clock); void recordLabelAdded(int issueNumber, String label); BoardSnapshot snapshot(); long awaitChange(long knownVersion, Duration timeout) throws InterruptedException }`.
  - `public final class ContextTracker { void record(int issueNumber, ContextUsage usage); Optional<ContextUsage> find(int issueNumber) }` — thread safe.

`BoardState` rules (all methods `synchronized`; each change adds 1 to `version` and calls `notifyAll`):
- `recordPoll`: sets `lastCheckAt` to the clock time. It sets the title and the labels of each card from the list. A card whose issue is not in the list goes away, except a card with the status DONE (the finish step removes the label `factory`).
- `recordIssue`: makes or replaces the card of the issue. The first record with the status DONE sets `doneAt` to the clock time. A later record does not move it.
- At most 20 DONE cards stay (`MAXIMUM_DONE_CARDS`), the newest `doneAt` first. The decision: the merge date is the time when the worker saw DONE. The DONE cards are lost at a restart, because a list of closed issues costs requests.
- `recordLabelAdded`: adds the label to the card at once, so the button of the action goes away before the next poll.
- `awaitChange`: returns at once when `version` differs from `knownVersion`; otherwise waits until a change or the timeout; returns the current version.

- [ ] **Step 1: Write the failing tests**

```java
@Test void aPollSetsTheCheckTimeAndTheTitles()
@Test void aRecordMakesACard()
@Test void anIssueThatLeavesTheListGoesAwayUnlessItIsDone()
@Test void theDoneTimeIsTheFirstTimeTheWorkerSawDone()        // two DONE records at t1 and t2 → doneAt == t1
@Test void onlyTheNewest20DoneCardsStay()
@Test void aLabelFromThePageShowsAtOnce()                      // recordLabelAdded(7, "factory:approve") → card labels contain it
@Test void awaitChangeReturnsAtOnceForAnOldVersion()
@Test void awaitChangeWakesUpOnAChange()                       // another thread records after 100 ms → returns in less than 2 s
@Test void awaitChangeEndsAfterTheTimeout()                    // no change, 200 ms → returns the same version
// ContextTrackerTest
@Test void keepsTheNewestContextOfEachIssue()
```

- [ ] **Step 2: Run them and see them fail.**
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run them and see them pass.**
- [ ] **Step 5: Commit** — "Keep the state of the board in memory".

---

### Task 3: The worker updates the board

**Files:**
- Modify: `worker/Worker.java` (W1, W2, W3 only), `workflow/StatusPublisher.java`, `workflow/PhaseRunner.java`, `workflow/IssueWorkflow.java` (constructor only)
- Modify (tests): `testing/FakeAgentRunner.java` (+ `withContextUsage(long tokensInUse, long contextWindow)`: the last scripted phase reports this context, in the style of `thatFails`), `worker/WorkerTest.java` and `workflow/IssueWorkflowTest.java` (the helpers pass the new arguments)
- Test: `worker/WorkerTest.java`, `workflow/PhaseRunnerTest.java` (new)

**Interfaces:**
- Consumes: `BoardUpdates`, `ContextTracker` (Task 2).
- Produces:
  - `StatusPublisher(GitHubClient gitHub, String workerLogin, int fixLoopLimit, BoardUpdates board)` — after each write, `board.recordIssue(issue, published)`.
  - `IssueWorkflow` constructor: two new last parameters `BoardUpdates board, ContextTracker contexts`.
  - `PhaseRunner(AgentRunner agentRunner, UsageTracker usage, ContextTracker contexts)` — `runPhase` (the agent in charge) records `report.contextUsage()` for `request.issueNumber()`. `runInNewSession` (reviews, risk) records only the usage windows: the context bar shows the agent in charge.
  - `Worker` constructor: a new last parameter `BoardUpdates board`.

The three touch points in `Worker`, so that a rebase on `fix/notes-and-api-budget` is easy:
- **W1** (constructor): store `board`, and pass it to `new StatusPublisher(...)`.
- **W2** (`pollOnce`): keep the result of `readLabeledIssues()` in a local variable, call `board.recordPoll(labeledIssues)`, then loop on it. A failed issue list never reaches W2, so the page keeps the old check time.
- **W3** (`findStartableRecord`): after the line `current = liveRecordCheck.forgetEndedSession(issue, current);`, call `board.recordIssue(issue, current)`.

The board gets only the data of the requests that the worker makes already. After the rebase, `GitHubRequestBudgetTest` of the other branch must give the same numbers.

- [ ] **Step 1: Write the failing tests**

```java
// WorkerTest (BoardState with a MutableClock)
@Test void thePollPutsTheIssuesOnTheBoard()            // addLabeledIssue(1); pollOnce → card 1, title "Change the greeting 1", lastCheckAt present
@Test void aStepUpdatesTheCardBetweenPolls()           // after waitForTheLoops: card 1 record == StatusComment.findRecord(gitHub.commentsOf(1), WORKER_LOGIN)
@Test void aFailedPollKeepsTheLastCheckTime()          // poll, clock.advance(1 min), failTheNextIssueList, poll → lastCheckAt unchanged
@Test void anIssueFromAnotherAccountMakesNoCard()      // label factory by "stranger" → no card
// PhaseRunnerTest
@Test void theAgentInChargeReportsItsContext()         // thenChangeNothing().withContextUsage(26262, 1_000_000); runPhase(issue 7) → find(7) == ContextUsage(26262, 1000000)
@Test void aReviewSessionDoesNotChangeTheContext()     // runInNewSession with another context → find(7) unchanged
```

- [ ] **Step 2: Run them and see them fail.**
- [ ] **Step 3: Implement** W1, W2, W3, the publisher, and the phase runner. Do not change other code of `Worker`.
- [ ] **Step 4: Run them and see them pass**, then the full `./mvnw -q test`.
- [ ] **Step 5: Commit** — "Update the board at each poll and at each published record".

---

### Task 4: The context of a live session

**Files:**
- Create: `agent/LiveContextReader.java`
- Test: `agent/LiveContextReaderTest.java`

**Interfaces:**
- Produces: `public final class LiveContextReader { LiveContextReader(Path claudeHome); Optional<Long> readTokensInUse(UUID sessionId, Path worktree) }`.

Section 9.2 says that in live mode the worker reads the counts from the local session file. Verified on this machine: Claude Code writes `<claude-home>/projects/<folder>/<session-uuid>.jsonl`. The folder is the absolute worktree path with each character that is not `[A-Za-z0-9]` changed to `-` (for example `/home/bbossola/.claude-ai-factory/work/issue-296` gives `-home-bbossola--claude-ai-factory-work-issue-296`). The claude home is `$CLAUDE_CONFIG_DIR` when it is set, else `~/.claude` (Main gives it).
- The reader reads only the last 256 KiB of the file. It takes the newest line that parses as JSON with `"type":"assistant"` and `message.usage`. The result is `input_tokens + cache_read_input_tokens + cache_creation_input_tokens`.
- A missing or unreadable file gives `Optional.empty()`. The reader never writes and never calls a network.
- The page divides the result by the context window of the newest auto run (`ContextTracker`). Without a known window, the card shows no bar.

- [ ] **Step 1: Write the failing tests**

```java
@Test void readsTheSumOfTheLastAssistantTurn()        // two assistant lines; the last has 2, 34697, 938 → 35637
@Test void findsTheFolderOfTheWorktree()              // worktree /home/u/.claude-ai-factory/work/issue-7 → reads <home>/projects/-home-u--claude-ai-factory-work-issue-7/<uuid>.jsonl
@Test void skipsABrokenLastLine()                     // a cut last line → the turn before it
@Test void readsTheEndOfALargeFile()                  // 1 MiB of user lines, then one assistant line → found
@Test void aMissingFileGivesNothing()
```

- [ ] **Step 2: Run them and see them fail.**
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run them and see them pass.**
- [ ] **Step 5: Commit** — "Read the context of a live session from its local file".

---

### Task 5: The web configuration and the hash command

**Files:**
- Create: `configuration/WebSettings.java`, `web/login/PasswordHash.java`
- Modify: `configuration/FactoryConfiguration.java`, `configuration/ConfigurationLoader.java`, `Main.java` (the command `hash-password`), `factory.example.yaml`, the three tests that call `new FactoryConfiguration(...)` (add `Optional.empty()`)
- Test: `web/login/PasswordHashTest.java`, `configuration/ConfigurationLoaderTest.java`, `MainTest.java`

**Interfaces:**
- Produces:
  - `public record WebSettings(String address, int port, PasswordHash passwordHash)`; `FactoryConfiguration` gets the new last component `Optional<WebSettings> web`.
  - `public final class PasswordHash { static PasswordHash create(char[] password); static PasswordHash create(char[] password, int iterations); static PasswordHash parse(String text); boolean matches(char[] password); int iterations(); String format() }`.
  - `Main.hashPassword(PasswordSource passwords, PrintStream output, PrintStream errors)` returns an exit code; `interface PasswordSource { Optional<char[]> read(String prompt) }`. `main` uses `System.console().readPassword`.

Exact values:
- The hash: `PBKDF2WithHmacSHA256`, 600,000 iterations (`DEFAULT_ITERATIONS`), a 16-byte salt from `SecureRandom`, a 256-bit key. The text: `pbkdf2-sha256:<iterations>:<salt base64>:<key base64>`. `matches` compares with `MessageDigest.isEqual` and clears the password array. `parse` throws `IllegalArgumentException` for another format.
- The YAML section, optional:
  ```yaml
  web:
    address: 127.0.0.1      # optional; 0.0.0.0 opens the page to the LAN
    port: 8090              # optional
    password-hash: "pbkdf2-sha256:600000:..."   # required in the section
  ```
- No `web` section: the worker serves no page. A `web` section without `password-hash`: "Configuration key web.password-hash is missing." A hash in another format: "web.password-hash is not valid. Make a new one with hash-password." Less than 100,000 iterations: "web.password-hash is too weak. Make a new one with hash-password." A port outside 1 to 65535: "web.port must be from 1 to 65535".
- `hash-password`: asks "Password: " and "Repeat the password: ". Different values: "The two passwords are not the same." (exit 1). Less than 12 characters: "The password must have 12 characters or more." (exit 1). No console: "Run hash-password in a terminal." (exit 1). Success: one line `password-hash: <hash>` on the output (exit 0).
- The usage text becomes two lines: `Usage: java -jar claude-ai-factory.jar run --config <file>` and `       java -jar claude-ai-factory.jar hash-password`.

- [ ] **Step 1: Write the failing tests**

```java
// PasswordHashTest (create with 1,000 iterations, so that the tests are fast)
@Test void aHashMatchesItsPasswordOnly()
@Test void theTextHasTheFormatAndTheDefaultIterations()   // create(password).format() starts with "pbkdf2-sha256:600000:"
@Test void twoHashesOfOnePasswordDiffer()                 // random salt
@Test void parseReadsBackTheSameHash()
@Test void parseRefusesAnotherFormat()
// ConfigurationLoaderTest
@Test void theWebSectionIsOptional()
@Test void theWebPageListensOnTheLoopbackAddressByDefault()   // 127.0.0.1, 8090
@Test void aWebSectionNeedsAPasswordHash()
@Test void aWeakHashIsRefused()
@Test void aPortOutsideTheRangeIsRefused()
// MainTest
@Test void hashPasswordWritesTheHashLine()
@Test void hashPasswordRefusesTwoDifferentPasswords()
@Test void hashPasswordRefusesAShortPassword()
```

- [ ] **Step 2: Run them and see them fail.**
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run them and see them pass.**
- [ ] **Step 5: Commit** — "Add the web configuration and the hash-password command".

---

### Task 6: The password login

**Files:**
- Create: `web/PageLogin.java`, `web/Html.java`, `web/login/PasswordLogin.java`, `web/login/LoginResult.java`
- Test: `web/login/PasswordLoginTest.java`, `web/HtmlTest.java`

**Interfaces:**
- Consumes: `PasswordHash` (Task 5).
- Produces:
  - `public final class Html { static String escape(String text) }` — changes `& < > " '` to `&amp; &lt; &gt; &quot; &#39;`; `null` gives "". The login form and all the pages use it.
  - `public interface PageLogin { boolean isLoggedIn(Headers requestHeaders); void serveLogin(HttpExchange exchange) throws IOException }` — `serveLogin` answers `GET /login` (the form) and `POST /login` (the check).
  - `public final class PasswordLogin implements PageLogin { PasswordLogin(PasswordHash hash, Clock clock, SecureRandom random); LoginResult logIn(char[] password); boolean isValidSession(String token); String sessionCookie(String token) }`.
  - `public record LoginResult(Outcome outcome, Optional<String> sessionToken, Optional<Instant> retryAfter)` with `enum Outcome { ACCEPTED, WRONG_PASSWORD, LOCKED }`.

Exact values:
- The cookie: `factory_session=<token>; Max-Age=2592000; Path=/; HttpOnly; SameSite=Strict`. No `Secure`, because the traffic is plain HTTP (the known limit of section 9.6).
- The token: 32 bytes from `SecureRandom`, base64url without padding (43 characters). The sessions are in memory: a restart of the worker asks for the password again. At most 20 sessions (`MAXIMUM_SESSIONS`); a new session removes the oldest one.
- The lock: 5 wrong passwords in a row set `lockedUntil` to now plus 15 minutes. During the lock, `logIn` returns LOCKED without a check of the password, also for the correct password. A correct password sets the count back to 0. The lock is for the worker, not for one device, as the spec says.
- `POST /login`: the body is `application/x-www-form-urlencoded` with the field `password`, at most 4 KiB. ACCEPTED: `Set-Cookie` and 303 to `/`. WRONG_PASSWORD: 401 and the form with "The password is not correct." LOCKED: 429 and "Too many wrong passwords. Try again after <HH:mm>."

- [ ] **Step 1: Write the failing tests**

```java
@Test void escapesTheFiveSpecialCharacters()             // HtmlTest
@Test void aCorrectPasswordGivesASessionFor30Days()        // valid at +30 d − 1 s, not valid at +30 d + 1 s
@Test void aWrongPasswordGivesNoSession()
@Test void fiveWrongPasswordsLockNewAttemptsFor15Minutes() // 5 wrong; the correct one → LOCKED, retryAfter == t + 15 min; at t + 15 min → ACCEPTED
@Test void aCorrectPasswordResetsTheCountOfWrongPasswords() // 4 wrong, correct, 4 wrong → not LOCKED
@Test void theCookieIsHttpOnlyAndStrict()                  // sessionCookie(token) equals the exact text above
@Test void isLoggedInFindsTheCookieAmongOthers()           // "Cookie: a=b; factory_session=<token>" → true; another token → false
@Test void eachSessionTokenIsNew()                         // two logins → two tokens of 43 characters
@Test void theOldestSessionGoesAfter20Sessions()
```

- [ ] **Step 2: Run them and see them fail.**
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run them and see them pass.**
- [ ] **Step 5: Commit** — "Add the password login of the web page".

---

### Task 7: The page model and the HTML

**Files:**
- Create: `web/PageModel.java`, `web/PageModelSource.java`, `web/PageAction.java`, `web/BoardPage.java`, `web/CardRenderer.java`, `src/main/resources/web/page.css`, `src/main/resources/web/page.js`
- Test: `web/PageActionTest.java`, `web/BoardPageTest.java`

**Interfaces:**
- Consumes: `Html` (Task 6), `BoardState`, `BoardSnapshot`, `IssueCard` (Task 2), `ContextTracker` (Task 2), `LiveContextReader` (Task 4), `LiveSessions.isContinueRequested`, `UsageTracker.windows()` and `resumesAt()`, `ApprovalCheck.waitsForApproval`.
- Produces:
  - `public record PageModel(BoardSnapshot board, Instant renderedAt, int issuesInWork, int parallelLimit, IsolationMode isolationMode, Duration pollInterval, List<UsageWindow> usageWindows, Optional<Instant> usageResumesAt, Map<Integer, ContextUsage> contexts, Set<Integer> continueRequested, String repositoryFullName)`.
  - `public final class PageModelSource { PageModelSource(BoardState board, UsageTracker usage, ContextTracker contexts, LiveContextReader liveContexts, LiveSessions liveSessions, WorkspaceLayout layout, FactoryConfiguration configuration, IntSupplier issuesInWork, Clock clock); PageModel read() }` — only memory and local files; for a live card the context is `LiveContextReader` tokens over the window of `ContextTracker`.
  - `public enum PageAction { ENTER("enter", "Enter", "factory:enter"), APPROVE("approve", "Approve", "factory:approve"), BLOCK("block", "Block", "factory:block"), CONTINUE("continue", "Continue", null); Optional<String> label(); String pathName(); static Optional<PageAction> findByPathName(String name); boolean isPossible(IssueCard card, boolean continueRequested) }`.
  - `public final class BoardPage { BoardPage(ZoneId zone); String renderPage(PageModel model); String renderBoard(PageModel model) }` — `renderPage` is the full document with `<div id="board">` around `renderBoard`.
  - `final class CardRenderer { String render(IssueCard card, PageModel model) }`.

The rules of `isPossible` (a label that is already on the card hides its button):
- ENTER: `record.agentSessionStarted()`, the phase is not DONE, and no live state with a link (a live state without a link can enter again, as plan 2).
- APPROVE: `ApprovalCheck.waitsForApproval(record)`.
- BLOCK: the status is not BLOCKED or DONE, and the phase is not DONE.
- CONTINUE: `record.live().isPresent()` and `!continueRequested`.

The content (section 9.1):
- Header: "Worker runs since <time>", "Last GitHub check: <time>", "Issues in work: 2 of 2", "Isolation: same-user". When the last check is older than 3 poll intervals: "The last GitHub check is old." with the class `warning`. Each usage window: "<name>: 37% at <time>". When `usageResumesAt` is present: "New phases start again at <time>." No dollar cost.
- Columns, in this order: Waits for you, In progress, In review, Queued, Blocked, Done. The decision: "Waits for you" is first, so that it is at the top on the phone. Cards by issue number; Done by `doneAt`, newest first.
- Card: "#12 <title>" as a link to `https://github.com/<repository>/issues/12`, the phase, the four reviews (correctness, security, architecture, quality: positive, negative, or —), "Fix loops: 1 of 2", "Risk: low", the context as `<meter min="0" max="100" value="62">` with the text "Context 62%" (no inline style: the policy forbids it), and a link to the pull request. A "Waits for you" card has the class `waits`, the reason sentence in one line, and the Remote Control link. Each card shows its newest note in one line. A Done card shows "Done on <date>".
- The Remote Control link is a link only when it matches `https://claude\.ai/code/session_[A-Za-z0-9]+` exactly (the pattern of `TmuxLiveSessions`). Another value shows as escaped text.
- Each possible action is `<form method="post" action="/issues/12/approve"><button>Approve</button></form>`. The forms work without JavaScript.
- Times: `yyyy-MM-dd HH:mm` in the zone of the worker.
- `page.js`: opens `new EventSource("/events")`; on the event `board`, it fetches `/board` and replaces the content of `#board`; a 401 reloads the page (this shows the login). Before a Block it asks "Block issue #<n>? The running claude process stops." (`confirm`). `page.css`: one column on a narrow screen; a clear color for `.waits`.

- [ ] **Step 1: Write the failing tests**

```java
// PageActionTest — one test for each rule above, plus:
@Test void aLabelOnTheCardHidesItsButton()
@Test void continueHidesWhenTheMarkerExists()
// BoardPageTest
@Test void escapesTheTitleTheNoteAndTheReason()      // title "<script>alert(1)</script>", note "\"><img src=x onerror=alert(1)>" → no "<script>", no "<img"; contains "&lt;script&gt;"
@Test void aLinkThatIsNotRemoteControlIsText()       // live link "javascript:alert(1)" → no href with it; escaped text
@Test void aWaitingCardShowsTheReasonAndTheLink()
@Test void theWaitsForYouColumnIsFirst()
@Test void showsTheContextAsAPercent()               // ContextUsage(620000, 1000000) → value="62", "Context 62%"
@Test void showsOnlyThePossibleButtons()             // MERGE_NEEDS_APPROVAL → Approve, Enter, Block; no Continue
@Test void warnsWhenTheLastCheckIsOld()
@Test void showsEachUsageWindowWithItsTime()
```

- [ ] **Step 2: Run them and see them fail.**
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run them and see them pass.**
- [ ] **Step 5: Commit** — "Render the board of the factory as HTML".

---

### Task 8: The web server, the actions, and the events

**Files:**
- Create: `web/WebServer.java`, `web/LabelAdder.java`, `web/PageActions.java`, `web/PageHandler.java`, `web/EventsHandler.java`, `web/ActionHandler.java`, `web/ResourceHandler.java`, `web/SecurityHeaders.java`
- Test: `web/WebServerTest.java`, `web/WebPackageTest.java`

**Interfaces:**
- Consumes: `PageLogin` (Task 6), `PageModelSource`, `BoardPage`, `PageAction` (Task 7), `BoardState` (Task 2), `PageRequests`, `LiveSessions.requestContinue` (Task 1).
- Produces:
  - `@FunctionalInterface public interface LabelAdder { void addLabel(int issueNumber, String label) }` — Main gives `gitHub::addLabel`.
  - `public final class PageActions { PageActions(LabelAdder labels, PageRequests pageRequests, LiveSessions liveSessions, BoardState board); ActionOutcome perform(int issueNumber, PageAction action) }` with `enum ActionOutcome { DONE, NOT_POSSIBLE, UNKNOWN_ISSUE }`. It reads the card from `board.snapshot()` and checks `isPossible` again. A label action calls `pageRequests.remember`, then `labels.addLabel`, then `board.recordLabelAdded`. CONTINUE calls `liveSessions.requestContinue`. The worker acts on a label at its next poll, so a Block stops the `claude` process after at most one poll interval (default 60 seconds).
  - `public final class WebServer { WebServer(WebSettings settings, PageLogin login, PageModelSource models, BoardPage page, BoardState board, PageActions actions); void start() throws IOException; int port(); void stop() }` — `com.sun.net.httpserver.HttpServer` with `Executors.newVirtualThreadPerTaskExecutor()`, so an open event stream holds no platform thread. Port 0 in the tests.

Routes (each answer has the headers of `SecurityHeaders`):

| Route | Without login | With login |
|---|---|---|
| `GET /login`, `POST /login` | `login.serveLogin` | the same |
| `GET /page.css`, `GET /page.js` | the resource | the resource |
| `GET /` | 303 to `/login` | `renderPage` |
| `GET /board` | 401 | `renderBoard` |
| `GET /events` | 401 | the event stream |
| `POST /issues/<n>/<action>` | 401 | DONE: 303 to `/`; NOT_POSSIBLE: 409 "This action is not possible now."; UNKNOWN_ISSUE or an unknown action: 404; a `GitHubException`: 502 "GitHub refused the label." with the escaped message |

- Another path: 404. Another method: 405.
- A `POST` with an `Origin` header that is not `http://<Host header>`: 403, before the login check. This adds a check to `SameSite=Strict`.
- `SecurityHeaders`: `Content-Security-Policy: default-src 'none'; script-src 'self'; style-src 'self'; connect-src 'self'; form-action 'self'; frame-ancestors 'none'; base-uri 'none'`, `X-Content-Type-Options: nosniff`, `Referrer-Policy: no-referrer`, and `Cache-Control: no-store` on the HTML and the events.
- The event stream: `Content-Type: text/event-stream`. The loop calls `board.awaitChange(version, 25 s)`. A new version writes `event: board` and `data: <version>` and an empty line. A timeout writes the comment line `: keep-alive`. A write failure (the browser left) ends the loop. `stop()` stops the server and the executor, so the loops end.

- [ ] **Step 1: Write the failing tests** (a `BoardState` with cards, a recording `LabelAdder`, `FakeLiveSessions`, a `PasswordLogin` with a hash of 1,000 iterations, `java.net.http.HttpClient` without a cookie handler)

```java
@Test void thePageNeedsTheLogin()                  // GET / → 303 /login; GET /board → 401; GET /events → 401
@Test void aForgedCookieGivesNoAccess()            // Cookie factory_session=forged → GET /board 401
@Test void theLoginOpensThePage()                  // POST /login → Set-Cookie; GET / with it → 200 and "#12"
@Test void thePageMakesNoGitHubRequest()           // GET / ×3, /board ×3, /events for one change, 3 board.recordPoll → the LabelAdder has 0 calls
@Test void approveAddsExactlyOneLabel()            // POST /issues/12/approve → 303; LabelAdder calls == [(12, "factory:approve")]; pageRequests.isRemembered(12, approve)
@Test void continueWritesTheMarkerAndNoLabel()     // live card; POST /issues/12/continue → liveSessions.isContinueRequested(12); LabelAdder 0 calls
@Test void anActionThatIsNoLongerPossibleGives409() // card without the reason MERGE_NEEDS_APPROVAL; POST approve → 409; 0 calls
@Test void aSecondClickGives409()                  // approve twice → the second is 409 (the label is on the card)
@Test void aPostFromAnotherSiteGives403()          // Origin: http://evil.example → 403; 0 calls
@Test void theEventStreamSignalsAChange()          // open /events; board.recordPoll → read "event: board" in 5 s
@Test void everyAnswerHasTheSecurityHeaders()      // CSP with frame-ancestors 'none'; nosniff
// WebPackageTest
@Test void noWebClassUsesTheGitHubClient()         // no file under src/main/java/com/meterian/factory/web contains "GitHubClient"
```

- [ ] **Step 2: Run them and see them fail.**
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run them and see them pass** (`./mvnw -q test -Dtest='WebServerTest,WebPackageTest'`).
- [ ] **Step 5: Commit** — "Serve the web page with the actions and the events".

---

### Task 9: Main, the wiring, and the documents

**Files:**
- Modify: `Main.java`, `README.md`, `docs/superpowers/specs/2026-11-01-factory-design.md` (sections 9 and 10), `factory.example.yaml`
- Test: `MainTest.java`

- [ ] **Step 1: Write the failing tests** — `MainTest`: `describesTheWebAddress` (`Main.describeWebAddress(settings)` gives `http://127.0.0.1:8090/`), and `warnsWhenThePageIsOpenToTheLan` (`Main.findWebWarning(settings)` for `0.0.0.0` gives "The web page is open to the LAN with plain HTTP. Another device on the LAN can read the password and the cookie."; empty for a loopback address).
- [ ] **Step 2: Run them and see them fail.**
- [ ] **Step 3: Implement:**
  - `runWorker` makes one `BoardState`, one `ContextTracker`, and one `PageRequests` (all with `Clock.systemUTC()`), and `RequestReader(gitHub, allowed, workerLogin, pageRequests)`. It gives them to the workflow, the worker, and the page.
  - When `configuration.web()` is present, it makes `PasswordLogin`, `PageModelSource` (with `worker.issuesInWork().size()` and `LiveContextReader` on the claude home), `PageActions` (with `gitHub::addLabel`), and `WebServer`. It starts the server before `runUntilStopped`. It writes the address and the warning to `errors`. A port in use gives `StartupException` "The web page cannot listen on <address>:<port>: <message>".
  - The shutdown hook and the end of `runWorker` call `webServer.stop()`.
  - README: a section "Web page": how to make the hash (`java -jar ... hash-password`), the `web` section, the address, the login and its limits (plain HTTP on the LAN, a restart asks for the password again, the lock), the columns, the four buttons and their labels, and that the page makes no GitHub request.
  - Spec section 9: the board in memory and no GitHub request of the page, the rule for a label of the worker account (Task 1), the Done cards in memory (Task 2), the column order (Task 7), the context in live mode (Task 4). Section 10: the `web` keys.
- [ ] **Step 4: `./mvnw verify`.** Expected: BUILD SUCCESS, 0 failures.
- [ ] **Step 5: Commit** — "Wire the web page in the command line and describe it".

---

### Task 10: Pilot of the web page (the owner decides)

This task changes GitHub. Do each step only after the owner says yes.

- [ ] **Step 1:** Make the hash with `hash-password`, add the `web` section, and start the worker with the merge target `factory-pilot`.
- [ ] **Step 2:** The owner opens the page on the computer and on the phone (with `web.address` for the LAN), logs in, and gives Enter, Continue, Approve, and Block on test issues.
- [ ] **Step 3:** Compare the GitHub requests of one hour with the page open and without it (the counts of `fix/notes-and-api-budget`). They must be the same, plus one request for each label action.
- [ ] **Step 4:** Report each step and each defect.
