package com.meterian.factory.web;

import com.meterian.factory.agent.ContextUsage;
import com.meterian.factory.agent.TmuxLiveSessions;
import com.meterian.factory.board.IssueCard;
import com.meterian.factory.state.IssueRecord;
import com.meterian.factory.state.IssueStatus;
import com.meterian.factory.state.LivePlace;
import com.meterian.factory.state.ReviewKind;
import com.meterian.factory.state.Verdict;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/** Writes the HTML of one card. All text from GitHub and from the agents goes through {@link Html#escape}. */
final class CardRenderer {

    private static final String NO_VALUE = "—";
    private static final String BROWSER_TOOLTIP = "Opens the same conversation in a new tab of this browser.";
    private static final String GITHUB_ENTER_SENTENCE = "Enter received. The worker stops the current step,"
            + " and the link goes to a comment of the issue in a few seconds.";
    private static final List<PageAction> ENTER_ACTIONS = List.of(PageAction.ENTER_TERMINAL, PageAction.ENTER_BROWSER);
    private static final List<PageAction> OTHER_ACTIONS =
            List.of(PageAction.APPROVE, PageAction.BLOCK, PageAction.START_AGAIN, PageAction.CONTINUE);

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
        List<String> requestLines = findPendingRequestLines(card, model);
        appendRequests(html, requestLines);
        if (waits && requestLines.isEmpty()) {
            appendReason(html, record);
        }
        appendLiveBox(html, card, model);
        appendReviews(html, record);
        appendFacts(html, record, model.fixLoopLimit());
        appendContext(html, Optional.ofNullable(model.contexts().get(card.issueNumber())));
        appendPullRequestLink(html, record, model.repositoryFullName());
        appendNewestNote(html, record);
        card.doneAt().ifPresent(doneAt -> appendDoneDate(html, doneAt));
        appendActions(html, card, model);
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
        appendExternalLinkStart(html, address, "");
        html.append(Html.escape(text)).append("</a>");
    }

    /** The attributes come after the attributes of the new tab, and they must be escaped already. */
    private static void appendExternalLinkStart(StringBuilder html, String address, String attributes) {
        html.append("<a href=\"").append(Html.escape(address))
                .append("\" target=\"_blank\" rel=\"noopener noreferrer\"").append(attributes).append('>');
    }

    /**
     * The two Enter buttons share one label, so only the button of the remembered place shows its line. An Enter
     * from the GitHub app has the place GITHUB_COMMENT and no button, so it gets its own line.
     */
    private static List<String> findPendingRequestLines(IssueCard card, PageModel model) {
        boolean continueRequested = model.continueRequested().contains(card.issueNumber());
        Set<String> rememberedLabels = model.findRememberedLabels(card.issueNumber());
        Optional<LivePlace> enterPlace = model.findEnterPlace(card.issueNumber());
        List<String> lines = new ArrayList<>(Arrays.stream(PageAction.values())
                .filter(action -> action.enterPlace().isEmpty() || action.enterPlace().equals(enterPlace))
                .filter(action -> action.isRequestPending(card, continueRequested, rememberedLabels))
                .flatMap(action -> action.requestSentence().stream())
                .toList());
        if (enterPlace.equals(Optional.of(LivePlace.GITHUB_COMMENT))
                && PageAction.isEnterRequestPending(card, continueRequested, rememberedLabels)) {
            lines.addFirst(GITHUB_ENTER_SENTENCE);
        }
        return lines;
    }

    /** A request of the page replaces the reason of the wait, so that the owner sees that the click arrived. */
    private void appendRequests(StringBuilder html, List<String> requestLines) {
        for (String line : requestLines) {
            html.append("<p class=\"request\">").append(Html.escape(line)).append("</p>");
        }
    }

    private void appendReason(StringBuilder html, IssueRecord record) {
        record.waitReason().ifPresent(reason ->
                html.append("<p class=\"reason\">").append(Html.escape(reason.sentence())).append("</p>"));
    }

    /** The box shows where the owner opened the live conversation, and the places where it opens again. */
    private void appendLiveBox(StringBuilder html, IssueCard card, PageModel model) {
        Optional<String> link = card.record().live().flatMap(live -> live.link());
        if (link.isEmpty()) {
            return;
        }
        LivePlace chosenPlace = card.record().live().orElseThrow().place();
        String openGroup = createOpenGroup(card, model, link.get(), chosenPlace);
        html.append("<div class=\"live-box\"><p class=\"live-title\">Live conversation open</p>")
                .append("<p class=\"open-label\">").append(describeChosenPlace(chosenPlace));
        if (!openGroup.isEmpty()) {
            html.append(" Open it again or somewhere else:");
        }
        html.append("</p>");
        if (!TmuxLiveSessions.isRemoteControlLink(link.get())) {
            html.append("<p class=\"open-label\">Remote Control: ").append(Html.escape(link.get())).append("</p>");
        }
        if (!openGroup.isEmpty()) {
            html.append("<div class=\"open-group\">").append(openGroup).append("</div>");
        }
        html.append("</div>");
    }

    private static String describeChosenPlace(LivePlace place) {
        return switch (place) {
            case TERMINAL -> "You opened it in a terminal on this machine.";
            case BROWSER -> "You opened it in a tab of this browser.";
            case GITHUB_COMMENT -> "The link is in a comment of the issue on GitHub.";
        };
    }

    /** Only a full match of the Remote Control pattern becomes the Browser link. Any other value is text. */
    private String createOpenGroup(IssueCard card, PageModel model, String link, LivePlace chosenPlace) {
        StringBuilder group = new StringBuilder();
        String fingerprint = CardFingerprint.of(card);
        if (isPossible(PageAction.OPEN_TERMINAL, card, model)) {
            appendPlaceForm(group, card.issueNumber(), PageAction.OPEN_TERMINAL, chosenPlace == LivePlace.TERMINAL,
                    fingerprint);
        }
        if (TmuxLiveSessions.isRemoteControlLink(link)) {
            appendExternalLinkStart(group, link, createPlaceAttributes(chosenPlace == LivePlace.BROWSER,
                    "Browser", BROWSER_TOOLTIP));
            group.append("Browser</a>");
        }
        return group.toString();
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
     * The forms work without JavaScript. page.js finds the forms of Block and Start again by their class and asks for a confirm.
     * Each form sends the fingerprint of the card, so that the server refuses a form from an old page.
     * The Enter buttons are one group before the other forms.
     */
    private void appendActions(StringBuilder html, IssueCard card, PageModel model) {
        String fingerprint = CardFingerprint.of(card);
        List<PageAction> enterActions = ENTER_ACTIONS.stream()
                .filter(action -> isPossible(action, card, model))
                .toList();
        html.append("<div class=\"actions\">");
        if (!enterActions.isEmpty()) {
            html.append("<div class=\"enter-group\">");
            for (PageAction action : enterActions) {
                appendPlaceForm(html, card.issueNumber(), action, false, fingerprint);
            }
            html.append("</div><span class=\"separator\"></span>");
        }
        for (PageAction action : OTHER_ACTIONS) {
            if (isPossible(action, card, model)) {
                appendFormStart(html, card.issueNumber(), action, fingerprint);
                html.append("<button>").append(action.buttonText()).append("</button></form>");
            }
        }
        html.append("</div>");
    }

    private static boolean isPossible(PageAction action, IssueCard card, PageModel model) {
        return action.isPossible(card, model.continueRequested().contains(card.issueNumber()),
                model.findRememberedLabels(card.issueNumber()), model.viewerOnThisMachine());
    }

    /** A button of a place has a tooltip. Its accessible name has the name of the button and the tooltip. */
    private void appendPlaceForm(StringBuilder html, int issueNumber, PageAction action, boolean current,
            String fingerprint) {
        appendFormStart(html, issueNumber, action, fingerprint);
        html.append("<button").append(createPlaceAttributes(current, action.buttonText(), action.tooltip()))
                .append('>').append(action.buttonText()).append("</button></form>");
    }

    /** The form of Enter in Browser opens the wait page in a new tab, so that the browser does not block it. */
    private void appendFormStart(StringBuilder html, int issueNumber, PageAction action, String fingerprint) {
        html.append("<form method=\"post\" action=\"/issues/").append(issueNumber).append('/')
                .append(action.pathName()).append('"');
        if (action == PageAction.BLOCK) {
            html.append(" class=\"confirm-block\" data-issue=\"").append(issueNumber).append('"');
        }
        if (action == PageAction.START_AGAIN) {
            html.append(" class=\"confirm-start-again\" data-issue=\"").append(issueNumber).append('"');
        }
        if (action == PageAction.ENTER_BROWSER) {
            html.append(" target=\"_blank\"");
        }
        html.append("><input type=\"hidden\" name=\"").append(CardFingerprint.FIELD_NAME).append("\" value=\"")
                .append(fingerprint).append("\">");
    }

    private static String createPlaceAttributes(boolean current, String name, String tooltip) {
        String tooltipText = Html.escape(tooltip);
        return (current ? " class=\"current\"" : "") + " data-tip=\"" + tooltipText + "\" aria-label=\""
                + Html.escape(name) + ". " + tooltipText + "\"";
    }

    private static String capitalize(String constantName) {
        String lowerCase = toLowerCase(constantName);
        return Character.toUpperCase(lowerCase.charAt(0)) + lowerCase.substring(1);
    }

    private static String toLowerCase(String constantName) {
        return constantName.toLowerCase(Locale.ROOT);
    }
}
