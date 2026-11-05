package com.meterian.factory.web;

import com.meterian.factory.agent.UsageWindow;
import com.meterian.factory.board.BoardSnapshot;
import com.meterian.factory.board.IssueCard;
import com.meterian.factory.definition.ColumnNames;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Writes the HTML of the page and of its board. The board has the header and the columns, so that one
 * fetch of /board updates all the values. The page has no inline script and no inline style.
 */
public final class BoardPage {

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
                + "<meta name=\"color-scheme\" content=\"dark\">"
                + "<title>Factory</title>"
                + "<link rel=\"stylesheet\" href=\"/page.css\">"
                + "<script src=\"/page.js\" defer></script>"
                + "</head><body><div id=\"board\">" + renderBoard(model) + "</div></body></html>";
    }

    public String renderBoard(PageModel model) {
        StringBuilder html = new StringBuilder();
        appendHeader(html, model);
        html.append("<main class=\"columns\">");
        for (String column : findColumnsToShow(model)) {
            appendColumn(html, column, model);
        }
        html.append("</main>");
        return html.toString();
    }

    private void appendHeader(StringBuilder html, PageModel model) {
        html.append("<header class=\"topbar\"><div class=\"brand\"><h1>Claude AI Factory</h1>")
                .append("<span class=\"repository\">").append(Html.escape(model.repositoryFullName()))
                .append("</span></div>");
        appendHealth(html, model);
        html.append("</header>");
        model.definitionWarning().ifPresent(warning -> html.append("<p class=\"warning\">")
                .append(Html.escape(warning)).append("</p>"));
        if (isLastCheckOld(model)) {
            html.append("<p class=\"warning\">The last GitHub check is old.</p>");
        }
        if (model.pickUpPaused()) {
            html.append("<p class=\"notice\">Pick up is paused. New issues wait in Queued. "
                    + "The issues in work continue.</p>");
        }
        model.usageResumesAt().ifPresent(resumesAt -> html.append("<p class=\"notice\">")
                .append(Html.escape("New phases start again at " + formatTime(resumesAt) + "."))
                .append("</p>"));
    }

    private void appendHealth(StringBuilder html, PageModel model) {
        BoardSnapshot board = model.board();
        html.append("<div class=\"health\">");
        appendTile(html, "Worker runs since", formatTime(board.workerStartedAt()));
        appendTile(html, "Last GitHub check", board.lastCheckAt().map(this::formatTime).orElse("none yet"));
        appendTile(html, "Issues in work", model.issuesInWork() + " of " + model.parallelLimit());
        appendTile(html, "Isolation", describeIsolation(model));
        appendPickUpTile(html, model.pickUpPaused());
        model.usageWindows().forEach(window -> appendUsageTile(html, window));
        html.append("</div>");
    }

    private void appendTile(StringBuilder html, String label, String value) {
        html.append("<div class=\"tile\"><span class=\"tile-label\">").append(Html.escape(label))
                .append("</span><span class=\"tile-value\">").append(Html.escape(value)).append("</span></div>");
    }

    private void appendPickUpTile(StringBuilder html, boolean paused) {
        html.append("<div class=\"tile pick-up\"><span class=\"tile-label\">Pick up</span>")
                .append("<span class=\"tile-value\">").append(paused ? "Paused" : "On").append("</span>")
                .append("<form method=\"post\" action=\"/pick-up/").append(paused ? "resume" : "pause")
                .append("\"><button>").append(paused ? "Start" : "Pause").append("</button></form></div>");
    }

    /** The meter turns yellow at 70% and red at 90%, as the browser shows the low and high ranges. */
    private void appendUsageTile(StringBuilder html, UsageWindow window) {
        long percent = Math.clamp(Math.round(window.utilization() * 100), 0, 100);
        html.append("<div class=\"tile usage\"><span class=\"tile-label\">")
                .append(Html.escape(describeWindowName(window.name())))
                .append("</span><span class=\"tile-value\"><meter min=\"0\" max=\"100\" low=\"70\" high=\"90\" ")
                .append("optimum=\"0\" value=\"").append(percent).append("\"></meter> ").append(percent)
                .append("%</span><span class=\"tile-note\">at ").append(formatTime(window.reportedAt()))
                .append("</span></div>");
    }

    private String describeWindowName(String windowName) {
        return switch (windowName) {
            case "five_hour" -> "5-hour usage";
            case "seven_day" -> "Weekly usage";
            default -> windowName;
        };
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

    /** The columns of the definition, then an extra column for each other column of a card, by issue number. */
    private List<String> findColumnsToShow(PageModel model) {
        List<String> columns = new ArrayList<>(model.columns());
        model.board().cards().stream()
                .sorted(Comparator.comparingInt(IssueCard::issueNumber))
                .map(card -> card.record().column())
                .filter(column -> !columns.contains(column))
                .forEach(columns::add);
        return columns;
    }

    private void appendColumn(StringBuilder html, String column, PageModel model) {
        List<IssueCard> cards = findCardsOfColumn(column, model.board());
        html.append("<section class=\"column status-").append(Html.escape(column)).append("\"><h2>")
                .append("<span class=\"column-name\">").append(Html.escape(ColumnNames.displayName(column)))
                .append("</span>")
                .append("<span class=\"count\">").append(cards.size()).append("</span></h2>")
                .append("<div class=\"cards\">");
        if (cards.isEmpty()) {
            html.append("<p class=\"empty\">No issues</p>");
        }
        for (IssueCard card : cards) {
            html.append(cardRenderer.render(card, model));
        }
        html.append("</div></section>");
    }

    /** A card with a done date goes first, the newest first. The other cards go by issue number. */
    private List<IssueCard> findCardsOfColumn(String column, BoardSnapshot board) {
        Comparator<IssueCard> newestDoneFirst = Comparator.comparing(
                (IssueCard card) -> card.doneAt().orElse(Instant.MIN)).reversed();
        Comparator<IssueCard> order = Comparator.comparing((IssueCard card) -> card.doneAt().isEmpty())
                .thenComparing(newestDoneFirst)
                .thenComparingInt(IssueCard::issueNumber);
        return board.cards().stream()
                .filter(card -> card.record().column().equals(column))
                .sorted(order)
                .toList();
    }

    private String formatTime(Instant instant) {
        return timeFormat.format(instant);
    }
}
