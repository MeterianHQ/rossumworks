package com.meterian.factory.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.meterian.factory.agent.ContextUsage;
import com.meterian.factory.agent.LiveContextReader;
import com.meterian.factory.agent.UsageWindow;
import com.meterian.factory.board.BoardState;
import com.meterian.factory.board.FactorySwitch;
import com.meterian.factory.board.FactoryState;
import com.meterian.factory.configuration.ClaudeSettings;
import com.meterian.factory.configuration.FactoryConfiguration;
import com.meterian.factory.configuration.IsolationMode;
import com.meterian.factory.configuration.RepositoryName;
import com.meterian.factory.configuration.WorkerLimits;
import com.meterian.factory.definition.BuiltInDefinition;
import com.meterian.factory.definition.DefinitionLookup;
import com.meterian.factory.definition.FactoryDefinition;
import com.meterian.factory.github.Issue;
import com.meterian.factory.state.IssueRecord;
import com.meterian.factory.testing.RecordStates;
import com.meterian.factory.state.LivePlace;
import com.meterian.factory.state.LiveSession;
import com.meterian.factory.testing.DefinitionCommits;
import com.meterian.factory.testing.FakeLiveSessions;
import com.meterian.factory.testing.MutableClock;
import com.meterian.factory.usage.ContextTracker;
import com.meterian.factory.usage.UsageTracker;
import com.meterian.factory.workflow.PageRequests;
import com.meterian.factory.workflow.WorkspaceLayout;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PageModelSourceTest {

    private static final Instant NOW = Instant.parse("2026-11-02T10:00:00Z");

    @TempDir
    Path temporaryDirectory;

    private final MutableClock clock = new MutableClock(NOW);
    private final BoardState board = new BoardState(clock);
    private FactorySwitch factorySwitch;
    private final UsageTracker usage = new UsageTracker(0.90, clock);
    private final ContextTracker contexts = new ContextTracker();
    private final FakeLiveSessions liveSessions = new FakeLiveSessions();
    private final PageRequests pageRequests = new PageRequests(clock);
    private DefinitionLookup definitions = DefinitionLookup.builtInOnly(DefinitionCommits.builtInWithImplementLimit(4));
    private Optional<String> definitionWarning = Optional.empty();
    private int usedSlots = 1;
    private int runningWork = 1;

    @Test
    void readsTheStateOfTheFactory() {
        PageModelSource source = source();
        assertThat(source.read(true).factoryState()).isEqualTo(FactoryState.RUNNING);

        factorySwitch.requestStop();

        assertThat(source.read(true).factoryState()).isEqualTo(FactoryState.STOPPING);
    }

    @Test
    void aStopDoesNotWaitForACardThatWaitsForYou() {
        usedSlots = 1;
        runningWork = 0;
        PageModelSource source = source();

        factorySwitch.requestStop();

        PageModel model = source.read(true);
        assertThat(model.factoryState()).isEqualTo(FactoryState.STOPPED);
        assertThat(model.issuesInWork()).isEqualTo(1);
    }

    @Test
    void showsTheColumnsInTheOrderOfTheDefinition() {
        definitions = DefinitionLookup.builtInOnly(BuiltInDefinition.load());

        List<String> columns = source().read(true).columns();

        assertThat(columns).containsExactly("queued", "in-progress", "in-review", "waits-for-you", "blocked", "done");
    }

    private PageModelSource source() {
        Path workDirectory = temporaryDirectory.resolve("work");
        factorySwitch = new FactorySwitch(workDirectory);
        FactoryConfiguration configuration = new FactoryConfiguration(new RepositoryName("MeterianHQ", "target"),
                workDirectory, temporaryDirectory.resolve("gatekeeper.git"), temporaryDirectory.resolve("live"),
                new WorkerLimits(3, Duration.ofSeconds(45), 0.90), Map.of(), IsolationMode.SAME_USER,
                ClaudeSettings.createDefaults(), Optional.empty(), FactoryConfiguration.DEFAULT_TERMINAL_COMMAND);
        return new PageModelSource(board, factorySwitch, usage, usage, contexts, new LiveContextReader(claudeHome()), liveSessions, true,
                pageRequests, new WorkspaceLayout(workDirectory), configuration, definitions, () -> definitionWarning,
                () -> usedSlots, () -> runningWork, clock);
    }

    private Path claudeHome() {
        return temporaryDirectory.resolve("claude-home");
    }

    private static IssueRecord liveRecord(int number) {
        return RecordStates.move(RecordStates.queued(number).withAgentSessionStarted(), "live")
                .withLive(new LiveSession("abc", Optional.empty(),
                        LivePlace.GITHUB_COMMENT));
    }

    private void writeSessionFile(IssueRecord record, long inputTokens) throws IOException {
        Path worktree = temporaryDirectory.resolve("work").resolve("issue-" + record.issueNumber());
        String folderName = worktree.toAbsolutePath().toString().replaceAll("[^A-Za-z0-9]", "-");
        Path folder = Files.createDirectories(claudeHome().resolve("projects").resolve(folderName));
        Files.writeString(folder.resolve(record.sessionId() + ".jsonl"),
                "{\"type\":\"assistant\",\"message\":{\"usage\":{\"input_tokens\":" + inputTokens
                        + ",\"cache_read_input_tokens\":0,\"cache_creation_input_tokens\":0}}}\n");
    }

    @Test
    void readsTheValuesOfTheWorkerAndTheConfiguration() {
        usage.record(List.of(new UsageWindow("seven_day", 0.95, NOW.plus(Duration.ofDays(1)), "allowed", null)));
        board.recordIssue(new Issue(12, "Title", "body", Set.of("factory")), RecordStates.queued(12));

        PageModel model = source().read(true);

        assertThat(model.renderedAt()).isEqualTo(NOW);
        assertThat(model.issuesInWork()).isEqualTo(1);
        assertThat(model.parallelLimit()).isEqualTo(3);
        assertThat(model.limitsOf(12)).containsExactly(Map.entry("implement", 4), Map.entry("review", 10));
        assertThat(model.pollInterval()).isEqualTo(Duration.ofSeconds(45));
        assertThat(model.isolationMode()).isEqualTo(IsolationMode.SAME_USER);
        assertThat(model.repositoryFullName()).isEqualTo("MeterianHQ/target");
        assertThat(model.usageWindows()).extracting(UsageWindow::name).containsExactly("seven_day");
        assertThat(model.phasesResumeAt()).contains(NOW.plus(Duration.ofDays(1)));
    }

    @Test
    void showsTheLimitsOfTheDefinitionOfTheRecord() {
        IssueRecord record = RecordStates.queued(12).withDefinitionVersion("1234567890");
        FactoryDefinition oneEntry = DefinitionCommits.builtInWithImplementLimit(1);
        definitions = other -> other.definitionVersion().isPresent() ? oneEntry : BuiltInDefinition.load();
        board.recordIssue(new Issue(12, "Title", "body", Set.of("factory")), record);

        PageModel model = source().read(true);

        assertThat(model.limitsOf(12)).containsEntry("implement", 1);
    }

    @Test
    void readsTheIssuesInTheBlockTarget() {
        board.recordIssue(new Issue(12, "Title", "body", Set.of("factory")),
                RecordStates.move(RecordStates.queued(12), "blocked"));
        board.recordIssue(new Issue(13, "Title", "body", Set.of("factory")), RecordStates.queued(13));

        PageModel model = source().read(true);

        assertThat(model.isInBlockTarget(12)).isTrue();
        assertThat(model.isInBlockTarget(13)).isFalse();
    }

    @Test
    void readsTheWarningOfTheDefinition() {
        definitionWarning = Optional.of("The default branch has no .factory that loads: x. "
                + "The factory takes no new issue.");

        PageModel model = source().read(true);

        assertThat(model.definitionWarning()).isEqualTo(definitionWarning);
    }

    @Test
    void aCardThatIsNotLiveShowsTheTrackedContext() {
        board.recordIssue(new Issue(12, "Title", "body", Set.of("factory")), RecordStates.queued(12));
        contexts.record(12, new ContextUsage(100, 1000));

        PageModel model = source().read(true);

        assertThat(model.contexts()).containsEntry(12, new ContextUsage(100, 1000));
    }

    @Test
    void aLiveCardReadsTheTokensFromTheSessionFile() throws IOException {
        IssueRecord record = liveRecord(12);
        board.recordIssue(new Issue(12, "Title", "body", Set.of("factory")), record);
        contexts.record(12, new ContextUsage(100, 1000));
        writeSessionFile(record, 620);

        PageModel model = source().read(true);

        assertThat(model.contexts()).containsEntry(12, new ContextUsage(620, 1000));
    }

    @Test
    void aLiveCardWithoutASessionFileKeepsTheTrackedContext() {
        board.recordIssue(new Issue(12, "Title", "body", Set.of("factory")), liveRecord(12));
        contexts.record(12, new ContextUsage(100, 1000));

        PageModel model = source().read(true);

        assertThat(model.contexts()).containsEntry(12, new ContextUsage(100, 1000));
    }

    @Test
    void readsTheContinueMarkerOfALiveCard() {
        board.recordIssue(new Issue(12, "Title", "body", Set.of("factory")), liveRecord(12));
        board.recordIssue(new Issue(13, "Title", "body", Set.of("factory")), liveRecord(13));
        liveSessions.requestContinue(12);

        PageModel model = source().read(true);

        assertThat(model.continueRequested()).containsExactly(12);
    }

    @Test
    void readsTheContinueMarkerOfACardWithoutAnActionOnly() {
        board.recordIssue(new Issue(12, "Title", "body", Set.of("factory")),
                RecordStates.move(RecordStates.queued(12), "blocked"));
        board.recordIssue(new Issue(13, "Title", "body", Set.of("factory")),
                RecordStates.move(RecordStates.queued(13), "implement"));
        liveSessions.requestContinue(12);
        liveSessions.requestContinue(13);

        PageModel model = source().read(true);

        assertThat(model.continueRequested()).containsExactly(12);
    }

    @Test
    void readsTheRememberedLabelsOfEachCard() {
        board.recordIssue(new Issue(12, "Title", "body", Set.of("factory")), RecordStates.queued(12));
        board.recordIssue(new Issue(13, "Title", "body", Set.of("factory")), RecordStates.queued(13));
        pageRequests.remember(12, "factory:approve");
        pageRequests.remember(12, "factory:block");
        pageRequests.remember(99, "factory:enter");

        PageModel model = source().read(true);

        assertThat(model.rememberedLabels()).containsOnlyKeys(12)
                .containsEntry(12, Set.of("factory:approve", "factory:block"));
    }

    @Test
    void readsTheEnterPlaceOfEachCard() {
        board.recordIssue(new Issue(12, "Title", "body", Set.of("factory")), RecordStates.queued(12));
        board.recordIssue(new Issue(13, "Title", "body", Set.of("factory")), RecordStates.queued(13));
        pageRequests.rememberEnterPlace(12, LivePlace.TERMINAL);
        pageRequests.rememberEnterPlace(99, LivePlace.BROWSER);

        PageModel model = source().read(true);

        assertThat(model.enterPlaces()).containsExactly(Map.entry(12, LivePlace.TERMINAL));
    }

    @Test
    void keepsTheLocationOfTheViewer() {
        assertThat(source().read(true).viewerOnThisMachine()).isTrue();
        assertThat(source().read(false).viewerOnThisMachine()).isFalse();
    }
}
