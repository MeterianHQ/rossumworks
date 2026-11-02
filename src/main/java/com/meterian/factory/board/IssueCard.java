package com.meterian.factory.board;

import com.meterian.factory.state.IssueRecord;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;

public record IssueCard(int issueNumber, String title, Set<String> labels, IssueRecord record,
                        Optional<Instant> doneAt) {

    public IssueCard {
        labels = Set.copyOf(labels);
    }
}
