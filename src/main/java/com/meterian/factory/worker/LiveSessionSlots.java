package com.meterian.factory.worker;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.function.IntPredicate;

/**
 * Keeps the issues with an open live session. Each one holds a slot of parallel-issues, as an issue in work. A
 * session holds its slot from the moment that it opens until a poll finds it closed.
 */
final class LiveSessionSlots {

    private final Set<Integer> issuesInLiveSession = new HashSet<>();
    private final Set<Integer> issuesFoundByThePoll = new HashSet<>();

    /** Starts the count of a poll and returns the issues with an open session before this poll. */
    synchronized Set<Integer> startPoll() {
        issuesFoundByThePoll.clear();
        return Set.copyOf(issuesInLiveSession);
    }

    /** The poll or the end of a loop found an open session of the issue. */
    synchronized void remember(int issueNumber) {
        issuesInLiveSession.add(issueNumber);
        issuesFoundByThePoll.add(issueNumber);
    }

    /**
     * Ends the count of a poll. An earlier session that the poll did not find, for example after a failed check,
     * keeps its slot while its session is open.
     */
    void finishPoll(Set<Integer> earlierLiveIssues, IntPredicate isSessionOpen) {
        Set<Integer> stillOpen = new HashSet<>();
        for (int issueNumber : earlierLiveIssues) {
            if (isSessionOpen.test(issueNumber)) {
                stillOpen.add(issueNumber);
            }
        }
        replaceWithFoundAnd(stillOpen);
    }

    private synchronized void replaceWithFoundAnd(Set<Integer> stillOpen) {
        issuesInLiveSession.retainAll(union(issuesFoundByThePoll, stillOpen));
    }

    private static Set<Integer> union(Set<Integer> first, Set<Integer> second) {
        Set<Integer> union = new HashSet<>(first);
        union.addAll(second);
        return union;
    }

    /** An issue back from its live session takes over the slot of its own session, so it does not count. */
    synchronized int countOutsideWork(Set<Integer> issuesInWork, Optional<Integer> excludedIssueNumber) {
        return (int) issuesInLiveSession.stream()
                .filter(issueNumber -> !issuesInWork.contains(issueNumber))
                .filter(issueNumber -> excludedIssueNumber.filter(issueNumber::equals).isEmpty())
                .count();
    }
}
