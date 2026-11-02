# Enter in three places: implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The owner chooses where the live conversation opens: Terminal, Browser, or Phone. After the open, the owner can open the same conversation again in any of the three places.

**Architecture:** The place travels from the page to the worker with the page request of `factory:enter`, and it goes into the live state of the record. `LiveMode` uses it: the mention only for Phone, a terminal window for Terminal. The page gets new actions, a wait page for Browser, and a new card layout. A rule on the remote address keeps Terminal for the machine of the worker.

**Tech Stack:** Java 21, Maven, JUnit 5, AssertJ, `com.sun.net.httpserver`, Jackson, plain JavaScript and CSS.

**Spec:** `docs/superpowers/specs/2026-11-01-factory-design.md`, section 8.4 (and sections 9.4 and 10). The mock of 2026-11-02 (version 5) shows the page.

## Global Constraints

- Build: `export JAVA_HOME=/home/bbossola/.sdkman/candidates/java/21.0.2-open; ./mvnw verify`.
- Tests never reach GitHub, a real token, or the real `claude`, and never use the default tmux server.
- The CSP does not change: `default-src 'none'; script-src 'self'; style-src 'self'; connect-src 'self'; form-action 'self'; frame-ancestors 'none'; base-uri 'none'`. No inline script, no inline style, no `on…` attribute.
- All text from GitHub, from the record, and from the agents goes through `Html.escape`.
- A link becomes a link only when `TmuxLiveSessions.isRemoteControlLink` accepts it.
- The terminal command comes only from the configuration, never from a request.
- Clean code: full names, one responsibility for each class and method, one-line comments only for complex code. Comments, docs, and UI text in Simplified Technical English.
- Commit messages end with the attribution lines of the session.

## Review Focus

1. A request from another device sends `POST /issues/N/enter-terminal` or `/open-terminal` by hand: the server answers 409 "This action is not possible now." and runs no command. (Task 5)
2. The card has a live link that does not match the Remote Control pattern: the wait page never goes to it, and `/live/N/link` answers 204. (Task 6)
3. A record that a worker before this change wrote has no `place`: it reads as PHONE. (Task 1)
4. `gnome-terminal` is not installed, or there is no display: the session stays open, the record gets a note, and the Remote Control link still works. (Task 4)
5. An Enter from the GitHub app, with no page request: the place is PHONE, and the comment has the mention. (Task 4)

---

### Task 1: The place in the record

**Files:**
- Create: `src/main/java/com/meterian/factory/state/LivePlace.java`
- Modify: `src/main/java/com/meterian/factory/state/LiveSession.java`
- Test: `src/test/java/com/meterian/factory/state/StatusCommentTest.java` (or the existing record JSON test; find it with `grep -rln "factory-record" src/test`)

**Interfaces:**
- Produces: `enum LivePlace { TERMINAL, BROWSER, PHONE }`.
- Produces: `LiveSession` gets the last component `LivePlace place`. The compact constructor turns `null` into `PHONE`. New method `LiveSession withPlace(LivePlace newPlace)`. `withLink` and `withoutLink` keep the place.

- [ ] **Step 1: Write the failing tests**
  - `aLiveSessionWithoutAPlaceIsPhone`: a record JSON with `"live":{"startHeadCommit":"abc","statusBefore":"WAITS_FOR_YOU","waitBefore":"MERGE_NEEDS_APPROVAL","link":"https://claude.ai/code/session_x"}` reads back with `live().get().place() == LivePlace.PHONE`.
  - `thePlaceOfTheLiveSessionSurvivesTheRecordComment`: a record with `withPlace(LivePlace.TERMINAL)` written to the status comment and read back keeps `TERMINAL`.
  - `theLinkKeepsThePlace`: `new LiveSession("abc", IN_PROGRESS, Optional.empty(), Optional.empty(), LivePlace.BROWSER).withLink("l").place() == BROWSER`.
- [ ] **Step 2: Run them and see them fail** (compile error: no `LivePlace`).
- [ ] **Step 3: Write `LivePlace` and the new component.** Update every `new LiveSession(` call (about 15, in main and test code) with `LivePlace.PHONE` as the last argument.
- [ ] **Step 4: Run `./mvnw -q test`.** Expected: all pass.
- [ ] **Step 5: Commit** "Keep the place of the live conversation in the record".

### Task 2: The terminal command in the configuration

**Files:**
- Modify: `src/main/java/com/meterian/factory/configuration/FactoryConfiguration.java`, `ConfigurationLoader.java`
- Modify: the 7 test files that call `new FactoryConfiguration(` (find them with `grep -rln "new FactoryConfiguration(" src/test`)
- Test: `src/test/java/com/meterian/factory/configuration/ConfigurationLoaderTest.java`

