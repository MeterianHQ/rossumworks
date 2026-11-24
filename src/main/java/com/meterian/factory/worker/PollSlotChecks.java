package com.meterian.factory.worker;

import com.meterian.factory.github.Issue;
import java.util.ArrayList;
import java.util.List;

/**
 * Keeps what a poll knows about the slots of the cards. The poll checks the cards one by one, so it knows the slot
 * of each card only at the end of the checks. A card that asks for a new slot, for example for an enter request,
 * waits for this end, and the poll then checks it again. Only the poll thread uses this class.
 */
final class PollSlotChecks {

    private final List<Issue> issuesThatWait = new ArrayList<>();
    private boolean allCardsChecked;
    private boolean aCheckFailed;

    void startPoll() {
        issuesThatWait.clear();
        allCardsChecked = false;
        aCheckFailed = false;
    }

    /** The issue asks for a new slot before the end of the checks. The poll checks it again after the end. */
    void deferToTheEndOfTheChecks(Issue issue) {
        issuesThatWait.add(issue);
    }

    /** Ends the checks of the cards, and gives the issues that wait for this end. */
    List<Issue> finishChecks() {
        allCardsChecked = true;
        List<Issue> waitingIssues = List.copyOf(issuesThatWait);
        issuesThatWait.clear();
        return waitingIssues;
    }

    boolean areAllCardsChecked() {
        return allCardsChecked;
    }

    /** After a failed check, the worker does not know the slot of each card. */
    void rememberFailedCheck() {
        aCheckFailed = true;
    }

    boolean hasAFailedCheck() {
        return aCheckFailed;
    }
}
