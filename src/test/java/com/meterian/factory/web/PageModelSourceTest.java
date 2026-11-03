package com.meterian.factory.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.meterian.factory.agent.ContextUsage;
import com.meterian.factory.agent.LiveContextReader;
import com.meterian.factory.agent.UsageWindow;
import com.meterian.factory.board.BoardState;
import com.meterian.factory.configuration.ClaudeSettings;
import com.meterian.factory.configuration.FactoryConfiguration;
import com.meterian.factory.configuration.IsolationMode;
import com.meterian.factory.configuration.Limits;
import com.meterian.factory.configuration.TargetRepository;
import com.meterian.factory.definition.BuiltInDefinition;
import com.meterian.factory.definition.DefinitionLookup;
import com.meterian.factory.definition.FactoryDefinition;
import com.meterian.factory.github.Issue;
import com.meterian.factory.state.IssueRecord;
import com.meterian.factory.state.IssueStatus;
import com.meterian.factory.state.LivePlace;
import com.meterian.factory.state.LiveSession;
import com.meterian.factory.state.WaitReason;
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
    private final UsageTracker usage = new UsageTracker(0.90, clock);
    private final ContextTracker contexts = new ContextTracker();
    private final FakeLiveSessions liveSessions = new FakeLiveSessions();
    private final PageRequests pageRequests = new PageRequests(clock);
    private DefinitionLookup definitions = DefinitionLookup.builtInOnly(DefinitionCommits.builtInWithFixLoops(4));
    private Optional<String> definitionWarning = Optional.empty();

    private PageModelSource source() {
        Path workDirectory = temporaryDirectory.resolve("work");
        TargetRepository target = new TargetRepository("MeterianHQ", "target", temporaryDirectory.resolve("clone"),
                "true", Map.of());
        FactoryConfiguration configuration = new FactoryConfiguration(target, "main",
                new Limits(3, 4, Duration.ofSeconds(45), 0.90), List.of("bbossola"), IsolationMode.SAME_USER,
                ClaudeSettings.createDefaults(), workDirectory, temporaryDirectory.resolve("gatekeeper.git"),
                Optional.empty(), temporaryDirectory.resolve("live"), Optional.empty(),
                FactoryConfiguration.DEFAULT_TERMINAL_COMMAND);
        return new PageModelSource(board, usage, contexts, new LiveContextReader(claudeHome()), liveSessions,
                pageRequests, new WorkspaceLayout(workDirectory), configuration, definitions, () -> definitionWarning,
                () -> 1, clock);
    }

    private Path claudeHome() {
        return temporaryDirectory.resolve("claude-home");
    }

    private static IssueRecord liveRecord(int number) {
        return IssueRecord.queued(number).withAgentSessionStarted().withStatus(IssueStatus.WAITS_FOR_YOU)
                .withWaitReason(WaitReason.LIVE_CONVERSATION)
                .withLive(new LiveSession("abc", IssueStatus.IN_PROGRESS, Optional.empty(), Optional.empty(),
                        LivePlace.PHONE));
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
        board.recordIssue(new Issue(12, "Title", "body", Set.of("factory")), IssueRecord.queued(12));

        PageModel model = source().read(true);

        assertThat(model.renderedAt()).isEqualTo(NOW);
        assertThat(model.issuesInWork()).isEqualTo(1);
        assertThat(model.parallelLimit()).isEqualTo(3);
        assertThat(model.fixLoopLimitOf(12)).isEqualTo(4);
        assertThat(model.pollInterval()).isEqualTo(Duration.ofSeconds(45));
        assertThat(model.isolationMode()).isEqualTo(IsolationMode.SAME_USER);
        assertThat(model.repositoryFullName()).isEqualTo("MeterianHQ/target");
        assertThat(model.usageWindows()).extracting(UsageWindow::name).containsExactly("seven_day");
        assertThat(model.usageResumesAt()).contains(NOW.plus(Duration.ofDays(1)));
    }

    @Test
    void showsTheFixLoopLimitOfTheDefinitionOfTheRecord() {
        IssueRecord record = IssueRecord.queued(12).withDefinitionCommit("1234567890");
        FactoryDefinition oneFixLoop = DefinitionCommits.builtInWithFixLoops(1);
        definitions = other -> other.definitionCommit().isPresent() ? oneFixLoop : BuiltInDefinition.load();
        board.recordIssue(new Issue(12, "Title", "body", Set.of("factory")), record);

        PageModel model = source().read(true);

        assertThat(model.fixLoopLimitOf(12)).isEqualTo(1);
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
        board.recordIssue(new Issue(12, "Title", "body", Set.of("factory")), IssueRecord.queued(12));
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
    void readsTheRememberedLabelsOfEachCard() {
        board.recordIssue(new Issue(12, "Title", "body", Set.of("factory")), IssueRecord.queued(12));
        board.recordIssue(new Issue(13, "Title", "body", Set.of("factory")), IssueRecord.queued(13));
        pageRequests.remember(12, "factory:approve");
        pageRequests.remember(12, "factory:block");
        pageRequests.remember(99, "factory:enter");

        PageModel model = source().read(true);

        assertThat(model.rememberedLabels()).containsOnlyKeys(12)
                .containsEntry(12, Set.of("factory:approve", "factory:block"));
    }

    @Test
    void readsTheEnterPlaceOfEachCard() {
        board.recordIssue(new Issue(12, "Title", "body", Set.of("factory")), IssueRecord.queued(12));
        board.recordIssue(new Issue(13, "Title", "body", Set.of("factory")), IssueRecord.queued(13));
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
