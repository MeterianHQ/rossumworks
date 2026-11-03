package com.meterian.factory.web;

import com.meterian.factory.agent.ContextUsage;
import com.meterian.factory.agent.LiveContextReader;
import com.meterian.factory.agent.LiveSessions;
import com.meterian.factory.board.BoardSnapshot;
import com.meterian.factory.board.BoardState;
import com.meterian.factory.board.IssueCard;
import com.meterian.factory.configuration.FactoryConfiguration;
import com.meterian.factory.definition.DefinitionLookup;
import com.meterian.factory.state.LivePlace;
import com.meterian.factory.usage.ContextTracker;
import com.meterian.factory.usage.UsageTracker;
import com.meterian.factory.workflow.FixLoopLimits;
import com.meterian.factory.workflow.PageRequests;
import com.meterian.factory.workflow.WorkspaceLayout;
import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/** Collects the values of the page. It reads only memory and local files, never GitHub. */
public final class PageModelSource {

    private final BoardState board;
    private final UsageTracker usage;
    private final ContextTracker contexts;
    private final LiveContextReader liveContexts;
    private final LiveSessions liveSessions;
    private final PageRequests pageRequests;
    private final WorkspaceLayout layout;
    private final FactoryConfiguration configuration;
    private final FixLoopLimits fixLoopLimits;
    private final Supplier<Optional<String>> definitionWarning;
    private final IntSupplier issuesInWork;
    private final Clock clock;

    public PageModelSource(BoardState board, UsageTracker usage, ContextTracker contexts,
            LiveContextReader liveContexts, LiveSessions liveSessions, PageRequests pageRequests,
            WorkspaceLayout layout, FactoryConfiguration configuration, DefinitionLookup definitions,
            Supplier<Optional<String>> definitionWarning, IntSupplier issuesInWork, Clock clock) {
        this.board = board;
        this.usage = usage;
        this.contexts = contexts;
        this.liveContexts = liveContexts;
        this.liveSessions = liveSessions;
        this.pageRequests = pageRequests;
        this.layout = layout;
        this.configuration = configuration;
        this.fixLoopLimits = new FixLoopLimits(definitions);
        this.definitionWarning = definitionWarning;
        this.issuesInWork = issuesInWork;
        this.clock = clock;
    }

    /**
     * Reads the values of the page for one viewer.
     *
     * @param viewerOnThisMachine true when the browser runs on the machine of the worker, see {@link ViewerLocation}
     */
    public PageModel read(boolean viewerOnThisMachine) {
        BoardSnapshot snapshot = board.snapshot();
        return new PageModel(snapshot, clock.instant(), issuesInWork.getAsInt(),
                configuration.limits().parallelIssues(), readFixLoopLimits(snapshot),
                configuration.isolationMode(), configuration.limits().pollInterval(), usage.windows(),
                usage.resumesAt(), readContexts(snapshot), readContinueRequests(snapshot), readRememberedLabels(snapshot),
                readEnterPlaces(snapshot), configuration.repository().fullName(), viewerOnThisMachine,
                definitionWarning.get());
    }

    private Map<Integer, Integer> readFixLoopLimits(BoardSnapshot snapshot) {
        Map<Integer, Integer> limitsByIssue = new HashMap<>();
        for (IssueCard card : snapshot.cards()) {
            limitsByIssue.put(card.issueNumber(), fixLoopLimits.limitOf(card.record()));
        }
        return limitsByIssue;
    }

    private Map<Integer, ContextUsage> readContexts(BoardSnapshot snapshot) {
        Map<Integer, ContextUsage> contextsByIssue = new HashMap<>();
        for (IssueCard card : snapshot.cards()) {
            readContext(card).ifPresent(context -> contextsByIssue.put(card.issueNumber(), context));
        }
        return contextsByIssue;
    }

    /** A live card uses the tokens of its session file over the context window of the last phase. */
    private Optional<ContextUsage> readContext(IssueCard card) {
        Optional<ContextUsage> tracked = contexts.find(card.issueNumber());
        if (tracked.isEmpty() || card.record().live().isEmpty()) {
            return tracked;
        }
        Optional<Long> liveTokens = liveContexts.readTokensInUse(card.record().sessionId(),
                layout.worktree(card.issueNumber()));
        return liveTokens.map(tokens -> new ContextUsage(tokens, tracked.get().contextWindow())).or(() -> tracked);
    }

    private Map<Integer, Set<String>> readRememberedLabels(BoardSnapshot snapshot) {
        Map<Integer, Set<String>> labelsByIssue = new HashMap<>();
        for (IssueCard card : snapshot.cards()) {
            Set<String> labels = PageAction.findRememberedLabels(card.issueNumber(), pageRequests);
            if (!labels.isEmpty()) {
                labelsByIssue.put(card.issueNumber(), labels);
            }
        }
        return labelsByIssue;
    }

    private Map<Integer, LivePlace> readEnterPlaces(BoardSnapshot snapshot) {
        Map<Integer, LivePlace> placesByIssue = new HashMap<>();
        for (IssueCard card : snapshot.cards()) {
            pageRequests.findEnterPlace(card.issueNumber())
                    .ifPresent(place -> placesByIssue.put(card.issueNumber(), place));
        }
        return placesByIssue;
    }

    private Set<Integer> readContinueRequests(BoardSnapshot snapshot) {
        return snapshot.cards().stream()
                .filter(card -> card.record().live().isPresent())
                .map(IssueCard::issueNumber)
                .filter(liveSessions::isContinueRequested)
                .collect(Collectors.toSet());
    }
}
