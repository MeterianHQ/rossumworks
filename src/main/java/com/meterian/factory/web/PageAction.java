package com.meterian.factory.web;

import com.meterian.factory.board.IssueCard;
import com.meterian.factory.state.IssueRecord;
import com.meterian.factory.state.IssueStatus;
import com.meterian.factory.state.Phase;
import com.meterian.factory.workflow.ApprovalCheck;
import com.meterian.factory.workflow.PageRequests;
import java.util.Arrays;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The four buttons of the page. Each rule is here only: the page shows a button and the server accepts its
 * POST only when {@link #isPossible} is true. A label on the card hides its button only when the page asked
 * for this label: a label that the worker ignored keeps the button, so that the owner can click again.
 */
public enum PageAction {
    ENTER("enter", "Enter", "factory:enter", "Enter sent. The worker opens the live conversation in a few seconds, or after the current step."),
    APPROVE("approve", "Approve", "factory:approve", "Approval sent. The worker takes it in a few seconds."),
    BLOCK("block", "Block", "factory:block", "Block sent. The worker stops the issue in a few seconds."),
    CONTINUE("continue", "Continue", null, "Continue sent. The factory takes the issue back in a few seconds.");

    private final String pathName;
    private final String buttonText;
    private final String label;
    private final String requestSentence;

    PageAction(String pathName, String buttonText, String label, String requestSentence) {
        this.pathName = pathName;
        this.buttonText = buttonText;
        this.label = label;
        this.requestSentence = requestSentence;
    }

    /** The line of the card while the worker did not act on the request yet. */
    public String requestSentence() {
        return requestSentence;
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
     */
    public boolean isPossible(IssueCard card, boolean continueRequested, Set<String> rememberedLabels) {
        if (isRequestedLabelOnTheCard(card, rememberedLabels)) {
            return false;
        }
        IssueRecord record = card.record();
        return switch (this) {
            case ENTER -> isEnterPossible(record);
            case APPROVE -> ApprovalCheck.waitsForApproval(record);
            case BLOCK -> isBlockPossible(record);
            case CONTINUE -> record.live().isPresent() && !continueRequested;
        };
    }

    /**
     * Tells if the page sent this request and the record does not show its effect yet. A label that the page
     * did not request is never a pending request.
     */
    public boolean isRequestPending(IssueCard card, boolean continueRequested, Set<String> rememberedLabels) {
        IssueRecord record = card.record();
        return switch (this) {
            case ENTER -> isRequestedLabelOnTheCard(card, rememberedLabels) && isEnterPossible(record);
            case APPROVE -> isRequestedLabelOnTheCard(card, rememberedLabels) && ApprovalCheck.waitsForApproval(record);
            case BLOCK -> isRequestedLabelOnTheCard(card, rememberedLabels) && isBlockPossible(record);
            case CONTINUE -> continueRequested && record.live().isPresent();
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
        boolean hasLiveLink = record.live().flatMap(live -> live.link()).isPresent();
        return record.agentSessionStarted() && record.phase() != Phase.DONE && !hasLiveLink;
    }

    private static boolean isBlockPossible(IssueRecord record) {
        return record.status() != IssueStatus.BLOCKED
                && record.status() != IssueStatus.DONE
                && record.phase() != Phase.DONE;
    }
}
