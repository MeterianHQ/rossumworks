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
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

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
        html.append("<div class=\"card-head\"><span class=\"phase\">").append(toLowerCase(record.phase().name()))
                .append("</span></div>");
        appendTitle(html, card, model.repositoryFullName());
        List<PageAction> pendingRequests = findPendingRequests(card, model);
        appendRequests(html, pendingRequests);
        if (waits && pendingRequests.isEmpty()) {
            appendReason(html, record);
        }
        appendRemoteControlLink(html, record);
        appendReviews(html, record);
        appendFacts(html, record, model.fixLoopLimit());
        appendContext(html, Optional.ofNullable(model.contexts().get(card.issueNumber())));
        appendPullRequestLink(html, record, model.repositoryFullName());
        appendNewestNote(html, record);
        card.doneAt().ifPresent(doneAt -> appendDoneDate(html, doneAt));
        appendActions(html, card, model.continueRequested().contains(card.issueNumber()),
                model.findRememberedLabels(card.issueNumber()));
        html.append("</article>");
        return html.toString();
    }

    private void appendTitle(StringBuilder html, IssueCard card, String repositoryFullName) {
        String issueLink = "https://github.com/" + repositoryFullName + "/issues/" + card.issueNumber();
        html.append("<h3>");
        appendExternalLink(html, issueLink, "#" + card.issueNumber() + " " + card.title());
        html.append("</h3>");
    }

    /** A link to another site opens in a new tab, and that site gets no access to the page and no referrer. */
    private void appendExternalLink(StringBuilder html, String address, String text) {
        html.append("<a href=\"").append(Html.escape(address))
                .append("\" target=\"_blank\" rel=\"noopener noreferrer\">").append(Html.escape(text)).append("</a>");
    }

    private static List<PageAction> findPendingRequests(IssueCard card, PageModel model) {
        boolean continueRequested = model.continueRequested().contains(card.issueNumber());
        Set<String> rememberedLabels = model.findRememberedLabels(card.issueNumber());
        return Arrays.stream(PageAction.values())
                .filter(action -> action.isRequestPending(card, continueRequested, rememberedLabels))
                .toList();
    }

    /** A request of the page replaces the reason of the wait, so that the owner sees that the click arrived. */
    private void appendRequests(StringBuilder html, List<PageAction> pendingRequests) {
        for (PageAction action : pendingRequests) {
            action.requestSentence().ifPresent(sentence ->
                    html.append("<p class=\"request\">").append(Html.escape(sentence)).append("</p>"));
        }
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
            appendExternalLink(html, link.get(), "Remote Control");
        } else {
            html.append("Remote Control: ").append(Html.escape(link.get()));
        }
        html.append("</p>");
    }

    /** Each review is a chip. Its class gives the color, and its title gives the result in words. */
    private void appendReviews(StringBuilder html, IssueRecord record) {
        html.append("<ul class=\"reviews\">");
        for (ReviewKind kind : ReviewKind.values()) {
            Optional<String> result = Optional.ofNullable(record.reviews().get(kind)).map(this::describeVerdict);
            String name = capitalize(kind.name());
            html.append("<li class=\"review ").append(result.orElse("none")).append("\" title=\"").append(name)
                    .append(": ").append(result.orElse(NO_VALUE)).append("\">").append(name).append("</li>");
        }
        html.append("</ul>");
    }

    private String describeVerdict(Verdict verdict) {
        return verdict == Verdict.APPROVE ? "positive" : "negative";
    }

    private void appendFacts(StringBuilder html, IssueRecord record, int fixLoopLimit) {
        String risk = record.risk().map(level -> toLowerCase(level.name())).orElse(NO_VALUE);
        String riskClass = record.risk().map(level -> " class=\"risk-" + toLowerCase(level.name()) + "\"").orElse("");
        html.append("<dl class=\"facts\"><div><dt>Fix loops</dt><dd>").append(record.fixLoops()).append(" of ")
                .append(fixLoopLimit).append("</dd></div><div><dt>Risk</dt><dd").append(riskClass).append('>')
                .append(risk).append("</dd></div></dl>");
    }

    /** The policy of the page forbids an inline style, so the bar is a meter element. */
    private void appendContext(StringBuilder html, Optional<ContextUsage> context) {
        if (context.isEmpty()) {
            return;
        }
        long percent = Math.clamp(Math.round(context.get().fraction() * 100), 0, 100);
        html.append("<p class=\"context\"><span class=\"context-label\">Context</span>")
                .append("<meter min=\"0\" max=\"100\" value=\"").append(percent).append("\"></meter>")
                .append("<span class=\"context-value\">").append(percent).append("%</span></p>");
    }

    private void appendPullRequestLink(StringBuilder html, IssueRecord record, String repositoryFullName) {
        record.pullRequestNumber().ifPresent(number -> {
            String pullRequestLink = "https://github.com/" + repositoryFullName + "/pull/" + number;
            html.append("<p class=\"pull-request\">");
            appendExternalLink(html, pullRequestLink, "Pull request #" + number);
            html.append("</p>");
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

    /**
     * The forms work without JavaScript. page.js finds the Block form by its class and asks for a confirm.
     * Each form sends the fingerprint of the card, so that the server refuses a form from an old page.
     */
    private void appendActions(StringBuilder html, IssueCard card, boolean continueRequested,
            Set<String> rememberedLabels) {
        String fingerprint = CardFingerprint.of(card);
        html.append("<div class=\"actions\">");
        for (PageAction action : PageAction.values()) {
            // The card does not know the viewer yet: the server still refuses a terminal action from another device.
            if (action.isPossible(card, continueRequested, rememberedLabels, true)) {
                appendActionForm(html, card.issueNumber(), action, fingerprint);
            }
        }
        html.append("</div>");
    }

    private void appendActionForm(StringBuilder html, int issueNumber, PageAction action, String fingerprint) {
        html.append("<form method=\"post\" action=\"/issues/").append(issueNumber).append('/')
                .append(action.pathName()).append('"');
        if (action == PageAction.BLOCK) {
            html.append(" class=\"confirm-block\" data-issue=\"").append(issueNumber).append('"');
        }
        html.append("><input type=\"hidden\" name=\"").append(CardFingerprint.FIELD_NAME).append("\" value=\"")
                .append(fingerprint).append("\"><button>").append(action.buttonText()).append("</button></form>");
    }

    private static String capitalize(String constantName) {
        String lowerCase = toLowerCase(constantName);
        return Character.toUpperCase(lowerCase.charAt(0)) + lowerCase.substring(1);
    }

    private static String toLowerCase(String constantName) {
        return constantName.toLowerCase(Locale.ROOT);
    }
}
