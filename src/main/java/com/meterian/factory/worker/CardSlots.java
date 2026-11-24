package com.meterian.factory.worker;

import com.meterian.factory.definition.ColumnNames;
import com.meterian.factory.state.ActionType;
import com.meterian.factory.state.IssueRecord;
import com.meterian.factory.workflow.IssueDefinitionFacts;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

/**
 * Keeps the owned cards that hold a slot of parallel-issues, also when no loop runs for them. An example is a card
 * that waits for the approval. A queued card, a done card, and a blocked card hold no slot.
 */
final class CardSlots {

    private final IssueDefinitionFacts facts;
    private final Set<Integer> issuesWithASlot = new HashSet<>();

    CardSlots(IssueDefinitionFacts facts) {
        this.facts = facts;
    }

    /** Gives the card a slot, or takes its slot away, from the current record of the card. */
    synchronized void remember(IssueRecord record) {
        if (holdsASlot(record)) {
            issuesWithASlot.add(record.issueNumber());
        } else {
            issuesWithASlot.remove(record.issueNumber());
        }
    }

    /** Takes the slot away from a card that leaves the worker, for example to another owner, or that gets a block. */
    synchronized void forget(int issueNumber) {
        issuesWithASlot.remove(issueNumber);
    }

    /** Takes the slot away from each card without the label factory, because it is not on the board any more. */
    synchronized void forgetAllExcept(Set<Integer> labeledIssues) {
        issuesWithASlot.retainAll(labeledIssues);
    }

    synchronized Set<Integer> findIssues() {
        return Set.copyOf(issuesWithASlot);
    }

    /** A queued card and a blocked card hold no slot. When they start, they add a card to the board. */
    boolean takesANewSlot(IssueRecord record) {
        return isQueued(record) || facts.isInBlockTarget(record);
    }

    boolean holdsASlot(IssueRecord record) {
        return !isQueued(record) && !isDone(record) && !facts.isInBlockTarget(record);
    }

    private static boolean isQueued(IssueRecord record) {
        return ColumnNames.QUEUED.equals(record.column());
    }

    private static boolean isDone(IssueRecord record) {
        return record.action().equals(Optional.of(ActionType.FINISH));
    }
}
