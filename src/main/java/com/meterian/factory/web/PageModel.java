package com.meterian.factory.web;

import com.meterian.factory.agent.ContextUsage;
import com.meterian.factory.agent.UsageWindow;
import com.meterian.factory.board.BoardSnapshot;
import com.meterian.factory.board.FactoryState;
import com.meterian.factory.configuration.IsolationMode;
import com.meterian.factory.state.LivePlace;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * All the values that the page shows. The page reads them from memory and from local files only.
 *
 * @param limits            the limits of the states of each card, by issue number, from the definition of its record
 * @param reviewNames       the names of the reviews of each card, by issue number, from the definition of its record
 * @param inBlockTarget     the issues whose state is the block target of the definition of their record
 * @param definitionWarning the reason why the definition of the default branch does not load, if it does not
 * @param factoryState      the state of the whole factory: running, stopping, or stopped
 * @param columns           the columns of the page: queued, then the columns of the newest good definition
 */
public record PageModel(
        BoardSnapshot board,
        Instant renderedAt,
        int issuesInWork,
        int parallelLimit,
        Map<Integer, Map<String, Integer>> limits,
        Map<Integer, List<String>> reviewNames,
        Set<Integer> inBlockTarget,
        IsolationMode isolationMode,
        Duration pollInterval,
        List<UsageWindow> usageWindows,
        Optional<Instant> usageResumesAt,
        Map<Integer, ContextUsage> contexts,
        Set<Integer> continueRequested,
        Map<Integer, Set<String>> rememberedLabels,
        Map<Integer, LivePlace> enterPlaces,
        String repositoryFullName,
        boolean viewerOnThisMachine,
        Optional<String> definitionWarning,
        FactoryState factoryState,
        List<String> columns) {

    public PageModel {
        limits = Map.copyOf(limits);
        reviewNames = Map.copyOf(reviewNames);
        inBlockTarget = Set.copyOf(inBlockTarget);
        usageWindows = List.copyOf(usageWindows);
        contexts = Map.copyOf(contexts);
        continueRequested = Set.copyOf(continueRequested);
        rememberedLabels = rememberedLabels.entrySet().stream()
                .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, entry -> Set.copyOf(entry.getValue())));
        enterPlaces = Map.copyOf(enterPlaces);
        columns = List.copyOf(columns);
    }

    /** The limit of each state with a limit, in the order of the workflow of the issue. */
    public Map<String, Integer> limitsOf(int issueNumber) {
        return limits.getOrDefault(issueNumber, Map.of());
    }

    /** The names of the reviews, in the order of the workflow of the issue. */
    public List<String> reviewNamesOf(int issueNumber) {
        return reviewNames.getOrDefault(issueNumber, List.of());
    }

    public boolean isInBlockTarget(int issueNumber) {
        return inBlockTarget.contains(issueNumber);
    }

    /** The labels that the page asked for on this issue. */
    public Set<String> findRememberedLabels(int issueNumber) {
        return rememberedLabels.getOrDefault(issueNumber, Set.of());
    }

    /** The place of the Enter button that the page sent on this issue, if the page remembers it. */
    public Optional<LivePlace> findEnterPlace(int issueNumber) {
        return Optional.ofNullable(enterPlaces.get(issueNumber));
    }
}
