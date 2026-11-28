package com.meterian.factory.workflow;

import com.meterian.factory.state.IssueRecord;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The last record that the current action of an issue wrote to the status comment, by issue number. Each issue
 * thread uses its own entry.
 */
final class PublishedRecords {

    private final Map<Integer, IssueRecord> records = new ConcurrentHashMap<>();

    void remember(IssueRecord published) {
        records.put(published.issueNumber(), published);
    }

    void forget(int issueNumber) {
        records.remove(issueNumber);
    }

    /** Gives the record and forgets it, so the next action starts without it. */
    Optional<IssueRecord> take(int issueNumber) {
        return Optional.ofNullable(records.remove(issueNumber));
    }
}