**Interfaces:**
- Produces: `FactoryConfiguration` gets the last component `List<String> terminalCommand`. `public static final List<String> DEFAULT_TERMINAL_COMMAND = List.of("gnome-terminal", "--");` in `FactoryConfiguration`.

- [ ] **Step 1: Write the failing tests**
  - `theDefaultTerminalCommandIsGnomeTerminal`: a file with no `live` section gives `List.of("gnome-terminal", "--")`.
  - `readsTheTerminalCommand`: `live: { terminal-command: [xterm, -e] }` gives `List.of("xterm", "-e")`.
  - `anEmptyTerminalCommandStopsTheStart`: `live: { terminal-command: [] }` throws `ConfigurationException` with the message `"live.terminal-command must have at least one value."`.
- [ ] **Step 2: Run them and see them fail.**
- [ ] **Step 3: Read the section** with `root.findSection("live")` and `findTextList("terminal-command")`. Pass `FactoryConfiguration.DEFAULT_TERMINAL_COMMAND` in the test constructors.
- [ ] **Step 4: Run `./mvnw -q test`.** Expected: all pass.
- [ ] **Step 5: Commit** "Read the terminal command of live mode from the configuration".

### Task 3: Open a terminal window on the session

**Files:**
- Create: `src/main/java/com/meterian/factory/agent/TerminalWindow.java`
- Modify: `src/main/java/com/meterian/factory/agent/LiveSessions.java`, `TmuxLiveSessions.java`, `src/main/java/com/meterian/factory/Main.java`
- Modify: `src/test/java/com/meterian/factory/testing/FakeLiveSessions.java`, the inner fake in `LiveModeTest`
- Test: `src/test/java/com/meterian/factory/agent/TerminalWindowTest.java`

**Interfaces:**
- Consumes: `FactoryConfiguration.terminalCommand()` (Task 2).
- Produces: `LiveSessions.openTerminal(int issueNumber) throws LiveSessionException`.
- Produces: `final class TerminalWindow` with `TerminalWindow(List<String> terminalCommand, String tmuxServer, Map<String, String> parentEnvironment)` and `void open(String sessionName) throws LiveSessionException`.
- `TmuxLiveSessions` gets a `TerminalWindow` in its constructors. `openTerminal` refuses with `LiveSessionException("The live session of the issue #N is not open.")` when `hasSession` is false.

`open` runs the terminal command followed by `tmux -L <server> attach -t <sessionName>`. It uses the environment of `ClaudeCommand.environment(parentEnvironment)`, discards the output, and waits at most 5 s. A program that is not found, or an exit code that is not 0 within the 5 s, throws `LiveSessionException("The terminal window did not open: " + reason)`. A process that still runs after 5 s counts as open (a terminal like `xterm` stays in the foreground).

- [ ] **Step 1: Write the failing tests** (use a fake terminal script in a `@TempDir`, as `UsageProbeTest` does):
  - `runsTheTerminalCommandWithTheAttachCommand`: the script writes its arguments to a file; the file is `["--", "tmux", "-L", "factory-test", "attach", "-t", "factory-issue-7"]` for the command `[<script>, --]`.
  - `aMissingProgramGivesALiveSessionException`: the message starts with `"The terminal window did not open: "`.
  - `anExitCodeThatIsNotZeroGivesALiveSessionException`.
  - `aTerminalThatStaysInTheForegroundCountsAsOpen`: the script runs `sleep 30`; `open` returns in less than 10 s.
- [ ] **Step 2: Run them and see them fail.**
- [ ] **Step 3: Write `TerminalWindow`, `openTerminal`, and the fakes.** `FakeLiveSessions.openTerminal` records the issue number in a list `openedTerminals`, and can throw a set exception.
- [ ] **Step 4: Run `./mvnw -q test`.** Expected: all pass.
- [ ] **Step 5: Commit** "Open a terminal window on the live session".

### Task 4: The place in live mode

**Files:**
- Create: `src/main/java/com/meterian/factory/workflow/LiveComment.java`
- Modify: `src/main/java/com/meterian/factory/workflow/PageRequests.java`, `LiveMode.java`, `IssueWorkflow.java`, `Main.java`
- Test: `src/test/java/com/meterian/factory/workflow/LiveModeTest.java`, `PageRequestsTest.java`

