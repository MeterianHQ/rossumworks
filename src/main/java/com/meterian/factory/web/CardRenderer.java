package com.meterian.factory.web;

import com.meterian.factory.agent.ContextUsage;
import com.meterian.factory.agent.TmuxLiveSessions;
import com.meterian.factory.board.IssueCard;
import com.meterian.factory.state.IssueRecord;
import com.meterian.factory.state.IssueStatus;
import com.meterian.factory.state.ReviewKind;
import com.meterian.factory.state.Verdict;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Optional;

/** Writes the HTML of one card. All text from GitHub and from the agents goes through {@link Html#escape}. */
final class CardRenderer {

    private static final String NO_VALUE = "—";

    private final DateTimeFormatter dateFormat;

    CardRenderer(ZoneId zone) {
        this.dateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(zone);
    }

    String render(IssueCard card, PageModel model) {
        IssueRecord record = card.record();
        boolean waits = record.status() == IssueStatus.WAITS_FOR_YOU;
        StringBuilder html = new StringBuilder();
        html.append("<article class=\"").append(waits ? "card waits" : "card").append("\">");
        appendTitle(html, card, model.repositoryFullName());
        if (waits) {
            appendReason(html, record);
        }
        appendRemoteControlLink(html, record);
        appendLine(html, "Phase: " + toLowerCase(record.phase().name()));
        appendReviews(html, record);
        appendLine(html, "Fix loops: " + record.fixLoops() + " of " + model.fixLoopLimit());
        appendLine(html, "Risk: " + record.risk().map(risk -> toLowerCase(risk.name())).orElse(NO_VALUE));
        appendContext(html, Optional.ofNullable(model.contexts().get(card.issueNumber())));
        appendPullRequestLink(html, record, model.repositoryFullName());
        appendNewestNote(html, record);
        card.doneAt().ifPresent(doneAt -> appendDoneDate(html, doneAt));
        appendActions(html, card, model.continueRequested().contains(card.issueNumber()));
        html.append("</article>");
        return html.toString();
    }

    private void appendTitle(StringBuilder html, IssueCard card, String repositoryFullName) {
        String issueLink = "https://github.com/" + repositoryFullName + "/issues/" + card.issueNumber();
        html.append("<h3><a href=\"").append(Html.escape(issueLink)).append("\">#").append(card.issueNumber())
                .append(' ').append(Html.escape(card.title())).append("</a></h3>");
    }

    private void appendReason(StringBuilder html, IssueRecord record) {
        record.waitReason().ifPresent(reason ->
                html.append("<p class=\"reason\">").append(Html.escape(reason.sentence())).append("</p>"));
    }

    /** Only a full match of the Remote Control pattern becomes a link. Any other value is text. */
    private void appendRemoteControlLink(StringBuilder html, IssueRecord record) {
        Optional<String> link = record.live().flatMap(live -> live.link());
        if (link.isEmpty()) {
            return;
        }
        html.append("<p class=\"live\">");
        if (TmuxLiveSessions.isRemoteControlLink(link.get())) {
            html.append("<a href=\"").append(Html.escape(link.get())).append("\">Remote Control</a>");
        } else {
            html.append("Remote Control: ").append(Html.escape(link.get()));
        }
        html.append("</p>");
    }

    private void appendReviews(StringBuilder html, IssueRecord record) {
        html.append("<ul class=\"reviews\">");
        for (ReviewKind kind : ReviewKind.values()) {
            String result = Optional.ofNullable(record.reviews().get(kind)).map(this::describeVerdict).orElse(NO_VALUE);
            html.append("<li>").append(capitalize(kind.name())).append(": ").append(result).append("</li>");
        }
        html.append("</ul>");
    }

    private String describeVerdict(Verdict verdict) {
        return verdict == Verdict.APPROVE ? "positive" : "negative";
    }

    /** The policy of the page forbids an inline style, so the bar is a meter element. */
    private void appendContext(StringBuilder html, Optional<ContextUsage> context) {
        if (context.isEmpty()) {
            return;
        }
        long percent = Math.clamp(Math.round(context.get().fraction() * 100), 0, 100);
        html.append("<p class=\"context\"><meter min=\"0\" max=\"100\" value=\"").append(percent)
                .append("\"></meter> Context ").append(percent).append("%</p>");
    }

    private void appendPullRequestLink(StringBuilder html, IssueRecord record, String repositoryFullName) {
        record.pullRequestNumber().ifPresent(number -> {
            String pullRequestLink = "https://github.com/" + repositoryFullName + "/pull/" + number;
            html.append("<p><a href=\"").append(Html.escape(pullRequestLink)).append("\">Pull request #")
                    .append(number).append("</a></p>");
        });
    }

    private void appendNewestNote(StringBuilder html, IssueRecord record) {
        if (!record.notes().isEmpty()) {
            String newestNote = record.notes().get(record.notes().size() - 1);
            html.append("<p class=\"note\">").append(Html.escape(newestNote)).append("</p>");
        }
    }

    private void appendDoneDate(StringBuilder html, Instant doneAt) {
        html.append("<p class=\"done\">Done on ").append(dateFormat.format(doneAt)).append("</p>");
    }

    /** The forms work without JavaScript. page.js finds the Block form by its class and asks for a confirm. */
    private void appendActions(StringBuilder html, IssueCard card, boolean continueRequested) {
        html.append("<div class=\"actions\">");
        for (PageAction action : PageAction.values()) {
            if (action.isPossible(card, continueRequested)) {
                appendActionForm(html, card.issueNumber(), action);
            }
        }
        html.append("</div>");
    }

    private void appendActionForm(StringBuilder html, int issueNumber, PageAction action) {
        html.append("<form method=\"post\" action=\"/issues/").append(issueNumber).append('/')
                .append(action.pathName()).append('"');
        if (action == PageAction.BLOCK) {
            html.append(" class=\"confirm-block\" data-issue=\"").append(issueNumber).append('"');
        }
        html.append("><button>").append(action.buttonText()).append("</button></form>");
    }

    private void appendLine(StringBuilder html, String text) {
        html.append("<p>").append(Html.escape(text)).append("</p>");
    }

    private static String capitalize(String constantName) {
        String lowerCase = toLowerCase(constantName);
        return Character.toUpperCase(lowerCase.charAt(0)) + lowerCase.substring(1);
    }

    private static String toLowerCase(String constantName) {
        return constantName.toLowerCase(Locale.ROOT);
    }
}
