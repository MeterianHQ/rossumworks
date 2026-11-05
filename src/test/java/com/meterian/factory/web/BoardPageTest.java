package com.meterian.factory.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.meterian.factory.agent.ContextUsage;
import com.meterian.factory.agent.UsageWindow;
import com.meterian.factory.board.BoardSnapshot;
import com.meterian.factory.board.IssueCard;
import com.meterian.factory.configuration.IsolationMode;
import com.meterian.factory.state.IssueRecord;
import com.meterian.factory.state.LivePlace;
import com.meterian.factory.state.LiveSession;
import com.meterian.factory.state.RiskLevel;
import com.meterian.factory.state.Verdict;
import com.meterian.factory.testing.RecordStates;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class BoardPageTest {

    private static final Instant NOW = Instant.parse("2026-11-02T10:00:00Z");
    private static final Duration POLL_INTERVAL = Duration.ofSeconds(60);
    private static final String NEW_TAB = "\" target=\"_blank\" rel=\"noopener noreferrer\">";
    private static final String LINK = "https://claude.ai/code/session_abc123";

    private final BoardPage page = new BoardPage(ZoneId.of("Europe/Rome"));

    private static IssueCard card(IssueRecord record) {
        return card(record, "Title " + record.issueNumber());
    }

    private static IssueCard card(IssueRecord record, String title) {
        return new IssueCard(record.issueNumber(), title, Set.of("factory"), record, Optional.empty());
    }

    private static final String NEEDS_APPROVAL_NOTE = "The merge needs your approval.";

    /** A record in the state of the built-in workflow, after the state implement. The start state stays queued. */
    private static IssueRecord record(int number, String stateName) {
        IssueRecord queued = RecordStates.queued(number).withAgentSessionStarted();
        if (stateName.equals(queued.state())) {
            return queued;
        }
        return RecordStates.move(RecordStates.move(queued, "implement"), stateName);
    }

    private static IssueRecord waitingRecord(int number, String stateName, String note) {
        return record(number, stateName).withNewestNote(note);
    }

    private static IssueRecord waitsForApproval(int number) {
        return waitingRecord(number, "waits-for-approval", NEEDS_APPROVAL_NOTE);
    }

    private static IssueRecord liveRecord(int number, String link) {
        return liveRecord(number, link, LivePlace.PHONE);
    }

    private static IssueRecord liveRecord(int number, String link, LivePlace place) {
        LiveSession live = new LiveSession("abc", Optional.of(link), place);
        return waitingRecord(number, "live", "The agent has a question.").withLive(live);
    }

    private static PageModel model(List<IssueCard> cards) {
        return model(cards, Optional.of(NOW.minusSeconds(30)), List.of(), Optional.empty(), Map.of());
    }

    private static PageModel model(List<IssueCard> cards, Optional<Instant> lastCheckAt,
            List<UsageWindow> usageWindows, Optional<Instant> usageResumesAt, Map<Integer, ContextUsage> contexts) {
        return model(cards, lastCheckAt, usageWindows, usageResumesAt, contexts, Map.of());
    }

    private static PageModel modelWithRememberedLabels(List<IssueCard> cards,
            Map<Integer, Set<String>> rememberedLabels) {
        return model(cards, Optional.of(NOW.minusSeconds(30)), List.of(), Optional.empty(), Map.of(),
                rememberedLabels);
    }

    private static PageModel model(List<IssueCard> cards, Optional<Instant> lastCheckAt,
            List<UsageWindow> usageWindows, Optional<Instant> usageResumesAt, Map<Integer, ContextUsage> contexts,
            Map<Integer, Set<String>> rememberedLabels) {
        return model(cards, lastCheckAt, usageWindows, usageResumesAt, contexts, rememberedLabels, Set.of());
    }

    private static PageModel modelWithContinueRequested(List<IssueCard> cards, Set<Integer> continueRequested) {
        return model(cards, Optional.of(NOW.minusSeconds(30)), List.of(), Optional.empty(), Map.of(), Map.of(),
                continueRequested);
    }

    private static PageModel model(List<IssueCard> cards, Optional<Instant> lastCheckAt,
            List<UsageWindow> usageWindows, Optional<Instant> usageResumesAt, Map<Integer, ContextUsage> contexts,
            Map<Integer, Set<String>> rememberedLabels, Set<Integer> continueRequested) {
        return model(cards, lastCheckAt, usageWindows, usageResumesAt, contexts, rememberedLabels, continueRequested,
                Map.of(), true);
    }

    private static PageModel modelWithWarning(List<IssueCard> cards, String definitionWarning) {
        return model(cards, Optional.of(NOW.minusSeconds(30)), List.of(), Optional.empty(), Map.of(), Map.of(),
                Set.of(), Map.of(), true, Optional.of(definitionWarning));
    }

    private static PageModel modelWithEnterPlaces(List<IssueCard> cards, Map<Integer, LivePlace> enterPlaces) {
        return model(cards, Optional.of(NOW.minusSeconds(30)), List.of(), Optional.empty(), Map.of(),
                Map.of(12, Set.of("factory:enter")), Set.of(), enterPlaces, true);
    }

    private static PageModel modelForAnotherDevice(List<IssueCard> cards) {
        return model(cards, Optional.of(NOW.minusSeconds(30)), List.of(), Optional.empty(), Map.of(), Map.of(),
                Set.of(), Map.of(), false);
    }

    private static PageModel model(List<IssueCard> cards, Optional<Instant> lastCheckAt,
            List<UsageWindow> usageWindows, Optional<Instant> usageResumesAt, Map<Integer, ContextUsage> contexts,
            Map<Integer, Set<String>> rememberedLabels, Set<Integer> continueRequested,
            Map<Integer, LivePlace> enterPlaces, boolean viewerOnThisMachine) {
        return model(cards, lastCheckAt, usageWindows, usageResumesAt, contexts, rememberedLabels, continueRequested,
                enterPlaces, viewerOnThisMachine, Optional.empty());
    }

    private static PageModel model(List<IssueCard> cards, Optional<Instant> lastCheckAt,
            List<UsageWindow> usageWindows, Optional<Instant> usageResumesAt, Map<Integer, ContextUsage> contexts,
            Map<Integer, Set<String>> rememberedLabels, Set<Integer> continueRequested,
            Map<Integer, LivePlace> enterPlaces, boolean viewerOnThisMachine, Optional<String> definitionWarning) {
        BoardSnapshot snapshot = new BoardSnapshot(NOW.minus(Duration.ofHours(2)), lastCheckAt, cards, 7);
        Map<Integer, Map<String, Integer>> limits = cards.stream()
                .collect(Collectors.toMap(IssueCard::issueNumber, card -> LIMITS, (first, second) -> first));
        Map<Integer, List<String>> reviewNames = cards.stream().collect(
                Collectors.toMap(IssueCard::issueNumber, card -> REVIEW_NAMES, (first, second) -> first));
        Set<Integer> inBlockTarget = cards.stream().filter(card -> card.record().state().equals("blocked"))
                .map(IssueCard::issueNumber).collect(Collectors.toSet());
        return new PageModel(snapshot, NOW, 2, 2, limits, reviewNames, inBlockTarget, IsolationMode.SAME_USER, POLL_INTERVAL,
                usageWindows, usageResumesAt, contexts, continueRequested, rememberedLabels, enterPlaces,
                "MeterianHQ/target", viewerOnThisMachine, definitionWarning, false, DEFAULT_COLUMNS);
    }

    private static PageModel withColumns(PageModel model, List<String> columns) {
        return new PageModel(model.board(), model.renderedAt(), model.issuesInWork(), model.parallelLimit(),
                model.limits(), model.reviewNames(), model.inBlockTarget(), model.isolationMode(), model.pollInterval(), model.usageWindows(),
                model.usageResumesAt(), model.contexts(), model.continueRequested(), model.rememberedLabels(),
                model.enterPlaces(), model.repositoryFullName(), model.viewerOnThisMachine(),
                model.definitionWarning(), model.pickUpPaused(), columns);
    }

    private static PageModel withReviewNames(PageModel model, List<String> reviewNames) {
        Map<Integer, List<String>> namesByIssue = model.board().cards().stream()
                .collect(Collectors.toMap(IssueCard::issueNumber, card -> reviewNames));
        return new PageModel(model.board(), model.renderedAt(), model.issuesInWork(), model.parallelLimit(),
                model.limits(), namesByIssue, model.inBlockTarget(), model.isolationMode(), model.pollInterval(),
                model.usageWindows(), model.usageResumesAt(), model.contexts(), model.continueRequested(),
                model.rememberedLabels(), model.enterPlaces(), model.repositoryFullName(),
                model.viewerOnThisMachine(), model.definitionWarning(), model.pickUpPaused(), model.columns());
    }

    private static PageModel withPausedPickUp(PageModel model) {
        return new PageModel(model.board(), model.renderedAt(), model.issuesInWork(), model.parallelLimit(),
                model.limits(), model.reviewNames(), model.inBlockTarget(), model.isolationMode(), model.pollInterval(), model.usageWindows(),
                model.usageResumesAt(), model.contexts(), model.continueRequested(), model.rememberedLabels(),
                model.enterPlaces(), model.repositoryFullName(), model.viewerOnThisMachine(),
                model.definitionWarning(), true, model.columns());
    }

    private static final List<String> REVIEW_NAMES = List.of("correctness", "security", "architecture", "quality");
    private static final List<String> DEFAULT_COLUMNS =
            List.of("queued", "in-progress", "in-review", "waits-for-you", "blocked", "done");
    private static final Map<String, Integer> LIMITS = createLimits();

    private static Map<String, Integer> createLimits() {
        Map<String, Integer> limits = new LinkedHashMap<>();
        limits.put("implement", 3);
        limits.put("review", 10);
        return limits;
    }

    private static IssueCard cardWithLabel(IssueRecord record, String label) {
        return new IssueCard(record.issueNumber(), "Title", Set.of("factory", label), record, Optional.empty());
    }

    private static String requestLine(String sentence) {
        return "<p class=\"request\">" + sentence + "</p>";
    }

    private static String columnName(String name) {
        return "<span class=\"column-name\">" + name + "</span>";
    }

    private static String tile(String label, String value) {
        return "<div class=\"tile\"><span class=\"tile-label\">" + label + "</span><span class=\"tile-value\">"
                + value + "</span></div>";
    }

    @Test
    void showsTheWarningOfTheDefinition() {
        String warning = "The .factory of 1234567 does not load: factory.yaml: <bad> key. "
                + "The factory keeps the version 89abcde.";

        String html = page.renderBoard(modelWithWarning(List.of(), warning));

        assertThat(html).contains("<p class=\"warning\">" + Html.escape(warning) + "</p>");
        assertThat(html).doesNotContain("<bad>");
    }

    @Test
    void showsNoDefinitionWarningWithoutAWarning() {
        assertThat(page.renderBoard(model(List.of()))).doesNotContain("class=\"warning\"");
    }

    @Test
    void escapesTheTitleTheNoteAndTheReason() {
        IssueRecord record = waitsForApproval(12)
                .withNewestNote("\"><img src=x onerror=alert(1)>");

        String html = page.renderBoard(model(List.of(card(record, "<script>alert(1)</script>"))));

        assertThat(html).doesNotContain("<script").doesNotContain("<img");
        assertThat(html).contains("&lt;script&gt;alert(1)&lt;/script&gt;");
        assertThat(html).contains("<p class=\"reason\">&quot;&gt;&lt;img src=x onerror=alert(1)&gt;</p>");
    }

    @Test
    void aLinkThatIsNotRemoteControlIsText() {
        String html = page.renderBoard(model(List.of(card(liveRecord(12, "javascript:alert(1)")))));

        assertThat(html).doesNotContain("href=\"javascript:");
        assertThat(html).contains("javascript:alert(1)");
    }

    @Test
    void aLinkWithMoreTextAfterThePatternIsText() {
        String html = page.renderBoard(model(List.of(card(liveRecord(12, LINK + "\"><b>x")))));

        assertThat(html).doesNotContain("href=\"" + LINK);
        assertThat(html).doesNotContain("<b>");
    }

    @Test
    void aWaitingCardShowsTheReasonAndTheLink() {
        String html = page.renderBoard(model(List.of(card(liveRecord(12, LINK)))));

        assertThat(html).contains("class=\"card waits\"");
        assertThat(html).contains("<p class=\"reason\">The agent has a question.</p>");
        assertThat(html).contains("<div class=\"live-box\"><p class=\"live-title\">Live conversation open</p>");
        assertThat(html).contains("<a href=\"" + LINK + "\" target=\"_blank\" rel=\"noopener noreferrer\"");
    }

    @Test
    void theEnterButtonsAreOneGroupWithTooltips() {
        IssueRecord record = waitsForApproval(12);

        String html = page.renderBoard(model(List.of(card(record))));

        assertThat(html).contains("<div class=\"enter-group\">");
        assertThat(html).contains("<form method=\"post\" action=\"/issues/12/enter-browser\" target=\"_blank\">");
        assertThat(html).contains("data-tip=\"" + PageAction.ENTER_TERMINAL.tooltip() + "\"",
                "data-tip=\"" + PageAction.ENTER_BROWSER.tooltip() + "\"",
                "data-tip=\"" + PageAction.ENTER_PHONE.tooltip() + "\"");
        assertThat(html).contains("aria-label=\"Phone. " + PageAction.ENTER_PHONE.tooltip() + "\"");
        assertThat(html).containsSubsequence("class=\"enter-group\"", "/enter-terminal", "/enter-browser",
                "/enter-phone", "/approve", "/block");
        assertThat(html).doesNotContain("\u2197").doesNotContain("class=\"icon\"");
    }

    @Test
    void anotherDeviceHasNoTerminalButton() {
        IssueRecord waiting = waitsForApproval(12);

        String html = page.renderBoard(modelForAnotherDevice(List.of(card(waiting), card(liveRecord(13, LINK)))));

        assertThat(html).contains("/issues/12/enter-browser", "/issues/12/enter-phone", "/issues/13/send-to-phone");
        assertThat(html).doesNotContain("/enter-terminal").doesNotContain("/open-terminal");
    }

    @Test
    void theLiveBoxMarksTheChosenPlace() {
        String html = page.renderBoard(model(List.of(card(liveRecord(12, LINK, LivePlace.BROWSER)))));

        assertThat(html).contains("<p class=\"open-label\">You opened it in a tab of this browser."
                + " Open it again or somewhere else:</p>");
        assertThat(html).contains("<a href=\"" + LINK + NEW_TAB.substring(0, NEW_TAB.length() - 1)
                + " class=\"current\"");
        assertThat(html).containsSubsequence("<div class=\"open-group\">", "/issues/12/open-terminal", LINK,
                "/issues/12/send-to-phone");
        assertThat(html).containsOnlyOnce("class=\"current\"").doesNotContain("class=\"icon\"");
    }

    @Test
    void theLiveBoxMarksTheTerminalAsTheChosenPlace() {
        String html = page.renderBoard(model(List.of(card(liveRecord(12, LINK, LivePlace.TERMINAL)))));

        assertThat(html).contains("You opened it in a terminal on this machine.");
        assertThat(html).containsSubsequence("/issues/12/open-terminal", "<button class=\"current\"", LINK);
        assertThat(html).containsOnlyOnce("class=\"current\"");
    }

    @Test
    void theLiveBoxHasNoBrowserLinkForAWrongLink() {
        String html = page.renderBoard(model(List.of(card(liveRecord(12, "javascript:alert(1)")))));

        assertThat(html).contains("You sent it to the phone.");
        assertThat(html).doesNotContain("href=\"javascript").doesNotContain("Browser</a>");
        assertThat(html).contains("javascript:alert(1)");
    }

    @Test
    void anEnterShowsOnlyTheLineOfTheChosenPlace() {
        IssueRecord record = waitsForApproval(12);
        List<IssueCard> cards = List.of(cardWithLabel(record, "factory:enter"));

        String chosen = page.renderBoard(modelWithEnterPlaces(cards, Map.of(12, LivePlace.TERMINAL)));
        String withoutPlace = page.renderBoard(modelWithEnterPlaces(cards, Map.of()));

        assertThat(chosen).containsOnlyOnce("class=\"request\"")
                .contains(requestLine(PageAction.ENTER_TERMINAL.requestSentence().orElseThrow()));
        assertThat(withoutPlace).doesNotContain("class=\"request\"");
    }

    @Test
    void marksOnlyTheCardsThatWaitForYou() {
        List<IssueCard> cards = List.of(card(waitsForApproval(3)), card(liveRecord(4, LINK)),
                card(record(5, "blocked")), card(record(6, "done")), card(record(7, "implement")));

        String html = page.renderBoard(model(cards));

        assertThat(html.split("class=\"card waits\"", -1)).hasSize(3);
        assertThat(html).containsSubsequence("class=\"card waits\"", "#3 ", "class=\"card waits\"", "#4 ");
        assertThat(html).containsSubsequence("<article class=\"card\">", "#5 ");
        assertThat(html).containsSubsequence("<article class=\"card\">", "#6 ");
    }

    @Test
    void theColumnsFollowTheProgressInTime() {
        List<IssueCard> cards = List.of(
                card(record(3, "done")), card(record(4, "blocked")),
                card(record(5, "prepare")), card(record(6, "review")),
                card(record(7, "implement")), card(waitsForApproval(8)));

        String html = page.renderBoard(model(cards));

        assertThat(html).containsSubsequence(columnName("Queued"), "#5 ", columnName("In progress"), "#7 ",
                columnName("In review"), "#6 ", columnName("Waits for you"), "#8 ", columnName("Blocked"), "#4 ",
                columnName("Done"), "#3 ");
    }

    @Test
    void showsTheColumnsInTheOrderOfTheDefinition() {
        PageModel model = withColumns(model(List.of(card(record(5, "prepare")))),
                List.of("queued", "doing", "needs-me", "done"));

        String html = page.renderBoard(model);

        assertThat(html).containsSubsequence(columnName("Queued"), columnName("Doing"), columnName("Needs me"),
                columnName("Done"));
        assertThat(html).contains("class=\"column status-needs-me\"").doesNotContain("In progress");
    }

    @Test
    void showsACardOfAnUnknownColumnInAnExtraColumn() {
        IssueCard later = card(record(9, "prepare").withMove("oldstate", Optional.empty(), "old-column"));
        PageModel model = withColumns(model(List.of(later, card(record(4, "prepare")))),
                List.of("queued", "done"));

        String html = page.renderBoard(model);

        assertThat(html).containsSubsequence(columnName("Queued"), columnName("Done"), columnName("Old column"));
    }

    @Test
    void eachColumnShowsItsStatusAndTheNumberOfCards() {
        List<IssueCard> cards = List.of(card(record(5, "prepare")), card(record(6, "prepare")));

        String html = page.renderBoard(model(cards));

        assertThat(html).contains("<section class=\"column status-queued\"><h2>" + columnName("Queued")
                + "<span class=\"count\">2</span></h2>");
        assertThat(html).contains("<section class=\"column status-waits-for-you\"><h2>" + columnName("Waits for you")
                + "<span class=\"count\">0</span></h2><div class=\"cards\"><p class=\"empty\">No issues</p></div>");
    }

    @Test
    void sortsTheCardsByNumberAndTheDoneCardsNewestFirst() {
        IssueCard olderDone = new IssueCard(20, "Older", Set.of(), record(20, "done"),
                Optional.of(NOW.minus(Duration.ofDays(2))));
        IssueCard newerDone = new IssueCard(10, "Newer", Set.of(), record(10, "done"),
                Optional.of(NOW.minus(Duration.ofDays(1))));
        List<IssueCard> cards = List.of(card(record(9, "prepare")), card(record(2, "prepare")),
                olderDone, newerDone);

        String html = page.renderBoard(model(cards));

        assertThat(html).containsSubsequence("#2 ", "#9 ", "#10 ", "Done on 2026-11-01", "#20 ", "Done on 2026-10-31");
    }

    @Test
    void showsTheContextAsAPercent() {
        PageModel model = model(List.of(card(record(12, "implement"))), Optional.of(NOW), List.of(),
                Optional.empty(), Map.of(12, new ContextUsage(620_000, 1_000_000)));

        String html = page.renderBoard(model);

        assertThat(html).contains("<meter min=\"0\" max=\"100\" value=\"62\">");
        assertThat(html).contains("<span class=\"context-value\">62%</span>");
        assertThat(html).doesNotContain("style=");
    }

    @Test
    void rendersTheReviewNamesOfTheDefinition() {
        IssueRecord record = record(12, "review").withReview("style", Verdict.APPROVE);
        PageModel model = model(List.of(card(record, "Fix the parser")));

        String html = page.renderBoard(withReviewNames(model, List.of("style", "code-quality")));

        assertThat(html).contains("<li class=\"review positive\" title=\"Style: positive\">Style</li>",
                "<li class=\"review none\" title=\"Code-quality: —\">Code-quality</li>");
        assertThat(html).doesNotContain(">Correctness</li>");
    }

    @Test
    void showsTheCardDetails() {
        IssueRecord record = record(12, "review")
                .withReview("correctness", Verdict.APPROVE)
                .withReview("security", Verdict.REJECT)
                .withEntryCounted("implement")
                .withRisk(RiskLevel.LOW, true, List.of("It <changes> the login."))
                .withPullRequest(40)
                .withNote("First note.")
                .withNote("Newest note.");

        String html = page.renderBoard(model(List.of(card(record, "Fix the parser"))));

        assertThat(html).contains(
                "<a href=\"https://github.com/MeterianHQ/target/issues/12" + NEW_TAB + "#12 Fix the parser</a>");
        assertThat(html).contains("<span class=\"phase\">review</span>");
        assertThat(html).contains("<li class=\"review positive\" title=\"Correctness: positive\">Correctness</li>",
                "<li class=\"review negative\" title=\"Security: negative\">Security</li>",
                "<li class=\"review none\" title=\"Architecture: —\">Architecture</li>",
                "<li class=\"review none\" title=\"Quality: —\">Quality</li>");
        assertThat(html).contains("<dt>Entries</dt><dd>implement 1 of 3, review 0 of 10</dd>", "<dt>Risk</dt><dd class=\"risk-low\">low</dd>");
        assertThat(html).contains(
                "<a href=\"https://github.com/MeterianHQ/target/pull/40" + NEW_TAB + "Pull request #40</a>");
        assertThat(html).contains("Newest note.").doesNotContain("First note.");
        assertThat(html).contains("risk: low, small", "<li>It &lt;changes&gt; the login.</li>");
    }

    @Test
    void showsOnlyThePossibleButtons() {
        IssueRecord record = waitsForApproval(12);

        String html = page.renderBoard(model(List.of(card(record))));

        String state = "<input type=\"hidden\" name=\"state\" value=\"" + CardFingerprint.of(card(record)) + "\">";
        assertThat(html).contains(
                "<form method=\"post\" action=\"/issues/12/approve\">" + state + "<button>Approve</button></form>");
        assertThat(html).contains("<form method=\"post\" action=\"/issues/12/enter-phone\">" + state + "<button");
        assertThat(html).contains("action=\"/issues/12/block\"").contains("<button>Block</button>");
        assertThat(html).doesNotContain("/issues/12/continue").doesNotContain("<button>Continue</button>");
    }

    @Test
    void aLabelThatThePageDidNotRequestShowsTheButton() {
        IssueRecord record = waitsForApproval(12);
        IssueCard card = new IssueCard(12, "Title", Set.of("factory", "factory:approve"), record, Optional.empty());

        String html = page.renderBoard(model(List.of(card)));

        assertThat(html).contains("action=\"/issues/12/approve\"").doesNotContain("class=\"request\"");
    }

    @Test
    void anApproveRequestReplacesTheReasonOfTheWait() {
        IssueRecord record = waitsForApproval(12);

        String html = page.renderBoard(modelWithRememberedLabels(List.of(cardWithLabel(record, "factory:approve")),
                Map.of(12, Set.of("factory:approve"))));

        assertThat(html).contains(requestLine("Approval sent. The worker takes it in a few seconds."))
                .doesNotContain("class=\"reason\"");
    }

    @Test
    void anApproveRequestEndsWhenTheIssueNoLongerWaitsForTheApproval() {
        IssueRecord record = record(12, "merge");

        String html = page.renderBoard(modelWithRememberedLabels(List.of(cardWithLabel(record, "factory:approve")),
                Map.of(12, Set.of("factory:approve"))));

        assertThat(html).doesNotContain("class=\"request\"");
    }

    @Test
    void anEnterRequestShowsThatTheConversationOpensSoon() {
        IssueRecord record = waitsForApproval(12);

        String html = page.renderBoard(modelWithEnterPlaces(List.of(cardWithLabel(record, "factory:enter")),
                Map.of(12, LivePlace.PHONE)));

        assertThat(html).contains(requestLine(
                "Enter sent. The worker stops the current action, and the link goes to your phone in a few seconds."));
    }

    @Test
    void anEnterRequestEndsWhenTheLinkIsOnTheCard() {
        String html = page.renderBoard(modelWithRememberedLabels(
                List.of(cardWithLabel(liveRecord(12, LINK), "factory:enter")), Map.of(12, Set.of("factory:enter"))));

        assertThat(html).doesNotContain("class=\"request\"");
    }

    @Test
    void aBlockRequestShowsThatTheWorkerStopsTheIssueSoon() {
        IssueRecord record = record(12, "implement");

        String html = page.renderBoard(modelWithRememberedLabels(List.of(cardWithLabel(record, "factory:block")),
                Map.of(12, Set.of("factory:block"))));

        assertThat(html).contains(requestLine("Block sent. The worker stops the issue in a few seconds."));
    }

    @Test
    void anActivePickUpHasAPauseButton() {
        String html = page.renderBoard(model(List.of()));

        assertThat(html).contains("<span class=\"tile-label\">Pick up</span><span class=\"tile-value\">On</span>")
                .contains("<form method=\"post\" action=\"/pick-up/pause\"><button>Pause</button></form>");
        assertThat(html).doesNotContain("/pick-up/resume");
    }

    @Test
    void aPausedPickUpHasAStartButtonAndANotice() {
        String html = page.renderBoard(withPausedPickUp(model(List.of())));

        assertThat(html).contains("<span class=\"tile-label\">Pick up</span><span class=\"tile-value\">Paused</span>")
                .contains("<form method=\"post\" action=\"/pick-up/resume\"><button>Start</button></form>")
                .contains("<p class=\"notice\">Pick up is paused. New issues wait in Queued. "
                        + "The issues in work continue.</p>");
        assertThat(html).doesNotContain("/pick-up/pause");
    }

    @Test
    void aBlockedCardHasAStartAgainButtonThatAsksForAConfirm() {
        String html = page.renderBoard(model(List.of(card(record(12, "blocked")))));

        assertThat(html).contains(
                "<form method=\"post\" action=\"/issues/12/start-again\" class=\"confirm-start-again\" data-issue=\"12\">")
                .contains("<button>Start again</button>");
    }

    @Test
    void aCardThatIsNotBlockedHasNoStartAgainButton() {
        String html = page.renderBoard(model(List.of(card(record(12, "implement")))));

        assertThat(html).doesNotContain("/start-again");
    }

    @Test
    void aStartAgainRequestShowsItsLineAndHidesItsButton() {
        IssueRecord record = record(12, "blocked");

        String html = page.renderBoard(modelWithRememberedLabels(List.of(cardWithLabel(record, "factory:start-again")),
                Map.of(12, Set.of("factory:start-again"))));

        assertThat(html).contains(requestLine("Start again sent. The worker starts the issue again in a few seconds."));
        assertThat(html).doesNotContain("/start-again");
    }

    @Test
    void aStartAgainRequestEndsWhenTheIssueLeavesBlocked() {
        IssueRecord record = record(12, "prepare");

        String html = page.renderBoard(modelWithRememberedLabels(List.of(cardWithLabel(record, "factory:start-again")),
                Map.of(12, Set.of("factory:start-again"))));

        assertThat(html).doesNotContain("class=\"request\"");
    }

    @Test
    void aBlockRequestEndsWhenTheIssueIsBlocked() {
        IssueRecord record = record(12, "blocked");

        String html = page.renderBoard(modelWithRememberedLabels(List.of(cardWithLabel(record, "factory:block")),
                Map.of(12, Set.of("factory:block"))));

        assertThat(html).doesNotContain("class=\"request\"");
    }

    @Test
    void aBlockRequestEndsWhenTheIssueIsDone() {
        IssueRecord record = record(12, "done");

        String html = page.renderBoard(modelWithRememberedLabels(List.of(cardWithLabel(record, "factory:block")),
                Map.of(12, Set.of("factory:block"))));

        assertThat(html).doesNotContain("class=\"request\"");
    }

    @Test
    void anEnterRequestEndsWhenTheIssueIsDone() {
        IssueRecord record = record(12, "done");

        String html = page.renderBoard(modelWithRememberedLabels(List.of(cardWithLabel(record, "factory:enter")),
                Map.of(12, Set.of("factory:enter"))));

        assertThat(html).doesNotContain("class=\"request\"");
    }

    @Test
    void aRememberedLabelThatIsNotOnTheCardShowsNoRequest() {
        IssueRecord record = waitsForApproval(12);

        String html = page.renderBoard(modelWithRememberedLabels(List.of(card(record)),
                Map.of(12, Set.of("factory:approve"))));

        assertThat(html).doesNotContain("class=\"request\"")
                .contains("<p class=\"reason\">" + NEEDS_APPROVAL_NOTE + "</p>");
    }

    @Test
    void aContinueRequestReplacesTheReasonOfTheWait() {
        String html = page.renderBoard(modelWithContinueRequested(List.of(card(liveRecord(12, LINK))), Set.of(12)));

        assertThat(html).contains(requestLine("Continue sent. The factory takes the issue back in a few seconds."))
                .doesNotContain("class=\"reason\"");
    }

    @Test
    void aLabelThatThePageRequestedHidesTheButton() {
        IssueRecord record = waitsForApproval(12);
        IssueCard card = new IssueCard(12, "Title", Set.of("factory", "factory:approve"), record, Optional.empty());

        String html = page.renderBoard(modelWithRememberedLabels(List.of(card), Map.of(12, Set.of("factory:approve"))));

        assertThat(html).doesNotContain("action=\"/issues/12/approve\"").contains("action=\"/issues/12/block\"");
    }

    @Test
    void theBlockFormAsksForAConfirmWithoutAnInlineHandler() {
        String html = page.renderBoard(model(List.of(card(record(12, "implement")))));

        assertThat(html).contains(
                "<form method=\"post\" action=\"/issues/12/block\" class=\"confirm-block\" data-issue=\"12\">");
        assertThat(html).doesNotContain("onsubmit").doesNotContain("onclick");
    }

    @Test
    void showsTheHealthOfTheWorker() {
        String html = page.renderBoard(model(List.of()));

        assertThat(html).contains("<h1>Claude AI Factory</h1><span class=\"repository\">MeterianHQ/target</span>");
        assertThat(html).contains(tile("Worker runs since", "2026-11-02 09:00"));
        assertThat(html).contains(tile("Last GitHub check", "2026-11-02 10:59"));
        assertThat(html).contains(tile("Issues in work", "2 of 2"));
        assertThat(html).contains(tile("Isolation", "same-user"));
        assertThat(html).doesNotContain("class=\"warning\"");
    }

    @Test
    void warnsWhenTheLastCheckIsOld() {
        PageModel model = model(List.of(), Optional.of(NOW.minus(POLL_INTERVAL.multipliedBy(3)).minusSeconds(1)),
                List.of(), Optional.empty(), Map.of());

        String html = page.renderBoard(model);

        assertThat(html).contains("<p class=\"warning\">The last GitHub check is old.</p>");
    }

    @Test
    void showsEachUsageWindowWithItsTime() {
        List<UsageWindow> windows = List.of(
                new UsageWindow("seven_day", 0.37, NOW.plus(Duration.ofDays(3)), "allowed", NOW.minusSeconds(600)),
                new UsageWindow("five_hour", 0.954, NOW.plus(Duration.ofHours(1)), "allowed", NOW));
        PageModel model = model(List.of(), Optional.of(NOW), windows, Optional.of(NOW.plus(Duration.ofHours(1))),
                Map.of());

        String html = page.renderBoard(model);

        assertThat(html).containsSubsequence("<span class=\"tile-label\">Weekly usage</span>",
                "<meter min=\"0\" max=\"100\" low=\"70\" high=\"90\" optimum=\"0\" value=\"37\"></meter> 37%",
                "<span class=\"tile-note\">at 2026-11-02 10:50</span>");
        assertThat(html).containsSubsequence("<span class=\"tile-label\">5-hour usage</span>", "value=\"95\"",
                "<span class=\"tile-note\">at 2026-11-02 11:00</span>");
        assertThat(html).contains("New phases start again at 2026-11-02 12:00.");
        assertThat(html).doesNotContain("$");
    }

    @Test
    void thePageWrapsTheBoardAndLinksOnlyItsOwnFiles() {
        PageModel model = model(List.of(card(record(12, "implement"))));

        String html = page.renderPage(model);

        assertThat(html).startsWith("<!DOCTYPE html>");
        assertThat(html).contains("<div id=\"board\">" + page.renderBoard(model) + "</div>");
        assertThat(html).contains("<link rel=\"stylesheet\" href=\"/page.css\">");
        assertThat(html).contains("<script src=\"/page.js\" defer></script>");
        assertThat(html).doesNotContain("style=").doesNotContain("<style");
    }
}