**Interfaces:**
- Consumes: `LivePlace`, `LiveSession.withPlace` (Task 1); `LiveSessions.openTerminal` (Task 3).
- Produces: `PageRequests.rememberEnterPlace(int issueNumber, LivePlace place)` and `LivePlace takeEnterPlace(int issueNumber)`: returns the remembered place and forgets it, or PHONE. The place expires with `REQUEST_LIFETIME`.
- Produces: `public final class LiveComment` with `static String render(String link, Optional<String> mentionedLogin)`. The text is the text of today's `renderOpenComment`; with an empty mention, it ends after the line "Type /factory:continue…".
- `LiveMode` gets `PageRequests` in its constructor. `IssueWorkflow` passes the shared page requests (from `Main.SharedState`).

Rules in `LiveMode.enter`:
- The place is `pageRequests.takeEnterPlace(issueNumber)`. It goes into the live state with `withPlace`.
- The comment mentions the first allowed account only for PHONE.
- For TERMINAL, after the comment, `liveSessions.openTerminal(issueNumber)`. A `LiveSessionException` there adds the note `"The terminal window did not open: <message> Use the Remote Control link."` and keeps the session open.

- [ ] **Step 1: Write the failing tests**
  - `PageRequestsTest.takesTheEnterPlaceOnce`: remember TERMINAL; the first take gives TERMINAL, the second gives PHONE.
  - `PageRequestsTest.anExpiredEnterPlaceIsPhone`: advance the clock by 24 h and 1 s.
  - `LiveModeTest.anEnterWithoutAPageRequestIsPhoneWithTheMention`: the comment contains `@bbossola`; `live().get().place() == PHONE`.
  - `LiveModeTest.anEnterInTheTerminalOpensTheTerminalWithoutTheMention`: remember TERMINAL; the comment does not contain `@`; `liveSessions.openedTerminals` is `[7]`.
  - `LiveModeTest.anEnterInTheBrowserHasNoMention`.
  - `LiveModeTest.aTerminalThatDoesNotOpenKeepsTheSession`: the fake throws `LiveSessionException("no display")`; the record is live, has the link, and has the note `"The terminal window did not open: no display Use the Remote Control link."`.
- [ ] **Step 2: Run them and see them fail.**
- [ ] **Step 3: Write the code.** Move the comment text from `LiveMode.renderOpenComment` to `LiveComment.render`.
- [ ] **Step 4: Run `./mvnw -q test`.** Expected: all pass.
- [ ] **Step 5: Commit** "Use the chosen place when the live conversation opens".

### Task 5: The new page actions

**Files:**
- Create: `src/main/java/com/meterian/factory/web/ViewerLocation.java`, `src/main/java/com/meterian/factory/web/PhoneLinkSender.java`
- Modify: `src/main/java/com/meterian/factory/web/PageAction.java`, `PageActions.java`, `ActionHandler.java`, `Main.java`
- Test: `PageActionTest.java`, `WebServerTest.java`, `MainTest.java`

**Interfaces:**
- Consumes: `PageRequests.rememberEnterPlace` (Task 4); `LiveSessions.openTerminal` (Task 3); `LiveComment.render` (Task 4).
- Produces: `PageAction` values, in this order: `ENTER_TERMINAL("enter-terminal", "Terminal", "factory:enter")`, `ENTER_BROWSER("enter-browser", "Browser", "factory:enter")`, `ENTER_PHONE("enter-phone", "Phone", "factory:enter")`, `APPROVE`, `BLOCK`, `CONTINUE`, `OPEN_TERMINAL("open-terminal", "Terminal", null)`, `SEND_TO_PHONE("send-to-phone", "Phone", null)`. `ENTER` goes away.
- Produces: `Optional<LivePlace> enterPlace()` (the three ENTER values only), `boolean needsThisMachine()` (ENTER_TERMINAL, OPEN_TERMINAL), `String tooltip()`.
- Produces: `isPossible(IssueCard card, boolean continueRequested, Set<String> rememberedLabels, boolean viewerOnThisMachine)`. An action with `needsThisMachine()` is not possible when `viewerOnThisMachine` is false. OPEN_TERMINAL and SEND_TO_PHONE need a live link and no continue request.
- Produces: `PageActions.perform(int issueNumber, PageAction action, Optional<String> state, boolean viewerOnThisMachine)`.
- Produces: `interface PhoneLinkSender { void sendLink(int issueNumber, String link); }`. `Main` makes it with `gitHub.createComment(issueNumber, LiveComment.render(link, firstAllowedAccount))`.
- Produces: `final class ViewerLocation` with `static boolean isThisMachine(HttpExchange exchange)`: true when `exchange.getRemoteAddress().getAddress().isLoopbackAddress()`. It never reads a header.

Texts (exact):

