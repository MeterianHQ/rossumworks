package com.meterian.factory.web;

import com.meterian.factory.agent.LiveSessions;
import com.meterian.factory.board.BoardState;
import com.meterian.factory.board.IssueCard;
import com.meterian.factory.workflow.PageRequests;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Runs the action of a button. It checks the rule of the button again on the current board, because the page
 * can be old. Enter, Approve, and Block add one label. Continue writes the local marker and makes no request.
 * The worker acts on a label at its next poll, so a Block stops the claude process after one poll interval.
 */
public final class PageActions {

    /** The result of an action. */
    public enum ActionOutcome { DONE, NOT_POSSIBLE, UNKNOWN_ISSUE }

    private final LabelAdder labels;
    private final PageRequests pageRequests;
    private final LiveSessions liveSessions;
    private final BoardState board;
    private final ReentrantLock actionLock = new ReentrantLock();

    public PageActions(LabelAdder labels, PageRequests pageRequests, LiveSessions liveSessions, BoardState board) {
        this.labels = labels;
        this.pageRequests = pageRequests;
        this.liveSessions = liveSessions;
        this.board = board;
    }

    /**
     * Runs one action at a time, so that two clicks at the same moment add only one label.
     *
     * @throws com.meterian.factory.github.GitHubException when GitHub refuses the label
     */
    public ActionOutcome perform(int issueNumber, PageAction action) {
        actionLock.lock();
        try {
            Optional<IssueCard> card = findCard(issueNumber);
            if (card.isEmpty()) {
                return ActionOutcome.UNKNOWN_ISSUE;
            }
            if (!action.isPossible(card.get(), liveSessions.isContinueRequested(issueNumber))) {
                return ActionOutcome.NOT_POSSIBLE;
            }
            runAction(issueNumber, action);
            return ActionOutcome.DONE;
        } finally {
            actionLock.unlock();
        }
    }

    private Optional<IssueCard> findCard(int issueNumber) {
        return board.snapshot().cards().stream().filter(card -> card.issueNumber() == issueNumber).findFirst();
    }

    private void runAction(int issueNumber, PageAction action) {
        Optional<String> label = action.label();
        if (label.isPresent()) {
            addLabel(issueNumber, label.get());
        } else {
            liveSessions.requestContinue(issueNumber);
        }
    }

    /** The request is remembered first, so that the actor check of the next poll accepts the label. */
    private void addLabel(int issueNumber, String label) {
        pageRequests.remember(issueNumber, label);
        labels.addLabel(issueNumber, label);
        board.recordLabelAdded(issueNumber, label);
    }
}
