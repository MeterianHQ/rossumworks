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

    /** The poll or the end of a loop knows the current record of the card. */
    synchronized void remember(IssueRecord record) {
        if (holdsASlot(record)) {
            issuesWithASlot.add(record.issueNumber());
        } else {
            issuesWithASlot.remove(record.issueNumber());
        }
    }

    /** The card left the worker, for example to another owner, or it gets a block. */
    synchronized void forget(int issueNumber) {
        issuesWithASlot.remove(issueNumber);
    }

    /** A card without the label factory is not on the board any more. */
    synchronized void forgetAllExcept(Set<Integer> labeledIssues) {
        issuesWithASlot.retainAll(labeledIssues);
    }

    synchronized Set<Integer> issues() {
        return Set.copyOf(issuesWithASlot);
    }

    private boolean holdsASlot(IssueRecord record) {
        return !ColumnNames.QUEUED.equals(record.column()) && !isDone(record) && !facts.isInBlockTarget(record);
    }

    private static boolean isDone(IssueRecord record) {
        return record.action().equals(Optional.of(ActionType.FINISH));
    }
}
