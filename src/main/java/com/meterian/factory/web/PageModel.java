package com.meterian.factory.web;

import com.meterian.factory.agent.ContextUsage;
import com.meterian.factory.agent.UsageWindow;
import com.meterian.factory.board.BoardSnapshot;
import com.meterian.factory.configuration.IsolationMode;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/** All the values that the page shows. The page reads them from memory and from local files only. */
public record PageModel(
        BoardSnapshot board,
        Instant renderedAt,
        int issuesInWork,
        int parallelLimit,
        int fixLoopLimit,
        IsolationMode isolationMode,
        Duration pollInterval,
        List<UsageWindow> usageWindows,
        Optional<Instant> usageResumesAt,
        Map<Integer, ContextUsage> contexts,
        Set<Integer> continueRequested,
        Map<Integer, Set<String>> rememberedLabels,
        String repositoryFullName) {

    public PageModel {
        usageWindows = List.copyOf(usageWindows);
        contexts = Map.copyOf(contexts);
        continueRequested = Set.copyOf(continueRequested);
        rememberedLabels = rememberedLabels.entrySet().stream()
                .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, entry -> Set.copyOf(entry.getValue())));
    }

    /** The labels that the page asked for on this issue. */
    public Set<String> findRememberedLabels(int issueNumber) {
        return rememberedLabels.getOrDefault(issueNumber, Set.of());
    }
}
