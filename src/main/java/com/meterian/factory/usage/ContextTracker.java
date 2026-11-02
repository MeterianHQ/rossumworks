package com.meterian.factory.usage;

import com.meterian.factory.agent.ContextUsage;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/** Keeps the newest context usage of the agent for each issue. */
public final class ContextTracker {

    private final Map<Integer, ContextUsage> usageByIssueNumber = new HashMap<>();

    public synchronized void record(int issueNumber, ContextUsage usage) {
        usageByIssueNumber.put(issueNumber, usage);
    }

    public synchronized Optional<ContextUsage> find(int issueNumber) {
        return Optional.ofNullable(usageByIssueNumber.get(issueNumber));
    }
}
