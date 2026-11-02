package com.meterian.factory.board;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public record BoardSnapshot(Instant workerStartedAt, Optional<Instant> lastCheckAt, List<IssueCard> cards,
                            long version) {

    public BoardSnapshot {
        cards = List.copyOf(cards);
    }
}
