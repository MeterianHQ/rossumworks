package com.meterian.factory.web;

import com.meterian.factory.agent.ContextUsage;
import com.meterian.factory.agent.TmuxLiveSessions;
import com.meterian.factory.board.IssueCard;
import com.meterian.factory.state.ActionType;
import com.meterian.factory.state.IssueRecord;
import com.meterian.factory.state.LivePlace;
import com.meterian.factory.state.Verdict;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/** Writes the HTML of one card. All text from GitHub and from the agents goes through {@link Html#escape}. */
final class CardRenderer {

    private static final String NO_VALUE = "—";
    private static final String BROWSER_TOOLTIP = "Opens the same conversation in a new tab of this browser.";
    private static final String GITHUB_ENTER_SENTENCE = "Enter received. The worker stops the current action,"
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
        boolean waits = record.action().filter(ActionType::waitsForYou).isPresent();
        List<String> requestLines = findPendingRequestLines(card, model);
        boolean suspended = !requestLines.isEmpty();
        StringBuilder html = new StringBuilder();
        html.append("<article class=\"").append(describeCardClass(waits, suspended)).append("\">");
        html.append("<div class=\"card-head\"><span class=\"phase\">").append(Html.escape(record.state()))
                .append("</span></div>");
        appendTitle(html, card, model.repositoryFullName());
        appendRequests(html, requestLines);
        boolean showsTheReason = waits && !suspended;
        if (showsTheReason) {
            appendReason(html, record);
        }
        appendLiveBox(html, card, model, suspended);
        appendReviews(html, record, model.reviewNamesOf(record.issueNumber()));
        appendFacts(html, record, model.limitsOf(record.issueNumber()));
        appendContext(html, Optional.ofNullable(model.contexts().get(card.issueNumber())));
        appendPullRequestLink(html, record, model.repositoryFullName());
        if (!showsTheReason) {
            appendNewestNote(html, record);
        }
        card.doneAt().ifPresent(doneAt -> appendDoneDate(html, doneAt));
        appendActions(html, card, model, suspended);
        html.append("</article>");
        return html.toString();
    }

    /** A card with a sent request is suspended: it is gray, and its buttons are disabled until the worker acts. */
    private static String describeCardClass(boolean waits, boolean suspended) {
        String cardClass = waits ? "card waits" : "card";
        return suspended ? cardClass + " pending" : cardClass;
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
        boolean inBlockTarget = model.isInBlockTarget(card.issueNumber());
        List<String> lines = new ArrayList<>(Arrays.stream(PageAction.values())
                .filter(action -> action.enterPlace().isEmpty() || action.enterPlace().equals(enterPlace))
                .filter(action -> action.isRequestPending(card, continueRequested, rememberedLabels, inBlockTarget))
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

    /** The newest note of a waiting card tells why it waits. */
    private void appendReason(StringBuilder html, IssueRecord record) {
        record.newestNote().ifPresent(note ->
                html.append("<p class=\"reason\">").append(Html.escape(note)).append("</p>"));
    }

    /** The box shows where the owner opened the live conversation, and the places where it opens again. */
    private void appendLiveBox(StringBuilder html, IssueCard card, PageModel model, boolean suspended) {
        Optional<String> link = card.record().live().flatMap(live -> live.link());
        if (link.isEmpty()) {
            return;
        }
        LivePlace chosenPlace = card.record().live().orElseThrow().place();
        String openGroup = createOpenGroup(card, model, link.get(), chosenPlace, suspended);
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
    private String createOpenGroup(IssueCard card, PageModel model, String link, LivePlace chosenPlace,
            boolean suspended) {
        StringBuilder group = new StringBuilder();
        String fingerprint = CardFingerprint.of(card);
        if (isPossible(PageAction.OPEN_TERMINAL, card, model)) {
            appendPlaceForm(group, card.issueNumber(), PageAction.OPEN_TERMINAL, chosenPlace == LivePlace.TERMINAL,
                    fingerprint, suspended);
        }
        if (TmuxLiveSessions.isRemoteControlLink(link)) {
            appendExternalLinkStart(group, link, createPlaceAttributes(chosenPlace == LivePlace.BROWSER,
                    "Browser", BROWSER_TOOLTIP));
            group.append("Browser</a>");
        }
        return group.toString();
    }

    /** Each review is a chip. Its class gives the color, and its title gives the result in words. */
    private void appendReviews(StringBuilder html, IssueRecord record, List<String> reviewNames) {
        html.append("<ul class=\"reviews\">");
        for (String reviewName : reviewNames) {
            Optional<String> result = Optional.ofNullable(record.reviews().get(reviewName)).map(this::describeVerdict);
            String name = Html.escape(capitalize(reviewName));
            html.append("<li class=\"review ").append(result.orElse("none")).append("\" title=\"").append(name)
                    .append(": ").append(result.orElse(NO_VALUE)).append("\">").append(name).append("</li>");
        }
        html.append("</ul>");
    }

    private String describeVerdict(Verdict verdict) {
        return verdict == Verdict.APPROVE ? "positive" : "negative";
    }

    private void appendFacts(StringBuilder html, IssueRecord record, Map<String, Integer> limits) {
        html.append("<p class=\"entries\" title=\"Entries into each state with a limit\">")
                .append(Html.escape(describeShortEntries(record, limits)))
                .append("</p>");
        appendRiskLine(html, record);
    }

    /** The short form "implement 1/3" keeps the entries on one line of the card. */
    private String describeShortEntries(IssueRecord record, Map<String, Integer> limits) {
        if (limits.isEmpty()) {
            return NO_VALUE;
        }
        return limits.entrySet().stream()
                .map(limit -> limit.getKey() + " " + record.entriesOf(limit.getKey()) + "/" + limit.getValue())
                .collect(Collectors.joining(", "));
    }

    /** The reasons of the risk check are in the status comment of the issue, so the card shows only the line. */
    private void appendRiskLine(StringBuilder html, IssueRecord record) {
        record.risk().ifPresent(level -> {
            String levelName = toLowerCase(level.name());
            String size = record.small() ? "small" : "not small";
            html.append("<p class=\"risk-line risk-").append(levelName).append("\">Risk: ").append(levelName)
                    .append(", ").append(size).append("</p>");
        });
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
    private void appendActions(StringBuilder html, IssueCard card, PageModel model, boolean suspended) {
        String fingerprint = CardFingerprint.of(card);
        List<PageAction> enterActions = ENTER_ACTIONS.stream()
                .filter(action -> isPossible(action, card, model))
                .toList();
        html.append("<div class=\"actions\">");
        if (!enterActions.isEmpty()) {
            html.append("<div class=\"enter-group\">");
            for (PageAction action : enterActions) {
                appendPlaceForm(html, card.issueNumber(), action, false, fingerprint, suspended);
            }
            html.append("</div><span class=\"separator\"></span>");
        }
        for (PageAction action : OTHER_ACTIONS) {
            if (isPossible(action, card, model)) {
                appendFormStart(html, card.issueNumber(), action, fingerprint);
                html.append(suspended ? "<button disabled>" : "<button>").append(action.buttonText())
                        .append("</button></form>");
            }
        }
        html.append("</div>");
    }

    private static boolean isPossible(PageAction action, IssueCard card, PageModel model) {
        return action.isPossible(card, model.continueRequested().contains(card.issueNumber()),
                model.findRememberedLabels(card.issueNumber()), model.viewerOnThisMachine(),
                model.isInBlockTarget(card.issueNumber()));
    }

    /** A button of a place has a tooltip. Its accessible name has the name of the button and the tooltip. */
    private void appendPlaceForm(StringBuilder html, int issueNumber, PageAction action, boolean current,
            String fingerprint, boolean suspended) {
        appendFormStart(html, issueNumber, action, fingerprint);
        html.append("<button").append(createPlaceAttributes(current, action.buttonText(), action.tooltip()))
                .append(suspended ? " disabled>" : ">").append(action.buttonText()).append("</button></form>");
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

    private static String capitalize(String text) {
        String lowerCase = toLowerCase(text);
        return Character.toUpperCase(lowerCase.charAt(0)) + lowerCase.substring(1);
    }

    private static String toLowerCase(String text) {
        return text.toLowerCase(Locale.ROOT);
    }
}
