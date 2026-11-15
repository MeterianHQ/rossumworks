package com.meterian.factory.ownership;

import com.meterian.factory.github.AssignmentEvent;
import com.meterian.factory.github.Issue;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/** Decides which account owns an issue from its assignees. */
public final class OwnershipRule {

    private static final Comparator<AssignmentEvent> EARLIEST_FIRST =
            Comparator.comparing(AssignmentEvent::createdAt).thenComparingLong(AssignmentEvent::id);

    private OwnershipRule() {
    }

    /** Calls the events only when the worker login is one of many assignees. */
    public static Ownership decide(Issue issue, String workerLogin,
            Supplier<List<AssignmentEvent>> events) {
        if (issue.assignees().isEmpty()) {
            return Ownership.UNASSIGNED;
        }
        if (!isAssignee(issue, workerLogin)) {
            return Ownership.OWNED_BY_ANOTHER;
        }
        if (issue.assignees().size() == 1) {
            return Ownership.OWNED;
        }
        return decideByEarliestAssignment(issue, workerLogin, events.get());
    }

    private static Ownership decideByEarliestAssignment(Issue issue, String workerLogin,
            List<AssignmentEvent> events) {
        AssignmentEvent earliest = null;
        for (String assignee : issue.assignees()) {
            Optional<AssignmentEvent> newest = findNewestEvent(events, assignee);
            if (newest.isEmpty()) {
                return Ownership.OWNED_BY_ANOTHER;
            }
            if (earliest == null || EARLIEST_FIRST.compare(newest.get(), earliest) < 0) {
                earliest = newest.get();
            }
        }
        return earliest.assignee().equalsIgnoreCase(workerLogin) ? Ownership.OWNED
                : Ownership.OWNED_BY_ANOTHER;
    }

    private static Optional<AssignmentEvent> findNewestEvent(List<AssignmentEvent> events, String assignee) {
        return events.stream()
                .filter(event -> event.assignee().equalsIgnoreCase(assignee))
                .max(EARLIEST_FIRST);
    }

    private static boolean isAssignee(Issue issue, String login) {
        return issue.assignees().stream().anyMatch(assignee -> assignee.equalsIgnoreCase(login));
    }
}
