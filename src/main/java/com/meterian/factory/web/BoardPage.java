package com.meterian.factory.web;

import com.meterian.factory.agent.UsageWindow;
import com.meterian.factory.board.BoardSnapshot;
import com.meterian.factory.board.IssueCard;
import com.meterian.factory.state.IssueStatus;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Writes the HTML of the page and of its board. The board has the header and the columns, so that one
 * fetch of /board updates all the values. The page has no inline script and no inline style.
 */
public final class BoardPage {

    /** "Waits for you" is first, so that it is at the top on the phone. */
    private static final List<IssueStatus> COLUMNS = List.of(IssueStatus.WAITS_FOR_YOU, IssueStatus.IN_PROGRESS,
            IssueStatus.IN_REVIEW, IssueStatus.QUEUED, IssueStatus.BLOCKED, IssueStatus.DONE);
    private static final int OLD_CHECK_POLL_INTERVALS = 3;

    private final DateTimeFormatter timeFormat;
    private final CardRenderer cardRenderer;

    public BoardPage(ZoneId zone) {
        this.timeFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(zone);
        this.cardRenderer = new CardRenderer(zone);
    }

    public String renderPage(PageModel model) {
        return "<!DOCTYPE html>"
                + "<html lang=\"en\"><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
                + "<title>Factory</title>"
                + "<link rel=\"stylesheet\" href=\"/page.css\">"
                + "<script src=\"/page.js\" defer></script>"
                + "</head><body><div id=\"board\">" + renderBoard(model) + "</div></body></html>";
    }

    public String renderBoard(PageModel model) {
        StringBuilder html = new StringBuilder();
        appendHeader(html, model);
        html.append("<main class=\"columns\">");
        for (IssueStatus status : COLUMNS) {
            appendColumn(html, status, model);
        }
        html.append("</main>");
        return html.toString();
    }

    private void appendHeader(StringBuilder html, PageModel model) {
        BoardSnapshot board = model.board();
        html.append("<header>");
        appendLine(html, "Worker runs since " + formatTime(board.workerStartedAt()));
        appendLine(html, "Last GitHub check: " + board.lastCheckAt().map(this::formatTime).orElse("none yet"));
        if (isLastCheckOld(model)) {
            html.append("<p class=\"warning\">The last GitHub check is old.</p>");
        }
        appendLine(html, "Issues in work: " + model.issuesInWork() + " of " + model.parallelLimit());
        appendLine(html, "Isolation: " + describeIsolation(model));
        model.usageWindows().forEach(window -> appendLine(html, describeUsageWindow(window)));
        model.usageResumesAt().ifPresent(resumesAt ->
                appendLine(html, "New phases start again at " + formatTime(resumesAt) + "."));
        html.append("</header>");
    }

    /** Without a check, the start of the worker is the reference time. */
    private boolean isLastCheckOld(PageModel model) {
        Instant lastCheck = model.board().lastCheckAt().orElse(model.board().workerStartedAt());
        Duration limit = model.pollInterval().multipliedBy(OLD_CHECK_POLL_INTERVALS);
        return Duration.between(lastCheck, model.renderedAt()).compareTo(limit) > 0;
    }

    private String describeIsolation(PageModel model) {
        return model.isolationMode().name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    private String describeUsageWindow(UsageWindow window) {
        long percent = Math.round(window.utilization() * 100);
        return window.name() + ": " + percent + "% at " + formatTime(window.reportedAt());
    }

    private void appendColumn(StringBuilder html, IssueStatus status, PageModel model) {
        html.append("<section class=\"column\"><h2>").append(Html.escape(status.displayName())).append("</h2>");
        for (IssueCard card : findCardsOfColumn(status, model.board())) {
            html.append(cardRenderer.render(card, model));
        }
        html.append("</section>");
    }

    /** The cards go by issue number. The Done cards go by the date of the merge, the newest first. */
    private List<IssueCard> findCardsOfColumn(IssueStatus status, BoardSnapshot board) {
        Comparator<IssueCard> order = status == IssueStatus.DONE
                ? Comparator.comparing((IssueCard card) -> card.doneAt().orElse(Instant.MIN)).reversed()
                : Comparator.comparingInt(IssueCard::issueNumber);
        return board.cards().stream()
                .filter(card -> card.record().status() == status)
                .sorted(order)
                .toList();
    }

    private void appendLine(StringBuilder html, String text) {
        html.append("<p>").append(Html.escape(text)).append("</p>");
    }

    private String formatTime(Instant instant) {
        return timeFormat.format(instant);
    }
}
