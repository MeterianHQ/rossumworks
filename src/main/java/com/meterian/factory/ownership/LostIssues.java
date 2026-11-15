package com.meterian.factory.ownership;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The issues that this factory knows another account owns. The memory lives only in this run: after a restart,
 * the poll finds the losses again.
 */
public final class LostIssues {

    private final Set<Integer> issueNumbers = ConcurrentHashMap.newKeySet();

    public void rememberLoss(int issueNumber) {
        issueNumbers.add(issueNumber);
    }

    /** The factory owns the issue again. */
    public void forgetLoss(int issueNumber) {
        issueNumbers.remove(issueNumber);
    }

    public boolean isLost(int issueNumber) {
        return issueNumbers.contains(issueNumber);
    }
}
