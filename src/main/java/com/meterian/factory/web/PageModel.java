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
        String repositoryFullName) {

    public PageModel {
        usageWindows = List.copyOf(usageWindows);
        contexts = Map.copyOf(contexts);
        continueRequested = Set.copyOf(continueRequested);
    }
}
