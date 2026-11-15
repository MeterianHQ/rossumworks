package com.meterian.factory.worker;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The issues of another owner that this run already released. The poll then sends no request for their release.
 * The memory lives only in this run: after a restart, the release reads the comments once again.
 */
final class ReleasedIssues {

    private final Set<Integer> issueNumbers = ConcurrentHashMap.newKeySet();

    void rememberRelease(int issueNumber) {
        issueNumbers.add(issueNumber);
    }

    /** The factory owns the issue again, so a later loss needs a new release. */
    void forgetRelease(int issueNumber) {
        issueNumbers.remove(issueNumber);
    }

    boolean isReleased(int issueNumber) {
        return issueNumbers.contains(issueNumber);
    }
}
