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
 * After each action, the page asks the worker for an early poll, so that the worker acts in seconds.
 */
public final class PageActions {

    /** The result of an action. */
    public enum ActionOutcome { DONE, NOT_POSSIBLE, UNKNOWN_ISSUE }

    private final LabelWriter labels;
    private final PageRequests pageRequests;
    private final LiveSessions liveSessions;
    private final BoardState board;
    private final Runnable earlyPollRequest;
    private final ReentrantLock actionLock = new ReentrantLock();

    /** @param earlyPollRequest asks the worker for a poll at once, see {@code Worker.requestEarlyPoll} */
    public PageActions(LabelWriter labels, PageRequests pageRequests, LiveSessions liveSessions, BoardState board,
            Runnable earlyPollRequest) {
        this.labels = labels;
        this.pageRequests = pageRequests;
        this.liveSessions = liveSessions;
        this.board = board;
        this.earlyPollRequest = earlyPollRequest;
    }

    /**
     * Runs one action at a time, so that two clicks at the same moment add only one label.
     *
     * @param state the fingerprint that the form sent, see {@link CardFingerprint}
     * @throws com.meterian.factory.github.GitHubException when GitHub refuses the label
     */
    public ActionOutcome perform(int issueNumber, PageAction action, Optional<String> state) {
        actionLock.lock();
        try {
            Optional<IssueCard> card = findCard(issueNumber);
            if (card.isEmpty()) {
                return ActionOutcome.UNKNOWN_ISSUE;
            }
            if (!isFromTheCurrentPage(card.get(), state) || !isPossible(card.get(), action)) {
                return ActionOutcome.NOT_POSSIBLE;
            }
            runAction(card.get(), action);
            earlyPollRequest.run();
            return ActionOutcome.DONE;
        } finally {
            actionLock.unlock();
        }
    }

    private Optional<IssueCard> findCard(int issueNumber) {
        return board.snapshot().cards().stream().filter(card -> card.issueNumber() == issueNumber).findFirst();
    }

    private static boolean isFromTheCurrentPage(IssueCard card, Optional<String> state) {
        return state.filter(CardFingerprint.of(card)::equals).isPresent();
    }

    private boolean isPossible(IssueCard card, PageAction action) {
        int issueNumber = card.issueNumber();
        return action.isPossible(card, liveSessions.isContinueRequested(issueNumber),
                PageAction.findRememberedLabels(issueNumber, pageRequests));
    }

    private void runAction(IssueCard card, PageAction action) {
        Optional<String> label = action.label();
        if (label.isPresent()) {
            addLabel(card, action, label.get());
        } else {
            liveSessions.requestContinue(card.issueNumber());
        }
    }

    /**
     * The request is remembered first, so that the actor check of the next poll accepts the label. A label that
     * the worker ignored is still on the issue: the page removes it and adds it again, so that the newest
     * "labeled" event comes from the worker account.
     */
    private void addLabel(IssueCard card, PageAction action, String label) {
        int issueNumber = card.issueNumber();
        pageRequests.remember(issueNumber, label);
        if (action.isLabelOnTheCard(card)) {
            labels.removeLabel(issueNumber, label);
        }
        labels.addLabel(issueNumber, label);
        board.recordLabelAdded(issueNumber, label);
    }
}
