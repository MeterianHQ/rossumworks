package com.meterian.factory.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.meterian.factory.agent.ContextUsage;
import com.meterian.factory.agent.UsageWindow;
import com.meterian.factory.board.BoardSnapshot;
import com.meterian.factory.board.IssueCard;
import com.meterian.factory.configuration.IsolationMode;
import com.meterian.factory.state.IssueRecord;
import com.meterian.factory.state.IssueStatus;
import com.meterian.factory.state.LiveSession;
import com.meterian.factory.state.Phase;
import com.meterian.factory.state.ReviewKind;
import com.meterian.factory.state.RiskLevel;
import com.meterian.factory.state.Verdict;
import com.meterian.factory.state.WaitReason;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class BoardPageTest {

    private static final Instant NOW = Instant.parse("2026-11-02T10:00:00Z");
    private static final Duration POLL_INTERVAL = Duration.ofSeconds(60);
    private static final String LINK = "https://claude.ai/code/session_abc123";

    private final BoardPage page = new BoardPage(ZoneId.of("Europe/Rome"));

    private static IssueCard card(IssueRecord record) {
        return card(record, "Title " + record.issueNumber());
    }

    private static IssueCard card(IssueRecord record, String title) {
        return new IssueCard(record.issueNumber(), title, Set.of("factory"), record, Optional.empty());
    }

    private static IssueRecord record(int number, IssueStatus status) {
        return IssueRecord.queued(number).withAgentSessionStarted().withStatus(status).withPhase(Phase.IMPLEMENT);
    }

    private static IssueRecord waitingRecord(int number, WaitReason reason) {
        return record(number, IssueStatus.WAITS_FOR_YOU).withWaitReason(reason);
    }

    private static IssueRecord liveRecord(int number, String link) {
        return waitingRecord(number, WaitReason.LIVE_CONVERSATION)
                .withLive(new LiveSession("abc", IssueStatus.IN_PROGRESS, Optional.empty(), Optional.of(link)));
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
        BoardSnapshot snapshot = new BoardSnapshot(NOW.minus(Duration.ofHours(2)), lastCheckAt, cards, 7);
        return new PageModel(snapshot, NOW, 2, 2, 2, IsolationMode.SAME_USER, POLL_INTERVAL, usageWindows,
                usageResumesAt, contexts, Set.of(), rememberedLabels, "MeterianHQ/target");
    }

    @Test
    void escapesTheTitleTheNoteAndTheReason() {
        IssueRecord record = waitingRecord(12, WaitReason.QUESTION).withNote("\"><img src=x onerror=alert(1)>");

        String html = page.renderBoard(model(List.of(card(record, "<script>alert(1)</script>"))));

        assertThat(html).doesNotContain("<script").doesNotContain("<img");
        assertThat(html).contains("&lt;script&gt;alert(1)&lt;/script&gt;");
        assertThat(html).contains("&quot;&gt;&lt;img src=x onerror=alert(1)&gt;");
        assertThat(html).contains("The agent has a question.");
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
        assertThat(html).contains(WaitReason.LIVE_CONVERSATION.sentence());
        assertThat(html).contains("<a href=\"" + LINK + "\">Remote Control</a>");
    }

    @Test
    void theWaitsForYouColumnIsFirst() {
        List<IssueCard> cards = List.of(
                card(record(3, IssueStatus.DONE)), card(record(4, IssueStatus.BLOCKED)),
                card(record(5, IssueStatus.QUEUED)), card(record(6, IssueStatus.IN_REVIEW)),
                card(record(7, IssueStatus.IN_PROGRESS)), card(waitingRecord(8, WaitReason.QUESTION)));

        String html = page.renderBoard(model(cards));

        assertThat(html).containsSubsequence("<h2>Waits for you</h2>", "#8 ", "<h2>In progress</h2>", "#7 ",
                "<h2>In review</h2>", "#6 ", "<h2>Queued</h2>", "#5 ", "<h2>Blocked</h2>", "#4 ",
                "<h2>Done</h2>", "#3 ");
    }

    @Test
    void sortsTheCardsByNumberAndTheDoneCardsNewestFirst() {
        IssueCard olderDone = new IssueCard(20, "Older", Set.of(), record(20, IssueStatus.DONE),
                Optional.of(NOW.minus(Duration.ofDays(2))));
        IssueCard newerDone = new IssueCard(10, "Newer", Set.of(), record(10, IssueStatus.DONE),
                Optional.of(NOW.minus(Duration.ofDays(1))));
        List<IssueCard> cards = List.of(card(record(9, IssueStatus.QUEUED)), card(record(2, IssueStatus.QUEUED)),
                olderDone, newerDone);

        String html = page.renderBoard(model(cards));

        assertThat(html).containsSubsequence("#2 ", "#9 ", "#10 ", "Done on 2026-11-01", "#20 ", "Done on 2026-10-31");
    }

    @Test
    void showsTheContextAsAPercent() {
        PageModel model = model(List.of(card(record(12, IssueStatus.IN_PROGRESS))), Optional.of(NOW), List.of(),
                Optional.empty(), Map.of(12, new ContextUsage(620_000, 1_000_000)));

        String html = page.renderBoard(model);

        assertThat(html).contains("<meter min=\"0\" max=\"100\" value=\"62\">");
        assertThat(html).contains("Context 62%");
        assertThat(html).doesNotContain("style=");
    }

    @Test
    void showsTheCardDetails() {
        IssueRecord record = record(12, IssueStatus.IN_REVIEW)
                .withReview(ReviewKind.CORRECTNESS, Verdict.APPROVE)
                .withReview(ReviewKind.SECURITY, Verdict.REJECT)
                .withFixLoop()
                .withRisk(RiskLevel.LOW, true)
                .withPullRequest(40)
                .withNote("First note.")
                .withNote("Newest note.");

        String html = page.renderBoard(model(List.of(card(record, "Fix the parser"))));

        assertThat(html).contains("<a href=\"https://github.com/MeterianHQ/target/issues/12\">#12 Fix the parser</a>");
        assertThat(html).contains("Phase: implement");
        assertThat(html).contains("Correctness: positive", "Security: negative", "Architecture: —", "Quality: —");
        assertThat(html).contains("Fix loops: 1 of 2", "Risk: low");
        assertThat(html).contains("<a href=\"https://github.com/MeterianHQ/target/pull/40\">Pull request #40</a>");
        assertThat(html).contains("Newest note.").doesNotContain("First note.");
    }

    @Test
    void showsOnlyThePossibleButtons() {
        IssueRecord record = waitingRecord(12, WaitReason.MERGE_NEEDS_APPROVAL).withPhase(Phase.MERGE);

        String html = page.renderBoard(model(List.of(card(record))));

        String state = "<input type=\"hidden\" name=\"state\" value=\"" + CardFingerprint.of(card(record)) + "\">";
        assertThat(html).contains(
                "<form method=\"post\" action=\"/issues/12/approve\">" + state + "<button>Approve</button></form>");
        assertThat(html).contains(
                "<form method=\"post\" action=\"/issues/12/enter\">" + state + "<button>Enter</button></form>");
        assertThat(html).contains("action=\"/issues/12/block\"").contains("<button>Block</button>");
        assertThat(html).doesNotContain("/issues/12/continue").doesNotContain("<button>Continue</button>");
    }

    @Test
    void aLabelThatThePageDidNotRequestShowsTheButton() {
        IssueRecord record = waitingRecord(12, WaitReason.MERGE_NEEDS_APPROVAL).withPhase(Phase.MERGE);
        IssueCard card = new IssueCard(12, "Title", Set.of("factory", "factory:approve"), record, Optional.empty());

        String html = page.renderBoard(model(List.of(card)));

        assertThat(html).contains("action=\"/issues/12/approve\"");
    }

    @Test
    void aLabelThatThePageRequestedHidesTheButton() {
        IssueRecord record = waitingRecord(12, WaitReason.MERGE_NEEDS_APPROVAL).withPhase(Phase.MERGE);
        IssueCard card = new IssueCard(12, "Title", Set.of("factory", "factory:approve"), record, Optional.empty());

        String html = page.renderBoard(modelWithRememberedLabels(List.of(card), Map.of(12, Set.of("factory:approve"))));

        assertThat(html).doesNotContain("action=\"/issues/12/approve\"").contains("action=\"/issues/12/block\"");
    }

    @Test
    void theBlockFormAsksForAConfirmWithoutAnInlineHandler() {
        String html = page.renderBoard(model(List.of(card(record(12, IssueStatus.IN_PROGRESS)))));

        assertThat(html).contains(
                "<form method=\"post\" action=\"/issues/12/block\" class=\"confirm-block\" data-issue=\"12\">");
        assertThat(html).doesNotContain("onsubmit").doesNotContain("onclick");
    }

    @Test
    void showsTheHealthOfTheWorker() {
        String html = page.renderBoard(model(List.of()));

        assertThat(html).contains("Worker runs since 2026-11-02 09:00");
        assertThat(html).contains("Last GitHub check: 2026-11-02 10:59");
        assertThat(html).contains("Issues in work: 2 of 2");
        assertThat(html).contains("Isolation: same-user");
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

        assertThat(html).contains("seven_day: 37% at 2026-11-02 10:50");
        assertThat(html).contains("five_hour: 95% at 2026-11-02 11:00");
        assertThat(html).contains("New phases start again at 2026-11-02 12:00.");
        assertThat(html).doesNotContain("$");
    }

    @Test
    void thePageWrapsTheBoardAndLinksOnlyItsOwnFiles() {
        PageModel model = model(List.of(card(record(12, IssueStatus.IN_PROGRESS))));

        String html = page.renderPage(model);

        assertThat(html).startsWith("<!DOCTYPE html>");
        assertThat(html).contains("<div id=\"board\">" + page.renderBoard(model) + "</div>");
        assertThat(html).contains("<link rel=\"stylesheet\" href=\"/page.css\">");
        assertThat(html).contains("<script src=\"/page.js\" defer></script>");
        assertThat(html).doesNotContain("style=").doesNotContain("<style");
    }
}
