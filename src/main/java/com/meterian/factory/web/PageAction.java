package com.meterian.factory.web;

import com.meterian.factory.board.IssueCard;
import com.meterian.factory.state.IssueRecord;
import com.meterian.factory.state.IssueStatus;
import com.meterian.factory.state.Phase;
import com.meterian.factory.workflow.ApprovalCheck;
import java.util.Arrays;
import java.util.Optional;

/**
 * The four buttons of the page. Each rule is here only: the page shows a button and the server accepts its
 * POST only when {@link #isPossible} is true. A label that is already on the card hides its button.
 */
public enum PageAction {
    ENTER("enter", "Enter", "factory:enter"),
    APPROVE("approve", "Approve", "factory:approve"),
    BLOCK("block", "Block", "factory:block"),
    CONTINUE("continue", "Continue", null);

    private final String pathName;
    private final String buttonText;
    private final String label;

    PageAction(String pathName, String buttonText, String label) {
        this.pathName = pathName;
        this.buttonText = buttonText;
        this.label = label;
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

    public boolean isPossible(IssueCard card, boolean continueRequested) {
        if (isLabelOnTheCard(card)) {
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

    private boolean isLabelOnTheCard(IssueCard card) {
        return label().filter(card.labels()::contains).isPresent();
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