| Action | Request line | Tooltip |
|---|---|---|
| ENTER_TERMINAL | "Enter sent. A terminal window opens on this machine in a few seconds, or after the current step." | "Opens the conversation in a terminal window on this machine. Close the window with Ctrl-b d." |
| ENTER_BROWSER | "Enter sent. The new tab goes to the conversation in a few seconds, or after the current step." | "Opens the conversation in a new tab of this browser. The tab waits a few seconds for the link." |
| ENTER_PHONE | "Enter sent. The link goes to your phone in a few seconds, or after the current step." | "Sends the link of the conversation to your phone, with a GitHub notification." |
| OPEN_TERMINAL | (none) | "Opens one more terminal window on this machine, attached to the same conversation." |
| SEND_TO_PHONE | (none) | "Sends the link to your phone again, with one more GitHub comment." |

Behavior of `perform`:
- ENTER_*: `pageRequests.rememberEnterPlace(issueNumber, place)` before the label, then the label as today, then the early poll.
- OPEN_TERMINAL: `liveSessions.openTerminal(issueNumber)`. A `LiveSessionException` gives the new outcome `ActionOutcome.FAILED`; `ActionHandler` answers 500 with the message of the exception. No early poll.
- SEND_TO_PHONE: `phoneLinkSender.sendLink(issueNumber, link)`. No early poll.
- `ActionHandler` redirects a DONE ENTER_BROWSER to `/live/<issueNumber>`, every other DONE to `/`.

- [ ] **Step 1: Write the failing tests**
  - `WebServerTest.aTerminalActionFromAnotherDeviceGives409`: call `PageActions.perform(12, ENTER_TERMINAL, state, false)` directly and expect `NOT_POSSIBLE`, no label call, no early poll. (The test client always comes from 127.0.0.1, so this test calls `PageActions`.)
  - `WebServerTest.anEnterInTheBrowserRedirectsToTheWaitPage`: `Location` is `/live/12`.
  - `WebServerTest.anEnterInTheTerminalRemembersThePlace`: `pageRequests.takeEnterPlace(12) == TERMINAL`.
  - `WebServerTest.openTerminalOpensTheTerminalWithoutAGitHubRequest`: fake `openedTerminals == [12]`, `labelCalls` empty.
  - `WebServerTest.aTerminalThatDoesNotOpenGives500WithTheMessage`.
  - `WebServerTest.sendToPhonePostsTheLink`: the fake sender got `(12, LINK)`.
  - `PageActionTest`: the rules of `needsThisMachine`, OPEN_TERMINAL without a link is not possible, SEND_TO_PHONE with a continue request is not possible.
  - Update the tests that use `ENTER` or `/enter` (7 places) to `ENTER_PHONE` and `/enter-phone`.
- [ ] **Step 2: Run them and see them fail.**
- [ ] **Step 3: Write the code.** `ActionHandler` passes `ViewerLocation.isThisMachine(exchange)` to `perform`. Until Task 7, `CardRenderer` calls `isPossible` with `true`.
- [ ] **Step 4: Run `./mvnw -q test`.** Expected: all pass.
- [ ] **Step 5: Commit** "Add the page actions of the three places".

### Task 6: The wait page of Browser

**Files:**
- Create: `src/main/java/com/meterian/factory/web/LiveHandler.java`, `src/main/java/com/meterian/factory/web/LivePage.java`, `src/main/resources/web/live.js`
- Modify: `src/main/java/com/meterian/factory/web/WebServer.java`
- Test: `src/test/java/com/meterian/factory/web/WebServerTest.java`

**Interfaces:**
- Consumes: `BoardState.snapshot()`, `TmuxLiveSessions.isRemoteControlLink`.
- Produces: `GET /live/<n>`: the wait page (login needed; without it, a redirect to `/login`). `GET /live/<n>/link`: `200` with `application/json` body `{"link":"<link>"}` when the card has a live link that matches the pattern, else `204`. Any other method gives 405. A number that does not match `[0-9]{1,9}` gives 404.
- `WebServer` adds `addContext("/live/", liveHandler)` and `addContext("/live.js", new ResourceHandler("live.js", "text/javascript; charset=utf-8"))`.

The wait page uses `page.css` and `live.js`, and the centered box of the login page. It has the issue number in `data-issue` on `<main>`, a spinner, the title "The live conversation of #<n> opens…", and the lines "This tab goes to the conversation when it is ready." and "Usually in a few seconds. After the current step, if the issue is in work.". `live.js` reads `/live/<n>/link` every 1 s. On a 200 it checks the link again with the pattern `^https://claude\.ai/code/session_[A-Za-z0-9]+$` and then sets `window.location.href`. After 10 minutes it stops and shows "The conversation did not open. Look at the card on the board.". A 401 reloads the page.

