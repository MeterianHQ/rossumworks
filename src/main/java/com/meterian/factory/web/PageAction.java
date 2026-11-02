package com.meterian.factory.web;

import com.meterian.factory.agent.TmuxLiveSessions;
import com.meterian.factory.board.IssueCard;
import com.meterian.factory.state.IssueRecord;
import com.meterian.factory.state.IssueStatus;
import com.meterian.factory.state.LivePlace;
import com.meterian.factory.state.Phase;
import com.meterian.factory.workflow.ApprovalCheck;
import com.meterian.factory.workflow.PageRequests;
import java.util.Arrays;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The buttons of the page. Each rule is here only: the page shows a button and the server accepts its POST only
 * when {@link #isPossible} is true. A label on the card hides its button only when the page asked for this label:
 * a label that the worker ignored keeps the button, so that the owner can click again. The three Enter buttons
 * add the same label and differ only in the place where the live conversation opens.
 */
public enum PageAction {
    ENTER_TERMINAL("enter-terminal", "Terminal", "factory:enter", LivePlace.TERMINAL,
            "Enter sent. A terminal window opens on this machine in a few seconds, or after the current step.",
            "Opens the conversation in a terminal window on this machine. Close the window with Ctrl-b d."),
    ENTER_BROWSER("enter-browser", "Browser", "factory:enter", LivePlace.BROWSER,
            "Enter sent. The new tab goes to the conversation in a few seconds, or after the current step.",
            "Opens the conversation in a new tab of this browser. The tab waits a few seconds for the link."),
    ENTER_PHONE("enter-phone", "Phone", "factory:enter", LivePlace.PHONE,
            "Enter sent. The link goes to your phone in a few seconds, or after the current step.",
            "Sends the link of the conversation to your phone, with a GitHub notification."),
    APPROVE("approve", "Approve", "factory:approve", null,
            "Approval sent. The worker takes it in a few seconds.", ""),
    BLOCK("block", "Block", "factory:block", null, "Block sent. The worker stops the issue in a few seconds.", ""),
    CONTINUE("continue", "Continue", null, null, "Continue sent. The factory takes the issue back in a few seconds.",
            ""),
    OPEN_TERMINAL("open-terminal", "Terminal", null, null, null,
            "Opens one more terminal window on this machine, attached to the same conversation."),
    SEND_TO_PHONE("send-to-phone", "Phone", null, null, null,
            "Sends the link to your phone again, with one more GitHub comment.");

    private final String pathName;
    private final String buttonText;
    private final String label;
    private final LivePlace enterPlace;
    private final String requestSentence;
    private final String tooltip;

    PageAction(String pathName, String buttonText, String label, LivePlace enterPlace, String requestSentence,
            String tooltip) {
        this.pathName = pathName;
        this.buttonText = buttonText;
        this.label = label;
        this.enterPlace = enterPlace;
        this.requestSentence = requestSentence;
        this.tooltip = tooltip;
    }

    /**
     * The line of the card while the worker did not act on the request yet. Open Terminal and Send to Phone have
     * no line: they act at once.
     */
    public Optional<String> requestSentence() {
        return Optional.ofNullable(requestSentence);
    }

    /** The place where the live conversation opens. Only the three Enter buttons have a place. */
    public Optional<LivePlace> enterPlace() {
        return Optional.ofNullable(enterPlace);
    }

    /** True for the buttons that open a terminal window: they work only on the machine of the worker. */
    public boolean needsThisMachine() {
        return this == ENTER_TERMINAL || this == OPEN_TERMINAL;
    }

    /** The help text of the button. It is empty for Approve, Block, and Continue. */
    public String tooltip() {
        return tooltip;
    }

    /** The label with the same effect. Continue has no label: it writes a local marker file. */
    public Optional<String> label() {
        return Optional.ofNullable(label);
    }

    public String pathName() {
        return pathName;
    }

    public String buttonText() {
        return buttonText;
    }

    public static Optional<PageAction> findByPathName(String name) {
        return Arrays.stream(values()).filter(action -> action.pathName.equals(name)).findFirst();
    }

    /** The labels of the buttons that the page asked for on this issue, from the remembered page requests. */
    public static Set<String> findRememberedLabels(int issueNumber, PageRequests pageRequests) {
        return Arrays.stream(values())
                .flatMap(action -> action.label().stream())
                .filter(label -> pageRequests.isRemembered(issueNumber, label))
                .collect(Collectors.toSet());
    }

    /**
     * Tells if the page shows the button and if the server accepts its POST.
     *
     * @param continueRequested true when the marker of Continue exists
     * @param rememberedLabels the labels that the page asked for on this issue, see {@link #findRememberedLabels}
     * @param viewerOnThisMachine true when the browser runs on the machine of the worker, see {@link ViewerLocation}
     */
    public boolean isPossible(IssueCard card, boolean continueRequested, Set<String> rememberedLabels,
            boolean viewerOnThisMachine) {
        if (needsThisMachine() && !viewerOnThisMachine) {
            return false;
        }
        if (isRequestedLabelOnTheCard(card, rememberedLabels)) {
            return false;
        }
        IssueRecord record = card.record();
        return switch (this) {
            case ENTER_TERMINAL, ENTER_BROWSER, ENTER_PHONE -> isEnterPossible(record);
            case APPROVE -> ApprovalCheck.waitsForApproval(record);
            case BLOCK -> isBlockPossible(record);
            case CONTINUE -> record.live().isPresent() && !continueRequested;
            case OPEN_TERMINAL -> findLiveLink(record).isPresent() && !continueRequested;
            case SEND_TO_PHONE -> findLiveLink(record).filter(TmuxLiveSessions::isRemoteControlLink).isPresent()
                    && !continueRequested;
        };
    }

    /**
     * Tells if the page sent this request and the record does not show its effect yet. A label that the page
     * did not request is never a pending request.
     */
    public boolean isRequestPending(IssueCard card, boolean continueRequested, Set<String> rememberedLabels) {
        IssueRecord record = card.record();
        return switch (this) {
            case ENTER_TERMINAL, ENTER_BROWSER, ENTER_PHONE ->
                    isRequestedLabelOnTheCard(card, rememberedLabels) && isEnterPossible(record);
            case APPROVE -> isRequestedLabelOnTheCard(card, rememberedLabels) && ApprovalCheck.waitsForApproval(record);
            case BLOCK -> isRequestedLabelOnTheCard(card, rememberedLabels) && isBlockPossible(record);
            case CONTINUE -> continueRequested && record.live().isPresent();
            case OPEN_TERMINAL, SEND_TO_PHONE -> false;
        };
    }

    /** True when the card has the label of this button. */
    public boolean isLabelOnTheCard(IssueCard card) {
        return label().filter(card.labels()::contains).isPresent();
    }

    private boolean isRequestedLabelOnTheCard(IssueCard card, Set<String> rememberedLabels) {
        return isLabelOnTheCard(card) && label().filter(rememberedLabels::contains).isPresent();
    }

    /** A live state without a link can enter again, because the open of the session did not end. */
    private static boolean isEnterPossible(IssueRecord record) {
        return record.agentSessionStarted() && record.phase() != Phase.DONE && findLiveLink(record).isEmpty();
    }

    private static Optional<String> findLiveLink(IssueRecord record) {
        return record.live().flatMap(live -> live.link());
    }

    private static boolean isBlockPossible(IssueRecord record) {
        return record.status() != IssueStatus.BLOCKED
                && record.status() != IssueStatus.DONE
                && record.phase() != Phase.DONE;
    }
}
