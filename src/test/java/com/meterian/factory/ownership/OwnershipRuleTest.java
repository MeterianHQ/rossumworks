package com.meterian.factory.ownership;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.meterian.factory.github.AssignmentEvent;
import com.meterian.factory.github.Issue;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class OwnershipRuleTest {

    private static final Instant TEN = Instant.parse("2026-11-15T10:00:00Z");
    private static final Instant TEN_ONE = Instant.parse("2026-11-15T10:01:00Z");
    private static final Instant TEN_TWO = Instant.parse("2026-11-15T10:02:00Z");

    private static Issue issueAssignedTo(String... assignees) {
        return new Issue(1, "title", "body", Set.of(), Set.of(assignees));
    }

    private static Ownership decide(Issue issue, AssignmentEvent... events) {
        return OwnershipRule.decide(issue, "worker", () -> List.of(events));
    }

    @Test
    void issueWithoutAssigneeIsUnassigned() {
        assertEquals(Ownership.UNASSIGNED, decide(issueAssignedTo()));
    }

    @Test
    void issueOfAnotherAccountIsOwnedByAnother() {
        assertEquals(Ownership.OWNED_BY_ANOTHER, decide(issueAssignedTo("alice")));
    }

    @Test
    void onlyAssigneeIsTheOwner() {
        AtomicInteger eventReads = new AtomicInteger();
        Ownership ownership = OwnershipRule.decide(issueAssignedTo("worker"), "worker", () -> {
            eventReads.incrementAndGet();
            return List.of();
        });
        assertEquals(Ownership.OWNED, ownership);
        assertEquals(0, eventReads.get());
    }

    @Test
    void ownerMatchesTheLoginWithoutCase() {
        assertEquals(Ownership.OWNED, decide(issueAssignedTo("Worker")));
    }

    @Test
    void earliestAssignmentWins() {
        Issue issue = issueAssignedTo("worker", "alice");
        assertEquals(Ownership.OWNED, decide(issue,
                new AssignmentEvent(1, "worker", TEN), new AssignmentEvent(2, "alice", TEN_ONE)));
        assertEquals(Ownership.OWNED_BY_ANOTHER, decide(issue,
                new AssignmentEvent(1, "worker", TEN_ONE), new AssignmentEvent(2, "alice", TEN)));
    }

    @Test
    void eventIdBreaksATie() {
        Issue issue = issueAssignedTo("worker", "alice");
        assertEquals(Ownership.OWNED_BY_ANOTHER, decide(issue,
                new AssignmentEvent(5, "alice", TEN), new AssignmentEvent(6, "worker", TEN)));
    }

    @Test
    void newestAssignmentOfAnAssigneeCounts() {
        Issue issue = issueAssignedTo("worker", "alice");
        assertEquals(Ownership.OWNED_BY_ANOTHER, decide(issue,
                new AssignmentEvent(1, "worker", TEN), new AssignmentEvent(2, "alice", TEN_ONE),
                new AssignmentEvent(3, "worker", TEN_TWO)));
    }

    @Test
    void assigneeWithoutEventWins() {
        assertEquals(Ownership.OWNED_BY_ANOTHER,
                decide(issueAssignedTo("worker", "alice"), new AssignmentEvent(1, "worker", TEN)));
    }

    @Test
    void workerWithoutEventGivesWay() {
        assertEquals(Ownership.OWNED_BY_ANOTHER,
                decide(issueAssignedTo("worker", "alice"), new AssignmentEvent(1, "alice", TEN)));
    }

    @Test
    void twoAssigneesWithoutEventsGiveWay() {
        assertEquals(Ownership.OWNED_BY_ANOTHER, decide(issueAssignedTo("worker", "alice")));
    }
}
