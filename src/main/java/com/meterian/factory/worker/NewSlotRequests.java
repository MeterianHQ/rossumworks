package com.meterian.factory.worker;

import com.meterian.factory.github.Issue;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * Decides in a poll when a card without a slot can take a new slot, for example for an enter request. The poll
 * checks the cards one by one, so it knows the slot of each card only at the end of the checks. Until then, a
 * request for a new slot waits. Only the poll thread uses this class.
 */
final class NewSlotRequests {

    private final List<Issue> issuesThatWait = new ArrayList<>();
    private boolean allCardsChecked;
    private boolean aCheckFailed;

    void startPoll() {
        issuesThatWait.clear();
        allCardsChecked = false;
        aCheckFailed = false;
    }

    /** After a failed check, the worker does not know the slot of each card. */
    void rememberFailedCheck() {
        aCheckFailed = true;
    }

    boolean hasAFailedCheck() {
        return aCheckFailed;
    }

    /** Ends the checks of the cards, and gives the issues that wait for this end. The poll checks them again. */
    List<Issue> finishChecks() {
        allCardsChecked = true;
        List<Issue> waitingIssues = List.copyOf(issuesThatWait);
        issuesThatWait.clear();
        return waitingIssues;
    }

    /**
     * Gives true when the card can take a new slot now. Before the end of the checks, the issue waits, and the
     * result is false. Fail closed: after a failed check, the result is false.
     */
    boolean requestANewSlot(Issue issue, BooleanSupplier hasAFreeSlot) {
        if (!allCardsChecked) {
            issuesThatWait.add(issue);
            return false;
        }
        return !aCheckFailed && hasAFreeSlot.getAsBoolean();
    }
}