- [ ] **Step 1: Write the failing tests**
  - `theWaitPageNeedsTheLogin`, `theWaitPageNamesTheIssue` (contains `data-issue="12"` and `src="/live.js"` and no `<script>` with content).
  - `theLinkIsMissingBeforeTheSessionOpens` (204), `theLinkComesWhenTheSessionIsOpen` (200, body `{"link":"https://claude.ai/code/session_abc123"}`).
  - `aLinkThatIsNotRemoteControlIsNeverGiven`: a live link `javascript:alert(1)` gives 204.
  - `theScriptOfTheWaitPageIsServed`: `/live.js` gives 200 with `text/javascript`.
- [ ] **Step 2: Run them and see them fail.**
- [ ] **Step 3: Write the code.** Write the JSON with Jackson, not by hand.
- [ ] **Step 4: Run `./mvnw -q test`.** Expected: all pass.
- [ ] **Step 5: Commit** "Add the wait page of Browser".

### Task 7: The card

**Files:**
- Modify: `src/main/java/com/meterian/factory/web/CardRenderer.java`, `PageModel.java`, `PageModelSource.java`, `PageHandler.java`, `src/main/resources/web/page.css`
- Test: `src/test/java/com/meterian/factory/web/BoardPageTest.java`

**Interfaces:**
- Consumes: `PageAction.tooltip()`, `needsThisMachine()`, `isPossible(..., viewerOnThisMachine)` (Task 5); `LiveSession.place()` (Task 1).
- Produces: `PageModel` gets the last component `boolean viewerOnThisMachine`. `PageModelSource.read(boolean viewerOnThisMachine)`. `PageHandler` passes `ViewerLocation.isThisMachine(exchange)` for `/` and `/board`.

Markup (the CSS of the mock, version 5, goes into `page.css`):
- The ENTER actions that are possible go into `<div class="enter-group">`, before the other forms. Each is a form with a `<button data-tip="<tooltip>" aria-label="<name>. <tooltip>">`. The form of ENTER_BROWSER has `target="_blank"`.
- The live box replaces `appendRemoteControlLink`: `<div class="live-box"><p class="live-title">Live conversation open</p><p class="open-label"><line> Open it again or somewhere else:</p><div class="open-group">…</div></div>`. The lines: TERMINAL "You opened it in a terminal on this machine.", BROWSER "You opened it in a tab of this browser.", PHONE "You sent it to the phone.". The group has the OPEN_TERMINAL form (this machine only), the Browser link (`appendExternalLink`, text "Browser"), and the SEND_TO_PHONE form. The element of the chosen place has the class `current`. A live link that does not match the pattern shows as text, as today, and the group has no Browser link.
- No "new tab" arrow anywhere.

- [ ] **Step 1: Write the failing tests**
  - `theEnterButtonsAreOneGroupWithTooltips`: contains `class="enter-group"`, the three tooltips, and `target="_blank"` on the form of `/issues/12/enter-browser`.
  - `anotherDeviceHasNoTerminalButton`: with `viewerOnThisMachine` false, no `/enter-terminal` and no `/open-terminal`.
  - `theLiveBoxMarksTheChosenPlace`: a live record with place BROWSER has `class="current"` on the Browser link and the line "You opened it in a tab of this browser.".
  - `theLiveBoxHasNoBrowserLinkForAWrongLink`: the link `javascript:alert(1)` gives no `href="javascript`.
  - Update the existing tests of the Remote Control link and of the Enter button.
- [ ] **Step 2: Run them and see them fail.**
- [ ] **Step 3: Write the code and the CSS.**
- [ ] **Step 4: Run `./mvnw -q test`.** Expected: all pass.
- [ ] **Step 5: Check in Chrome** with the preview of `scratchpad/preview` (or the running worker): the tooltip shows and stays inside the card, the enter row does not break, the live box matches the mock.
- [ ] **Step 6: Commit** "Show the three places on the card".

### Task 8: Documentation

**Files:**
- Modify: `README.md` (sections "Live mode" and "Web page", and the configuration example)

- [ ] **Step 1: Describe** the three places, the tooltips, the wait page, "this machine only" for Terminal, the setting `live.terminal-command`, `Ctrl-b d`, and the device sign-in of claude.ai. Copy the facts from spec section 8.4.
- [ ] **Step 2: Run `./mvnw verify`.** Expected: BUILD SUCCESS.
- [ ] **Step 3: Commit** "Describe the three places of a live conversation".
