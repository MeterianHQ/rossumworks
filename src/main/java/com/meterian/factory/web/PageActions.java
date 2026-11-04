package com.meterian.factory.web;

import com.meterian.factory.agent.LiveSessionException;
import com.meterian.factory.agent.LiveSessions;
import com.meterian.factory.board.BoardState;
import com.meterian.factory.board.IssueCard;
import com.meterian.factory.board.PickUpSwitch;
import com.meterian.factory.workflow.PageRequests;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Runs the action of a button. It checks the rule of the button again on the current board, because the page
 * can be old. Enter, Approve, and Block add one label. Continue writes the local marker and makes no request.
 * After these actions, the page asks the worker for an early poll, so that the worker acts in seconds. Open
 * Terminal and Send to Phone act at once in the page: they need no poll.
 */
public final class PageActions {

    /** The kind of result of an action. */
    public enum ActionOutcome { DONE, NOT_POSSIBLE, UNKNOWN_ISSUE, FAILED }

    /** The result of an action. Only a FAILED result has a message. */
    public record ActionResult(ActionOutcome outcome, Optional<String> failureMessage) {

        static ActionResult of(ActionOutcome outcome) {
            return new ActionResult(outcome, Optional.empty());
        }

        static ActionResult failed(String message) {
            return new ActionResult(ActionOutcome.FAILED, Optional.of(message));
        }
    }

    private final LabelWriter labels;
    private final PageRequests pageRequests;
    private final LiveSessions liveSessions;
    private final PhoneLinkSender phoneLinkSender;
    private final BoardState board;
    private final PickUpSwitch pickUp;
    private final Runnable earlyPollRequest;
    private final ReentrantLock actionLock = new ReentrantLock();

    /** @param earlyPollRequest asks the worker for an early poll, 2 s later, see {@code Worker.requestEarlyPoll} */
    public PageActions(LabelWriter labels, PageRequests pageRequests, LiveSessions liveSessions,
            PhoneLinkSender phoneLinkSender, BoardState board, PickUpSwitch pickUp, Runnable earlyPollRequest) {
        this.labels = labels;
        this.pageRequests = pageRequests;
        this.liveSessions = liveSessions;
        this.phoneLinkSender = phoneLinkSender;
        this.board = board;
        this.pickUp = pickUp;
        this.earlyPollRequest = earlyPollRequest;
    }

    /**
     * Runs one action at a time, so that two clicks at the same moment add only one label.
     *
     * @param state the fingerprint that the form sent, see {@link CardFingerprint}
     * @param viewerOnThisMachine true when the browser runs on the machine of the worker, see {@link ViewerLocation}
     * @throws com.meterian.factory.github.GitHubException when GitHub refuses the label or the comment
     */
    public ActionResult perform(int issueNumber, PageAction action, Optional<String> state,
            boolean viewerOnThisMachine) {
        actionLock.lock();
        try {
            Optional<IssueCard> card = findCard(issueNumber);
            if (card.isEmpty()) {
                return ActionResult.of(ActionOutcome.UNKNOWN_ISSUE);
            }
            if (!isFromTheCurrentPage(card.get(), state) || !isPossible(card.get(), action, viewerOnThisMachine)) {
                return ActionResult.of(ActionOutcome.NOT_POSSIBLE);
            }
            return runAction(card.get(), action);
        } finally {
            actionLock.unlock();
        }
    }

    /** The page shows the new state at once. A pause needs no poll: the next poll only starts nothing new. */
    public void pausePickUp() {
        pickUp.pause();
        board.recordRequestChange();
    }

    /** The worker starts the waiting issues in about 2 seconds. */
    public void resumePickUp() {
        pickUp.resume();
        board.recordRequestChange();
        earlyPollRequest.run();
    }

    private Optional<IssueCard> findCard(int issueNumber) {
        return board.snapshot().cards().stream().filter(card -> card.issueNumber() == issueNumber).findFirst();
    }

    private static boolean isFromTheCurrentPage(IssueCard card, Optional<String> state) {
        return state.filter(CardFingerprint.of(card)::equals).isPresent();
    }

    private boolean isPossible(IssueCard card, PageAction action, boolean viewerOnThisMachine) {
        int issueNumber = card.issueNumber();
        return action.isPossible(card, liveSessions.isContinueRequested(issueNumber),
                PageAction.findRememberedLabels(issueNumber, pageRequests), viewerOnThisMachine);
    }

    private ActionResult runAction(IssueCard card, PageAction action) {
        return switch (action) {
            case OPEN_TERMINAL -> openTerminal(card.issueNumber());
            case SEND_TO_PHONE -> sendLinkToPhone(card);
            case CONTINUE -> requestEarlyPollAfter(() -> liveSessions.requestContinue(card.issueNumber()));
            case ENTER_TERMINAL, ENTER_BROWSER, ENTER_PHONE, APPROVE, BLOCK, START_AGAIN ->
                    requestEarlyPollAfter(() -> sendLabelRequest(card, action));
        };
    }

    private ActionResult requestEarlyPollAfter(Runnable request) {
        request.run();
        earlyPollRequest.run();
        return ActionResult.of(ActionOutcome.DONE);
    }

    private ActionResult openTerminal(int issueNumber) {
        try {
            liveSessions.openTerminal(issueNumber);
            return ActionResult.of(ActionOutcome.DONE);
        } catch (LiveSessionException exception) {
            return ActionResult.failed(exception.getMessage());
        }
    }

    /** The rule of Send to Phone accepts only a card with a Remote Control link. */
    private ActionResult sendLinkToPhone(IssueCard card) {
        String link = card.record().live().flatMap(live -> live.link()).orElseThrow();
        phoneLinkSender.sendLink(card.issueNumber(), link);
        return ActionResult.of(ActionOutcome.DONE);
    }

    /** An Enter remembers its place first, so that the worker knows where to open the conversation. */
    private void sendLabelRequest(IssueCard card, PageAction action) {
        action.enterPlace().ifPresent(place -> pageRequests.rememberEnterPlace(card.issueNumber(), place));
        try {
            addLabel(card, action, action.label().orElseThrow());
        } catch (RuntimeException exception) {
            forgetEnterPlace(card.issueNumber(), action);
            throw exception;
        }
    }

    /** A refused label leaves no place behind, so that a later Enter from the GitHub app opens no wrong place. */
    private void forgetEnterPlace(int issueNumber, PageAction action) {
        if (action.enterPlace().isPresent()) {
            pageRequests.takeEnterPlace(issueNumber);
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
