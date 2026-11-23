package com.meterian.factory.worker;

import static com.meterian.factory.testing.TemporaryRepository.commitFile;
import static com.meterian.factory.testing.DefinitionCommits.builtInWithBuild;
import static com.meterian.factory.testing.DefinitionCommits.commitDefinition;
import static com.meterian.factory.testing.DefinitionCommits.mainFileFor;
import static com.meterian.factory.testing.TemporaryRepository.createBare;
import static com.meterian.factory.testing.TemporaryRepository.createClone;
import static com.meterian.factory.testing.TemporaryRepository.run;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import com.meterian.factory.agent.PhaseRequest;
import com.meterian.factory.board.BoardState;
import com.meterian.factory.board.BoardUpdates;
import com.meterian.factory.board.IssueCard;
import com.meterian.factory.board.FactorySwitch;
import com.meterian.factory.build.BuildRunner;
import com.meterian.factory.configuration.ClaudeSettings;
import com.meterian.factory.configuration.FactoryConfiguration;
import com.meterian.factory.configuration.IsolationMode;
import com.meterian.factory.configuration.RepositoryName;
import com.meterian.factory.configuration.WorkerLimits;
import com.meterian.factory.git.Gatekeeper;
import com.meterian.factory.git.Git;
import com.meterian.factory.git.ProcessGit;
import com.meterian.factory.definition.DefinitionVersions;
import com.meterian.factory.github.GitHubException;
import com.meterian.factory.github.Issue;
import com.meterian.factory.github.IssueComment;
import com.meterian.factory.ownership.GitHubOwnershipGuard;
import com.meterian.factory.ownership.LostIssues;
import com.meterian.factory.ownership.OwnershipGuard;
import com.meterian.factory.state.HandOver;
import com.meterian.factory.state.IssueRecord;
import com.meterian.factory.state.LiveSession;
import com.meterian.factory.state.StatusComment;
import com.meterian.factory.state.Verdict;
import com.meterian.factory.state.ActionType;
import com.meterian.factory.testing.CollectedEvents;
import com.meterian.factory.testing.FakeAgentRunner;
import com.meterian.factory.testing.FakeGitHubClient;
import com.meterian.factory.testing.FakeLiveSessions;
import com.meterian.factory.testing.MutableClock;
import com.meterian.factory.testing.RecordStates;
import com.meterian.factory.usage.ContextTracker;
import com.meterian.factory.usage.UsageTracker;
import com.meterian.factory.workflow.IssueText;
import com.meterian.factory.workflow.IssueWorkflow;
import com.meterian.factory.workflow.PageRequests;
import com.meterian.factory.workflow.WorkspaceLayout;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkerTest {

    private static final int ISSUE = 7;
    private static final int OTHER_ISSUE = 8;
    private static final String WORKER_LOGIN = "factory-bot";
    private static final String ALICE = "alice";
    private static final String STOP_COMMENT_START = "The factory of `factory-bot` stops work on this issue";
    private static final String FACTORY_LABEL = "factory";
    private static final String BLOCK_LABEL = "factory:block";
    private static final String APPROVE_LABEL = "factory:approve";
    private static final String ENTER_LABEL = "factory:enter";
    private static final String START_AGAIN_LABEL = "factory:start-again";
    private static final String ENDED_NOTE = "The live conversation ended without /factory:continue."
            + " Add the label factory:enter to open a new one.";
    private static final String DONE_BLOCK_NOTE = "The issue is already done; the block has no effect.";
    private static final String MERGED_NOTE = "The pull request is merged.";
    private static final String FINISH_FAILURE_NOTE =
            "An action after the merge failed: java.lang.IllegalStateException. The change is merged.";
    private static final String BROKEN_RECORD_NOTE =
            "The status record of the factory is broken. The factory does not work on this issue.";
    private static final Duration TIME_BOUND = Duration.ofSeconds(30);
    private static final Map<String, Integer> LIMITS = Map.of("implement", 3, "review", 10);
    /** A static reference: the logging framework keeps only a weak reference to a logger. */
    private static final Logger WORKER_LOGGER = Logger.getLogger(Worker.class.getName());

    @TempDir
    Path temporaryDirectory;

    private FactorySwitch factorySwitch;

    private final PageRequests pageRequests = new PageRequests(Clock.systemUTC());
    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-01T09:00:00Z"));
    private final BoardState board = new BoardState(clock);

    private final Git git = new ProcessGit();
    private final CollectedEvents events = new CollectedEvents();
    private final FakeGitHubClient gitHub = new FakeGitHubClient();
    private final FakeLiveSessions liveSessions = new FakeLiveSessions();
    private final List<ExecutorService> executors = new ArrayList<>();
    private String buildCommand = "true";
    private Path clone;

    /** The tests cause the failures on purpose, so the log of the worker is only noise. */
    @BeforeAll
    static void silenceTheWorkerLog() {
        WORKER_LOGGER.setLevel(Level.OFF);
    }

    @BeforeEach
    void createRepositoriesAndIssue() {
        Path origin = createBare(temporaryDirectory.resolve("origin.git"));
        clone = createClone(origin, temporaryDirectory.resolve("work").resolve("repository"));
        Path githubRepository = createBare(temporaryDirectory.resolve("github.git"));
        Path gatekeeper = temporaryDirectory.resolve("gatekeeper.git");
        String targetUrl = createRepositoryName().sshUrl();
        Gatekeeper.ensure(gatekeeper, targetUrl);
        // The gatekeeper forwards to the local "github" repository, not to the network.
        run(gatekeeper, "config", "url." + githubRepository + ".insteadOf", targetUrl);
        gitHub.setAuthenticatedLogin(WORKER_LOGIN);
        gitHub.readPullRequestHeadsFrom(githubRepository);
        addLabeledIssue(ISSUE);
    }

    @AfterEach
    void stopTheExecutors() throws InterruptedException {
        for (ExecutorService executor : executors) {
            executor.shutdownNow();
            executor.awaitTermination(TIME_BOUND.toSeconds(), TimeUnit.SECONDS);
        }
    }

    private FactorySwitch factorySwitch() {
        if (factorySwitch == null) {
            factorySwitch = new FactorySwitch(temporaryDirectory.resolve("work"));
        }
        return factorySwitch;
    }

    private void addLabeledIssue(int number) {
        gitHub.addIssue(number, "Change the greeting " + number, "The greeting must change.");
        gitHub.addLabel(number, FACTORY_LABEL);
    }

    private RepositoryName createRepositoryName() {
        return new RepositoryName("MeterianHQ", "sample");
    }

    private FactoryConfiguration createConfiguration(int parallelIssues, Duration pollInterval) {
        return new FactoryConfiguration(createRepositoryName(),
                temporaryDirectory.resolve("work"), temporaryDirectory.resolve("gatekeeper.git"),
                temporaryDirectory.resolve("live"), new WorkerLimits(parallelIssues, pollInterval, 0.90), Map.of(),
                IsolationMode.SAME_USER, ClaudeSettings.createDefaults(), Optional.empty(),
                FactoryConfiguration.DEFAULT_TERMINAL_COMMAND);
    }

    private Worker createWorker(FakeAgentRunner agent, int parallelIssues) {
        return createWorker(agent, createConfiguration(parallelIssues, Duration.ofSeconds(60)));
    }

    private Worker createWorker(FakeAgentRunner agent, FactoryConfiguration configuration) {
        return createWorker(agent, configuration, board);
    }

    /** The default branch gets a .factory with the build command of the configuration. */
    private Worker createWorker(FakeAgentRunner agent, FactoryConfiguration configuration, BoardUpdates board) {
        commitDefinition(clone, mainFileFor(buildCommand));
        return createWorker(agent, configuration, board, createVersions(configuration));
    }

    private DefinitionVersions createVersions(FactoryConfiguration configuration) {
        return new DefinitionVersions(git, clone, "main", builtInWithBuild(buildCommand));
    }

    private Worker createWorker(FakeAgentRunner agent, FactoryConfiguration configuration, BoardUpdates board,
                                DefinitionVersions versions) {
        BuildRunner buildRunner = new BuildRunner();
        OwnershipGuard ownershipGuard = new GitHubOwnershipGuard(gitHub, WORKER_LOGIN);
        LostIssues lostIssues = new LostIssues();
        IssueWorkflow workflow = new IssueWorkflow(configuration, gitHub, git, agent, versions,
                buildRunner, Duration.ZERO,
                new UsageTracker(0.90, Clock.systemUTC()),
                new WorkspaceLayout(configuration.workDirectory()), WORKER_LOGIN, liveSessions,
                board, new ContextTracker(), pageRequests,
                com.meterian.factory.workflow.RetrySettings.defaults(), ownershipGuard, lostIssues,
                events.workerEvents());
        ExecutorService executor = Executors.newFixedThreadPool(Math.max(1, configuration.limits().parallelIssues()));
        executors.add(executor);
        PhaseStopper phaseStopper = new PhaseStopper(agent, buildRunner,
                new WorkspaceLayout(configuration.workDirectory()), liveSessions);
        return new Worker(configuration, gitHub, workflow, versions, phaseStopper, pageRequests, WORKER_LOGIN,
                executor, liveSessions, Clock.systemUTC(), board, factorySwitch(), lostIssues,
                events.workerEvents());
    }

    /** Waits until the loops of the worker end. The worker gets no new loop after this call. */
    private void waitForTheLoops() throws InterruptedException {
        ExecutorService executor = executors.getLast();
        executor.shutdown();
        assertThat(executor.awaitTermination(TIME_BOUND.toSeconds(), TimeUnit.SECONDS)).isTrue();
    }

    private static void waitUntil(BooleanSupplier condition) throws InterruptedException {
        Instant deadline = Instant.now().plus(TIME_BOUND);
        while (!condition.getAsBoolean()) {
            assertThat(Instant.now()).isBefore(deadline);
            Thread.sleep(20);
        }
    }

    private Optional<IssueRecord> readRecord(int issueNumber) {
        return StatusComment.findRecord(gitHub.commentsOf(issueNumber), WORKER_LOGIN);
    }

    private static Optional<IssueCard> findCard(BoardState board, int issueNumber) {
        return board.snapshot().cards().stream().filter(card -> card.issueNumber() == issueNumber).findFirst();
    }

    private List<IssueComment> statusComments(int issueNumber) {
        return gitHub.commentsOf(issueNumber).stream()
                .filter(comment -> comment.body().contains("<!-- factory-record"))
                .toList();
    }

    private Path worktree() {
        return temporaryDirectory.resolve("work").resolve("issue-" + ISSUE);
    }

    /** Worker 1 runs the implement and the build, then GitHub fails in the first review and the worker ends. */
    private FakeAgentRunner stopAWorkerDuringTheReview() throws InterruptedException {
        FakeAgentRunner firstAgent = new FakeAgentRunner().thenChangeFile().then(request -> {
            throw new GitHubException("GitHub answered 502 for GET /issues/7");
        });
        createWorker(firstAgent, 2).pollOnce();
        waitForTheLoops();
        assertThat(readRecord(ISSUE).orElseThrow().state()).isEqualTo("review");
        return firstAgent;
    }

    @Test
    void thePollPutsTheIssuesOnTheBoard() {
        addLabeledIssue(1);
        Worker worker = createWorker(new FakeAgentRunner(), 0);

        worker.pollOnce();

        IssueCard card = findCard(board, 1).orElseThrow();
        assertThat(card.title()).isEqualTo("Change the greeting 1");
        assertThat(card.record().column()).isEqualTo("queued");
        assertThat(board.snapshot().lastCheckAt()).contains(clock.instant());
    }

    @Test
    void takesNoNewIssueWithoutAGoodDefinition() {
        FactoryConfiguration configuration = createConfiguration(2, Duration.ofSeconds(60));
        Worker worker = createWorker(new FakeAgentRunner(), configuration, board, createVersions(configuration));

        worker.pollOnce();

        assertThat(readRecord(ISSUE)).isEmpty();
        assertThat(gitHub.commentsOf(ISSUE)).isEmpty();
        assertThat(worker.issuesInWork()).isEmpty();
    }

    @Test
    void givesTheIntakeTheCurrentCommit() {
        String commit = commitDefinition(clone, mainFileFor("true"));
        FactoryConfiguration configuration = createConfiguration(0, Duration.ofSeconds(60));
        Worker worker = createWorker(new FakeAgentRunner(), configuration, board, createVersions(configuration));

        worker.pollOnce();

        assertThat(readRecord(ISSUE).orElseThrow().definitionCommit()).contains(commit);
    }

    @Test
    void blocksARecordWhoseDefinitionDoesNotLoadAndStartsTheNextIssue() throws InterruptedException {
        String broken = commitDefinition(clone, "version: 1\nworkflow: [\n");
        IssueRecord unloadable = RecordStates.move(RecordStates.queued(ISSUE).withAgentSessionStarted(), "implement")
                .withDefinitionCommit(broken);
        gitHub.addCommentBy(ISSUE, WORKER_LOGIN, StatusComment.render(unloadable, LIMITS, RecordStates.REVIEW_NAMES));
        addLabeledIssue(OTHER_ISSUE);
        Worker worker = createWorker(new FakeAgentRunner().thenChangeFile().thenApproveAllReviews()
                .thenAssessRisk("low", true), 2);

        worker.pollOnce();
        waitForTheLoops();

        IssueRecord blocked = readRecord(ISSUE).orElseThrow();
        assertThat(blocked.state()).isEqualTo("implement");
        assertThat(blocked.action()).isEmpty();
        assertThat(blocked.column()).isEqualTo("blocked");
        assertThat(blocked.notes()).anySatisfy(note -> assertThat(note)
                .startsWith("The definition " + broken.substring(0, 7) + " of the issue does not load: "));
        assertThat(readRecord(OTHER_ISSUE).orElseThrow().column()).isNotEqualTo("queued");
        assertThat(worker.issuesInWork()).isEmpty();
    }

    @Test
    void thePollAfterARestartPutsTheKnownIssuesOnTheBoard() {
        createWorker(new FakeAgentRunner(), 0).pollOnce();
        BoardState boardAfterRestart = new BoardState(clock);
        Worker restartedWorker = createWorker(new FakeAgentRunner(),
                createConfiguration(0, Duration.ofSeconds(60)), boardAfterRestart);

        restartedWorker.pollOnce();

        assertThat(findCard(boardAfterRestart, ISSUE).orElseThrow().record())
                .isEqualTo(readRecord(ISSUE).orElseThrow());
    }

    @Test
    void anActionUpdatesTheCardBetweenPolls() throws InterruptedException {
        Worker worker = createWorker(new FakeAgentRunner().thenAskQuestion("Which greeting do you want?"), 2);

        worker.pollOnce();
        waitForTheLoops();

        IssueRecord onGitHub = readRecord(ISSUE).orElseThrow();
        assertThat(onGitHub.column()).isEqualTo("waits-for-you");
        assertThat(findCard(board, ISSUE).orElseThrow().record()).isEqualTo(onGitHub);
    }

    @Test
    void aFailedPollKeepsTheLastCheckTime() {
        Worker worker = createWorker(new FakeAgentRunner(), 0);
        worker.pollOnce();
        Instant firstCheck = clock.instant();
        clock.advance(Duration.ofMinutes(1));
        gitHub.failTheNextIssueList();

        worker.pollOnce();

        assertThat(board.snapshot().lastCheckAt()).contains(firstCheck);
    }

    @Test
    void takesALabeledIssueIntoTheQueue() {
        Worker worker = createWorker(new FakeAgentRunner(), 0);

        worker.pollOnce();
        worker.pollOnce();

        assertThat(gitHub.columnOf(ISSUE)).contains("queued");
        assertThat(statusComments(ISSUE)).hasSize(1);
        assertThat(readRecord(ISSUE).orElseThrow().column()).isEqualTo("queued");
        assertThat(readRecord(ISSUE).orElseThrow().issueTextHash())
                .contains(IssueText.computeHash(gitHub.issue(ISSUE)));
    }

    @Test
    void takesAnIssueWithTheFactoryLabelFromAnyAccountIntoTheQueue() {
        addLabeledIssue(8);
        Worker worker = createWorker(new FakeAgentRunner(), 0);

        worker.pollOnce();
        worker.pollOnce();

        assertThat(readRecord(8).orElseThrow().column()).isEqualTo("queued");
        assertThat(gitHub.columnOf(8)).contains("queued");
        assertThat(gitHub.commentsOf(8)).hasSize(1);
    }

    @Test
    void startsNoMoreThanTheParallelLimit() throws InterruptedException {
        addLabeledIssue(8);
        addLabeledIssue(9);
        FakeAgentRunner agent = new FakeAgentRunner()
                .thenWaitForStop().thatFails().thenWaitForStop().thatFails();
        Worker worker = createWorker(agent, 2);

        worker.pollOnce();
        waitUntil(() -> agent.waitingPhaseCount() == 1);
        worker.pollOnce();

        assertThat(worker.issuesInWork()).isEqualTo(Set.of(7, 8));
        assertThat(readRecord(9).orElseThrow().column()).isEqualTo("queued");
        agent.stopPhase(7);
        waitForTheLoops();
    }

    @Test
    void startsTheLowestIssueNumberFirst() throws InterruptedException {
        addLabeledIssue(3);
        addLabeledIssue(5);
        FakeAgentRunner agent = new FakeAgentRunner().thenWaitForStop().thatFails();
        Worker worker = createWorker(agent, 1);

        worker.pollOnce();

        assertThat(worker.issuesInWork()).isEqualTo(Set.of(3));
        waitUntil(() -> agent.waitingPhaseCount() == 1);
        agent.stopPhase(3);
        waitForTheLoops();
        assertThat(agent.requests()).extracting(PhaseRequest::issueNumber).containsExactly(3);
    }

    @Test
    void blockStopsTheRunningPhase() throws InterruptedException {
        FakeAgentRunner agent = new FakeAgentRunner().thenWaitForStop().thatFails();
        Worker worker = createWorker(agent, 2);
        worker.pollOnce();
        waitUntil(() -> agent.waitingPhaseCount() == 1);
        gitHub.addLabel(ISSUE, BLOCK_LABEL);

        worker.pollOnce();
        waitForTheLoops();

        IssueRecord record = readRecord(ISSUE).orElseThrow();
        assertThat(agent.stoppedIssues()).contains(ISSUE);
        assertThat(record.state()).isEqualTo("blocked");
        assertThat(record.newestNote()).contains("The owner blocked the issue.");
        assertThat(record.previousState()).contains("implement");
        assertThat(record.entriesOf("implement")).isZero();
        assertThat(record.notes()).noneMatch(note -> note.startsWith("The agent session failed"));
        assertThat(gitHub.columnOf(ISSUE)).contains("blocked");
        assertThat(gitHub.labelsOf(ISSUE)).doesNotContain(BLOCK_LABEL);
        assertThat(statusComments(ISSUE)).hasSize(1);
        assertThat(worker.issuesInWork()).isEmpty();
    }

    @Test
    void aBlockDuringTheSecondReviewRoundKeepsOnlyTheVerdictsOfThatRound() throws InterruptedException {
        FakeAgentRunner agent = new FakeAgentRunner().thenChangeFile()
                .thenApproveAllReviews(3).thenRejectReview("Bad style")
                .thenChangeFile().thenApproveReview().thenWaitForStop().thatFails();
        Worker worker = createWorker(agent, 2);
        worker.pollOnce();
        waitUntil(() -> agent.waitingPhaseCount() == 1);
        gitHub.addLabel(ISSUE, BLOCK_LABEL);

        worker.pollOnce();
        waitForTheLoops();

        IssueRecord record = readRecord(ISSUE).orElseThrow();
        assertThat(record.state()).isEqualTo("blocked");
        assertThat(record.reviews()).isEqualTo(Map.of("correctness", Verdict.APPROVE));
    }

    @Test
    void blocksAnIssueThatIsNotInWork() {
        Worker worker = createWorker(new FakeAgentRunner(), 0);
        worker.pollOnce();
        gitHub.addLabel(ISSUE, BLOCK_LABEL);

        worker.pollOnce();

        IssueRecord record = readRecord(ISSUE).orElseThrow();
        assertThat(record.state()).isEqualTo("blocked");
        assertThat(record.newestNote()).contains("The owner blocked the issue.");
        assertThat(gitHub.labelsOf(ISSUE)).doesNotContain(BLOCK_LABEL);
    }

    private IssueRecord writeBlockedRecordWithEarlierWork() {
        IssueRecord inBuild = RecordStates.move(readRecord(ISSUE).orElseThrow()
                .withAgentSessionStarted()
                .withEntryCounted("implement")
                .withEntryCounted("implement")
                .withBuiltTree("tree"), "build");
        IssueRecord blocked = RecordStates.move(inBuild, "blocked").withNewestNote("The owner blocked the issue.");
        gitHub.editComment(blocked.statusCommentId().orElseThrow(), StatusComment.render(blocked, LIMITS, RecordStates.REVIEW_NAMES));
        return blocked;
    }

    @Test
    void aStoppedFactoryLeavesANewIssueUntouched() {
        FakeAgentRunner agent = new FakeAgentRunner();
        Worker worker = createWorker(agent, 2);
        factorySwitch().requestStop();

        worker.pollOnce();

        assertThat(readRecord(ISSUE)).isEmpty();
        assertThat(gitHub.commentsOf(ISSUE)).isEmpty();
        assertThat(gitHub.columnOf(ISSUE)).isEmpty();
        assertThat(findCard(board, ISSUE)).isEmpty();
        assertThat(worker.issuesInWork()).isEmpty();
        assertThat(agent.requests()).isEmpty();
    }

    @Test
    void leavesAnIssueWithTheLabelHumanUntouched() {
        FakeAgentRunner agent = new FakeAgentRunner();
        Worker worker = createWorker(agent, 2);
        gitHub.addLabel(ISSUE, "human");

        worker.pollOnce();

        assertThat(readRecord(ISSUE)).isEmpty();
        assertThat(gitHub.commentsOf(ISSUE)).isEmpty();
        assertThat(gitHub.columnOf(ISSUE)).isEmpty();
        assertThat(gitHub.issue(ISSUE).assignees()).isEmpty();
        assertThat(findCard(board, ISSUE)).isEmpty();
        assertThat(worker.issuesInWork()).isEmpty();
        assertThat(agent.requests()).isEmpty();
    }

    @Test
    void thePollCreatesTheLabelHuman() {
        Worker worker = createWorker(new FakeAgentRunner(), 0);

        worker.pollOnce();

        assertThat(gitHub.createdLabels()).contains("human");
    }

    @Test
    void theLabelHumanEndsTheLoopAfterTheCurrentActionAndItsMove() throws InterruptedException {
        CountDownLatch phaseStarted = new CountDownLatch(1);
        CountDownLatch phaseMayEnd = new CountDownLatch(1);
        FakeAgentRunner agent = new FakeAgentRunner().then(request -> {
            phaseStarted.countDown();
            awaitQuietly(phaseMayEnd);
            Files.writeString(request.workingDirectory().resolve("work.txt"), "change in the phase\n");
        });
        Worker worker = createWorker(agent, 2);
        worker.pollOnce();
        awaitQuietly(phaseStarted);

        gitHub.addLabel(ISSUE, "human");
        phaseMayEnd.countDown();
        waitUntil(() -> worker.issuesInWork().isEmpty());
        worker.pollOnce();
        waitForTheLoops();

        IssueRecord record = readRecord(ISSUE).orElseThrow();
        assertThat(agent.stoppedIssues()).isEmpty();
        assertThat(agent.requests()).hasSize(1);
        assertThat(record.state()).isEqualTo("build");
        assertThat(findCard(board, ISSUE)).isEmpty();
    }

    @Test
    void anIssueStartsAtTheFirstPollAfterTheStart() throws InterruptedException {
        FakeAgentRunner agent = new FakeAgentRunner().thenChangeNothing();
        factorySwitch().requestStop();
        Worker worker = createWorker(agent, 2);
        worker.pollOnce();
        factorySwitch().start();
        worker.pollOnce();
        waitUntil(() -> !agent.requests().isEmpty());
        waitForTheLoops();
    }

    @Test
    void aStartedIssueGetsTheSlotBeforeAQueuedIssueWithALowerNumber() throws InterruptedException {
        createWorker(new FakeAgentRunner(), 0).pollOnce();
        IssueRecord started = RecordStates.move(readRecord(ISSUE).orElseThrow().withAgentSessionStarted(),
                "implement");
        gitHub.editComment(started.statusCommentId().orElseThrow(), StatusComment.render(started, LIMITS, RecordStates.REVIEW_NAMES));
        addLabeledIssue(3);
        FakeAgentRunner agent = new FakeAgentRunner().thenChangeNothing();
        Worker worker = createWorker(agent, 1);

        worker.pollOnce();
        waitForTheLoops();

        assertThat(agent.requests()).extracting(PhaseRequest::issueNumber).containsOnly(ISSUE);
        assertThat(readRecord(3).orElseThrow().column()).isEqualTo("queued");
    }

    @Test
    void aStoppedFactoryDoesNotStartAnIssueThatIsAlreadyStarted() {
        createWorker(new FakeAgentRunner(), 0).pollOnce();
        IssueRecord started = RecordStates.move(readRecord(ISSUE).orElseThrow().withAgentSessionStarted(),
                "implement");
        gitHub.editComment(started.statusCommentId().orElseThrow(), StatusComment.render(started, LIMITS, RecordStates.REVIEW_NAMES));
        FakeAgentRunner agent = new FakeAgentRunner().thenChangeNothing();
        Worker worker = createWorker(agent, 2);
        factorySwitch().requestStop();

        worker.pollOnce();

        assertThat(worker.issuesInWork()).isEmpty();
        assertThat(agent.requests()).isEmpty();
    }

    @Test
    void aStopEndsTheLoopAfterTheCurrentActionAndItsMove() throws InterruptedException {
        CountDownLatch phaseStarted = new CountDownLatch(1);
        CountDownLatch phaseMayEnd = new CountDownLatch(1);
        FakeAgentRunner agent = new FakeAgentRunner().then(request -> {
            phaseStarted.countDown();
            awaitQuietly(phaseMayEnd);
            Files.writeString(request.workingDirectory().resolve("work.txt"), "change in the phase\n");
        });
        Worker worker = createWorker(agent, 2);
        worker.pollOnce();
        awaitQuietly(phaseStarted);

        factorySwitch().requestStop();
        phaseMayEnd.countDown();
        waitUntil(() -> worker.issuesInWork().isEmpty());
        worker.pollOnce();
        waitForTheLoops();

        IssueRecord record = readRecord(ISSUE).orElseThrow();
        assertThat(agent.stoppedIssues()).isEmpty();
        assertThat(agent.requests()).hasSize(1);
        assertThat(record.state()).isEqualTo("build");
        assertThat(record.column()).isEqualTo("in-progress");
    }

    @Test
    void aStoppedIssueContinuesInItsNextStateAfterTheStart() throws InterruptedException {
        CountDownLatch phaseStarted = new CountDownLatch(1);
        CountDownLatch phaseMayEnd = new CountDownLatch(1);
        FakeAgentRunner agent = new FakeAgentRunner().then(request -> {
            phaseStarted.countDown();
            awaitQuietly(phaseMayEnd);
            Files.writeString(request.workingDirectory().resolve("work.txt"), "change in the phase\n");
        });
        Worker worker = createWorker(agent, 2);
        worker.pollOnce();
        awaitQuietly(phaseStarted);
        factorySwitch().requestStop();
        phaseMayEnd.countDown();
        waitUntil(() -> worker.issuesInWork().isEmpty());

        factorySwitch().start();
        worker.pollOnce();

        waitUntil(() -> !readRecord(ISSUE).orElseThrow().state().equals("build"));
        waitForTheLoops();
        assertThat(readRecord(ISSUE).orElseThrow().state()).isNotIn("prepare", "implement", "build");
    }

    @Test
    void startAgainSetsABlockedIssueBackToTheStart() {
        Worker worker = createWorker(new FakeAgentRunner(), 0);
        worker.pollOnce();
        IssueRecord blocked = writeBlockedRecordWithEarlierWork();
        gitHub.addLabel(ISSUE, START_AGAIN_LABEL);

        worker.pollOnce();

        IssueRecord record = readRecord(ISSUE).orElseThrow();
        assertThat(record.column()).isEqualTo("queued");
        assertThat(record.state()).isEqualTo("prepare");
        assertThat(record.action()).contains(ActionType.PREPARE);
        assertThat(record.definitionCommit()).isEqualTo(blocked.definitionCommit()).isPresent();
        assertThat(record.sessionId()).isNotEqualTo(blocked.sessionId());
        assertThat(record.agentSessionStarted()).isFalse();
        assertThat(record.entriesOf("implement")).isLessThanOrEqualTo(1);
        assertThat(record.builtTreeHash()).isEmpty();
        assertThat(record.notes()).contains("The owner started the issue again. The earlier work is discarded.");
        assertThat(gitHub.columnOf(ISSUE)).contains("queued");
        assertThat(gitHub.labelsOf(ISSUE)).doesNotContain(START_AGAIN_LABEL);
        assertThat(statusComments(ISSUE)).hasSize(1);
    }

    @Test
    void startAgainWritesTheMoveBackToTheStart() {
        Worker worker = createWorker(new FakeAgentRunner(), 0);
        worker.pollOnce();
        writeBlockedRecordWithEarlierWork();
        gitHub.addLabel(ISSUE, START_AGAIN_LABEL);

        worker.pollOnce();

        assertThat(events.ofType("move")).singleElement().satisfies(line -> {
            assertThat(line.path("from").asText()).isEqualTo("blocked");
            assertThat(line.path("to").asText()).isEqualTo("prepare");
            assertThat(line.path("column").asText()).isEqualTo("queued");
            assertThat(line.has("event")).isFalse();
        });
    }

    @Test
    void startAgainTakesTheCurrentVersionOfTheDefinition() {
        Worker worker = createWorker(new FakeAgentRunner(), 0);
        worker.pollOnce();
        IssueRecord blocked = writeBlockedRecordWithEarlierWork();
        String correction = commitDefinition(clone, mainFileFor("echo corrected"));
        assertThat(blocked.definitionCommit()).isPresent().isNotEqualTo(Optional.of(correction));
        gitHub.addLabel(ISSUE, START_AGAIN_LABEL);

        worker.pollOnce();

        IssueRecord record = readRecord(ISSUE).orElseThrow();
        assertThat(record.state()).isEqualTo("prepare");
        assertThat(record.definitionCommit()).contains(correction);
    }

    @Test
    void startAgainRunsTheAgentInANewSessionAndACleanWorktree() throws InterruptedException, IOException {
        AtomicBoolean strayFileSeen = new AtomicBoolean();
        Path strayFile = worktree().resolve("stray.txt");
        FakeAgentRunner agent = new FakeAgentRunner().thenWaitForStop().thatFails()
                .then(request -> strayFileSeen.set(Files.exists(strayFile)));
        Worker worker = createWorker(agent, 2);
        worker.pollOnce();
        waitUntil(() -> agent.waitingPhaseCount() == 1);
        gitHub.addLabel(ISSUE, BLOCK_LABEL);
        worker.pollOnce();
        waitUntil(() -> !gitHub.labelsOf(ISSUE).contains(BLOCK_LABEL));
        Files.writeString(strayFile, "a file of the earlier work\n");
        gitHub.addLabel(ISSUE, START_AGAIN_LABEL);

        waitUntil(() -> {
            worker.pollOnce();
            return agent.requests().size() >= 2;
        });
        waitForTheLoops();

        List<PhaseRequest> requests = agent.requests();
        assertThat(requests).hasSizeGreaterThanOrEqualTo(2);
        assertThat(requests.get(1).sessionId()).isNotEqualTo(requests.get(0).sessionId());
        assertThat(requests.get(1).newSession()).isTrue();
        assertThat(strayFileSeen).isFalse();
    }

    @Test
    void startAgainHasNoEffectOnAnIssueThatIsNotBlocked() {
        Worker worker = createWorker(new FakeAgentRunner(), 0);
        worker.pollOnce();
        IssueRecord queued = readRecord(ISSUE).orElseThrow();
        gitHub.addLabel(ISSUE, START_AGAIN_LABEL);

        worker.pollOnce();

        IssueRecord record = readRecord(ISSUE).orElseThrow();
        assertThat(record.sessionId()).isEqualTo(queued.sessionId());
        assertThat(record.notes()).contains(
                "Start again has no effect: it works only on a Blocked issue without a live conversation.");
        assertThat(gitHub.labelsOf(ISSUE)).doesNotContain(START_AGAIN_LABEL);
    }

    @Test
    void startAgainNeverMakesTheChangeOfAMergedIssueAgain() {
        Worker worker = createWorker(new FakeAgentRunner(), 0);
        worker.pollOnce();
        IssueRecord blocked = writeBlockedRecordWithEarlierWork()
                .withPullRequest(gitHub.addMergedPullRequest("factory/issue-" + ISSUE));
        gitHub.editComment(blocked.statusCommentId().orElseThrow(), StatusComment.render(blocked, LIMITS, RecordStates.REVIEW_NAMES));
        gitHub.addLabel(ISSUE, START_AGAIN_LABEL);

        worker.pollOnce();

        IssueRecord record = readRecord(ISSUE).orElseThrow();
        assertThat(record.action()).contains(ActionType.FINISH);
        assertThat(record.sessionId()).isEqualTo(blocked.sessionId());
        assertThat(record.notes()).contains("GitHub already merged the pull request, so the issue does not start again.");
        assertThat(gitHub.labelsOf(ISSUE)).doesNotContain(START_AGAIN_LABEL);
    }

    @Test
    void aBlockDuringTheMergeKeepsTheMerge() throws InterruptedException {
        FakeAgentRunner agent = new FakeAgentRunner()
                .thenChangeFile().thenApproveAllReviews().thenAssessRisk("low", true);
        Worker worker = createWorker(agent, 2);
        gitHub.whenSquashMerging(() -> {
            gitHub.addLabel(ISSUE, BLOCK_LABEL);
            worker.pollOnce();
        });

        worker.pollOnce();
        waitForTheLoops();

        assertThat(agent.stoppedIssues()).contains(ISSUE);
        assertThat(readRecord(ISSUE).orElseThrow().state()).isEqualTo("done");
        assertThat(gitHub.columnOf(ISSUE)).contains("done");
        assertThat(gitHub.labelsOf(ISSUE)).doesNotContain(BLOCK_LABEL);
        assertThat(gitHub.squashMergeCalls()).hasSize(1);
    }

    @Test
    void aFailedLabelRemovalAfterTheMergeKeepsTheIssueDone() throws InterruptedException {
        FakeAgentRunner agent = new FakeAgentRunner()
                .thenChangeFile().thenApproveAllReviews().thenAssessRisk("low", true);
        Worker worker = createWorker(agent, 2);
        gitHub.failTheRemoveLabelAfterTheMerge();
        gitHub.whenSquashMerging(() -> {
            gitHub.addLabel(ISSUE, BLOCK_LABEL);
            worker.pollOnce();
        });

        worker.pollOnce();
        waitForTheLoops();

        IssueRecord record = readRecord(ISSUE).orElseThrow();
        assertThat(record.state()).isEqualTo("done");
        assertThat(record.column()).isNotEqualTo("blocked");
        assertThat(record.notes()).containsExactly(MERGED_NOTE, DONE_BLOCK_NOTE);
        assertThat(gitHub.labelsOf(ISSUE)).doesNotContain(BLOCK_LABEL);
        assertThat(gitHub.squashMergeCalls()).hasSize(1);
    }

    @Test
    void aDefectInTheFinishActionKeepsTheIssueDone() throws InterruptedException {
        gitHub.failTheRemoveLabelAfterTheMergeWithADefect();
        createWorker(new FakeAgentRunner().thenChangeFile().thenApproveAllReviews().thenAssessRisk("low", true), 2)
                .pollOnce();
        waitForTheLoops();
        IssueRecord afterTheFailure = readRecord(ISSUE).orElseThrow();
        assertThat(afterTheFailure.state()).isEqualTo("done");
        assertThat(afterTheFailure.column()).isNotEqualTo("blocked");
        assertThat(afterTheFailure.notes()).containsExactly(MERGED_NOTE, FINISH_FAILURE_NOTE);

        createWorker(new FakeAgentRunner(), 2).pollOnce();
        waitForTheLoops();

        IssueRecord record = readRecord(ISSUE).orElseThrow();
        assertThat(record.state()).isEqualTo("done");
        assertThat(record.notes()).containsExactly(MERGED_NOTE, FINISH_FAILURE_NOTE);
        assertThat(gitHub.labelsOf(ISSUE)).doesNotContain(FACTORY_LABEL);
        assertThat(worktree()).doesNotExist();
        assertThat(gitHub.squashMergeCalls()).hasSize(1);
    }

    @Test
    void aFailedFinishActionLeavesTheLabelFactoryForTheNextPoll() throws InterruptedException {
        gitHub.failTheRemoveLabelAfterTheMerge();
        createWorker(new FakeAgentRunner().thenChangeFile().thenApproveAllReviews().thenAssessRisk("low", true), 2)
                .pollOnce();
        waitForTheLoops();
        assertThat(gitHub.columnOf(ISSUE)).contains("done");
        assertThat(gitHub.labelsOf(ISSUE)).contains(FACTORY_LABEL);
        assertThat(worktree()).doesNotExist();

        createWorker(new FakeAgentRunner(), 2).pollOnce();
        waitForTheLoops();

        assertThat(readRecord(ISSUE).orElseThrow().state()).isEqualTo("done");
        assertThat(gitHub.labelsOf(ISSUE)).doesNotContain(FACTORY_LABEL);
        assertThat(gitHub.removedLabels()).last().isEqualTo(FACTORY_LABEL);
        assertThat(gitHub.squashMergeCalls()).hasSize(1);
    }

    @Test
    void aBlockDuringTheMergeWithALostPublishEndsDone() throws InterruptedException {
        FakeAgentRunner agent = new FakeAgentRunner()
                .thenChangeFile().thenApproveAllReviews().thenAssessRisk("low", true);
        Worker worker = createWorker(agent, 2);
        gitHub.failTheCommentEditAfterTheMerge();
        gitHub.whenSquashMerging(() -> {
            gitHub.addLabel(ISSUE, BLOCK_LABEL);
            worker.pollOnce();
        });
        worker.pollOnce();
        waitForTheLoops();
        assertThat(readRecord(ISSUE).orElseThrow().column()).isNotEqualTo("blocked");

        createWorker(new FakeAgentRunner(), 2).pollOnce();
        waitForTheLoops();

        IssueRecord record = readRecord(ISSUE).orElseThrow();
        assertThat(record.state()).isEqualTo("done");
        assertThat(gitHub.editedBodies()).noneMatch(body -> body.contains("Blocked"));
        assertThat(gitHub.labelsOf(ISSUE)).doesNotContain(BLOCK_LABEL, FACTORY_LABEL);
        assertThat(gitHub.squashMergeCalls()).hasSize(1);
    }

    @Test
    void aBlockOnADoneIssueKeepsItDone() throws InterruptedException {
        createWorker(new FakeAgentRunner().thenChangeFile().thenApproveAllReviews().thenAssessRisk("low", true), 2)
                .pollOnce();
        waitForTheLoops();
        gitHub.addLabel(ISSUE, FACTORY_LABEL);
        gitHub.addLabel(ISSUE, BLOCK_LABEL);
        Worker worker = createWorker(new FakeAgentRunner(), 2);

        worker.pollOnce();
        gitHub.addLabel(ISSUE, BLOCK_LABEL);
        worker.pollOnce();

        IssueRecord record = readRecord(ISSUE).orElseThrow();
        assertThat(record.state()).isEqualTo("done");
        assertThat(record.notes()).containsExactly(MERGED_NOTE, DONE_BLOCK_NOTE);
        assertThat(gitHub.columnOf(ISSUE)).contains("done");
        assertThat(gitHub.labelsOf(ISSUE)).doesNotContain(BLOCK_LABEL);
    }

    @Test
    void shutDownRefusesNewPhases() throws InterruptedException {
        addLabeledIssue(8);
        FakeAgentRunner agent = new FakeAgentRunner()
                .thenWaitForStop().thatFails().thenWaitForStop().thatFails();
        Worker worker = createWorker(agent, 2);
        worker.pollOnce();
        waitUntil(() -> List.of(7, 8).stream().allMatch(issueNumber -> readRecord(issueNumber)
                .filter(IssueRecord::agentSessionStarted).isPresent()));

        worker.shutDown(TIME_BOUND);

        assertThat(agent.requests()).hasSize(1);
        for (int issueNumber : List.of(7, 8)) {
            IssueRecord record = readRecord(issueNumber).orElseThrow();
            assertThat(record.column()).isEqualTo("in-progress");
            assertThat(record.state()).isEqualTo("implement");
            assertThat(record.notes()).isEmpty();
        }
    }

    @Test
    void keepsRunningAfterAFailureOfTheIssueList() {
        gitHub.failTheNextIssueList();
        Worker worker = createWorker(new FakeAgentRunner(), 0);

        worker.pollOnce();
        worker.pollOnce();

        assertThat(worker.isStopped()).isFalse();
        assertThat(gitHub.columnOf(ISSUE)).contains("queued");
    }

    @Test
    void aPollDuringTheBlockDoesNotBlockTwice() throws InterruptedException {
        FakeAgentRunner agent = new FakeAgentRunner().thenWaitForStop().thatFails();
        Worker worker = createWorker(agent, 2);
        worker.pollOnce();
        waitUntil(() -> agent.waitingPhaseCount() == 1);
        gitHub.whenACommentEditContains("The owner blocked the issue.", worker::pollOnce);
        gitHub.addLabel(ISSUE, BLOCK_LABEL);

        worker.pollOnce();
        waitForTheLoops();

        assertThat(agent.stoppedIssues()).containsExactly(ISSUE);
        assertThat(readRecord(ISSUE).orElseThrow().newestNote()).contains("The owner blocked the issue.");
    }

    @Test
    void shutDownStopsThePhasesInWork() throws InterruptedException {
        addLabeledIssue(8);
        FakeAgentRunner agent = new FakeAgentRunner()
                .thenWaitForStop().thatFails().thenWaitForStop().thatFails();
        Worker worker = createWorker(agent, 2);
        worker.pollOnce();
        waitUntil(() -> agent.waitingPhaseCount() == 1);

        boolean loopsEnded = worker.shutDown(TIME_BOUND);

        assertThat(loopsEnded).isTrue();
        assertThat(worker.isStopped()).isTrue();
        assertThat(agent.stoppedIssues()).contains(7, 8);
        assertThat(worker.foundAnApiKey()).isFalse();
        for (int issueNumber : List.of(7, 8)) {
            assertThat(readRecord(issueNumber).orElseThrow().column()).isNotEqualTo("blocked");
        }
    }

    @Test
    void aRestartWithoutTheWorktreeAfterAFixLoopImplementsAgain() throws InterruptedException {
        FakeAgentRunner firstAgent = new FakeAgentRunner().thenChangeFile()
                .thenApproveReview().thenRejectReview("Missing input check").thenApproveReview().thenApproveReview()
                .thenChangeFile()
                .then(request -> {
                    throw new GitHubException("GitHub answered 502 for GET /issues/7");
                });
        createWorker(firstAgent, 2).pollOnce();
        waitForTheLoops();
        IssueRecord stopped = readRecord(ISSUE).orElseThrow();
        assertThat(stopped.state()).isEqualTo("review");
        assertThat(stopped.entriesOf("implement")).isEqualTo(2);
        run(clone, "worktree", "remove", "--force", worktree().toString());
        FakeAgentRunner secondAgent = new FakeAgentRunner()
                .thenChangeFile().thenApproveAllReviews().thenAssessRisk("low", true);

        createWorker(secondAgent, 2).pollOnce();
        waitForTheLoops();

        PhaseRequest implement = secondAgent.requests().getFirst();
        IssueRecord record = readRecord(ISSUE).orElseThrow();
        assertThat(record.state()).isEqualTo("done");
        assertThat(record.entriesOf("implement")).isEqualTo(3);
        assertThat(implement.newSession()).isTrue();
        assertThat(implement.sessionId()).isNotEqualTo(firstAgent.requests().getFirst().sessionId());
        assertThat(implement.prompt()).startsWith("Do the work of the issue below")
                .doesNotContain("Missing input check");
    }

    @Test
    void keepsPollingAfterAnUnexpectedFailureOfOneIssue() {
        addLabeledIssue(8);
        gitHub.failTheCommentsOf(ISSUE);
        Worker worker = createWorker(new FakeAgentRunner(), 0);

        worker.pollOnce();

        assertThat(gitHub.columnOf(8)).contains("queued");
        assertThat(worker.isStopped()).isFalse();
    }

    @Test
    void aRestartRunsThePhaseAgainWithoutASecondComment() throws InterruptedException {
        stopAWorkerDuringTheReview();
        FakeAgentRunner secondAgent = new FakeAgentRunner().thenApproveAllReviews().thenAssessRisk("low", true);

        createWorker(secondAgent, 2).pollOnce();
        waitForTheLoops();

        assertThat(readRecord(ISSUE).orElseThrow().state()).isEqualTo("done");
        assertThat(secondAgent.requests().getFirst().prompt()).contains(".factory-work/review-");
        assertThat(gitHub.commentsOf(ISSUE)).hasSize(1);
        assertThat(statusComments(ISSUE)).hasSize(1);
        assertThat(gitHub.pullRequests()).hasSize(1);
        assertThat(gitHub.squashMergeCalls()).hasSize(1);
    }

    @Test
    void aRestartWithoutTheWorktreePreparesAgain() throws InterruptedException {
        FakeAgentRunner firstAgent = stopAWorkerDuringTheReview();
        run(clone, "worktree", "remove", "--force", worktree().toString());
        FakeAgentRunner secondAgent = new FakeAgentRunner()
                .thenChangeFile().thenApproveAllReviews().thenAssessRisk("low", true);

        createWorker(secondAgent, 2).pollOnce();
        waitForTheLoops();

        PhaseRequest firstImplement = firstAgent.requests().getFirst();
        PhaseRequest secondImplement = secondAgent.requests().getFirst();
        assertThat(readRecord(ISSUE).orElseThrow().state()).isEqualTo("done");
        assertThat(secondImplement.prompt()).doesNotContain(".factory-work/review-");
        assertThat(secondImplement.newSession()).isTrue();
        assertThat(secondImplement.sessionId()).isNotEqualTo(firstImplement.sessionId());
        assertThat(statusComments(ISSUE)).hasSize(1);
        assertThat(gitHub.pullRequests()).hasSize(1);
    }

    @Test
    void startsAnApprovedMergeAgain() throws InterruptedException {
        createWorker(new FakeAgentRunner().thenChangeFile().thenApproveAllReviews().thenAssessRisk("medium", true), 2)
                .pollOnce();
        waitForTheLoops();
        assertThat(readRecord(ISSUE).orElseThrow().state()).isEqualTo("waits-for-approval");
        gitHub.addLabel(ISSUE, APPROVE_LABEL);

        createWorker(new FakeAgentRunner(), 2).pollOnce();
        waitForTheLoops();

        assertThat(readRecord(ISSUE).orElseThrow().state()).isEqualTo("done");
        assertThat(gitHub.squashMergeCalls()).hasSize(1);
    }

    @Test
    void anApproveFromThePageMergesTheIssue() throws InterruptedException {
        createWorker(new FakeAgentRunner().thenChangeFile().thenApproveAllReviews().thenAssessRisk("medium", true), 2)
                .pollOnce();
        waitForTheLoops();
        assertThat(readRecord(ISSUE).orElseThrow().state()).isEqualTo("waits-for-approval");
        pageRequests.remember(ISSUE, APPROVE_LABEL);
        gitHub.addLabel(ISSUE, APPROVE_LABEL);

        createWorker(new FakeAgentRunner(), 2).pollOnce();
        waitForTheLoops();

        assertThat(readRecord(ISSUE).orElseThrow().state()).isEqualTo("done");
        assertThat(gitHub.squashMergeCalls()).hasSize(1);
    }

    @Test
    void continuesAnIssueAfterTheOwnerAcceptsAChangedText() throws InterruptedException {
        createWorker(new FakeAgentRunner(), 0).pollOnce();
        gitHub.editIssue(ISSUE, "Change the greeting 7", "Change the greeting to hello.");
        createWorker(new FakeAgentRunner(), 2).pollOnce();
        waitForTheLoops();
        assertThat(readRecord(ISSUE).orElseThrow().state()).isEqualTo("text-changed");
        gitHub.addLabel(ISSUE, APPROVE_LABEL);
        FakeAgentRunner agent = new FakeAgentRunner()
                .thenChangeFile().thenApproveAllReviews().thenAssessRisk("low", true);

        createWorker(agent, 2).pollOnce();
        waitForTheLoops();

        assertThat(readRecord(ISSUE).orElseThrow().state()).isEqualTo("done");
        assertThat(agent.requests().getFirst().prompt()).contains("Change the greeting to hello.");
    }

    @Test
    void aBlockStopsTheRunningBuild() throws InterruptedException {
        Path started = temporaryDirectory.resolve("build-started");
        buildCommand = "touch " + started + "; sleep 60";
        Worker worker = createWorker(new FakeAgentRunner().thenChangeFile(),
                createConfiguration(2, Duration.ofSeconds(60)));
        worker.pollOnce();
        waitUntil(() -> Files.exists(started));
        gitHub.addLabel(ISSUE, BLOCK_LABEL);

        worker.pollOnce();
        waitForTheLoops();

        IssueRecord record = readRecord(ISSUE).orElseThrow();
        assertThat(record.state()).isEqualTo("blocked");
        assertThat(record.newestNote()).contains("The owner blocked the issue.");
        assertThat(record.previousState()).contains("build");
        assertThat(record.entriesOf("implement")).isEqualTo(1);
    }

    @Test
    void shutDownStopsTheRunningBuild() throws InterruptedException {
        Path started = temporaryDirectory.resolve("build-started");
        buildCommand = "touch " + started + "; sleep 60";
        Worker worker = createWorker(new FakeAgentRunner().thenChangeFile(),
                createConfiguration(2, Duration.ofSeconds(60)));
        worker.pollOnce();
        waitUntil(() -> Files.exists(started));

        boolean loopsEnded = worker.shutDown(Duration.ofSeconds(20));

        IssueRecord record = readRecord(ISSUE).orElseThrow();
        assertThat(loopsEnded).isTrue();
        assertThat(record.state()).isEqualTo("build");
        assertThat(record.column()).isEqualTo("in-progress");
        assertThat(record.entriesOf("implement")).isLessThanOrEqualTo(1);
    }

    @Test
    void aRestartWithoutTheWorktreeOfAMergedIssueWritesTheMoveToDone() throws InterruptedException {
        gitHub.failTheCommentEditAfterTheMerge();
        createWorker(new FakeAgentRunner().thenChangeFile().thenApproveAllReviews().thenAssessRisk("low", true), 2)
                .pollOnce();
        waitForTheLoops();
        run(clone, "worktree", "remove", "--force", worktree().toString());
        int earlierMoves = events.ofType("move").size();

        createWorker(new FakeAgentRunner(), 2).pollOnce();
        waitForTheLoops();

        assertThat(events.ofType("move").subList(earlierMoves, events.ofType("move").size())).first()
                .satisfies(line -> {
                    assertThat(line.path("from").asText()).isEqualTo("merge");
                    assertThat(line.path("to").asText()).isEqualTo("done");
                    assertThat(line.has("event")).isFalse();
                });
    }

    @Test
    void aRestartWithoutTheWorktreeOfAMergedIssueEndsDone() throws InterruptedException {
        gitHub.failTheCommentEditAfterTheMerge();
        createWorker(new FakeAgentRunner().thenChangeFile().thenApproveAllReviews().thenAssessRisk("low", true), 2)
                .pollOnce();
        waitForTheLoops();
        assertThat(readRecord(ISSUE).orElseThrow().state()).isEqualTo("merge");
        run(clone, "worktree", "remove", "--force", worktree().toString());
        FakeAgentRunner secondAgent = new FakeAgentRunner();

        createWorker(secondAgent, 2).pollOnce();
        waitForTheLoops();

        IssueRecord record = readRecord(ISSUE).orElseThrow();
        assertThat(record.state()).isEqualTo("done");
        assertThat(secondAgent.requests()).isEmpty();
        assertThat(gitHub.squashMergeCalls()).hasSize(1);
        assertThat(gitHub.labelsOf(ISSUE)).doesNotContain(FACTORY_LABEL);
    }

    @Test
    void writesTheStartAndOneStopOfTheWorker() throws InterruptedException {
        Worker worker = createWorker(new FakeAgentRunner(), createConfiguration(1, Duration.ofMillis(50)));
        Thread loop = Thread.ofVirtual().start(() -> {
            try {
                worker.runUntilStopped();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        });
        assertTimeoutPreemptively(TIME_BOUND, () -> {
            while (events.ofType("worker").isEmpty()) {
                Thread.sleep(10);
            }
        });

        worker.shutDown(TIME_BOUND);
        worker.shutDown(TIME_BOUND);
        loop.join(TIME_BOUND);

        assertThat(events.ofType("worker")).extracting(line -> line.path("event").asText())
                .containsExactly("start", "stop");
        assertThat(events.ofType("worker").getFirst().path("repository").asText())
                .isEqualTo(createRepositoryName().fullName());
    }

    @Test
    void stopsTheFactoryOnAnApiKey() throws InterruptedException {
        addLabeledIssue(8);
        FakeAgentRunner agent = new FakeAgentRunner().thenDetectAnApiKey().thenWaitForStop().thatFails();
        Worker worker = createWorker(agent, createConfiguration(2, Duration.ofMillis(50)));

        assertTimeoutPreemptively(TIME_BOUND, worker::runUntilStopped);
        waitForTheLoops();

        int issueWithTheApiKey = agent.requests().getFirst().issueNumber();
        int otherIssue = issueWithTheApiKey == 7 ? 8 : 7;
        IssueRecord otherRecord = readRecord(otherIssue).orElseThrow();
        assertThat(worker.isStopped()).isTrue();
        assertThat(worker.foundAnApiKey()).isTrue();
        assertThat(agent.stoppedIssues()).contains(otherIssue);
        assertThat(otherRecord.column()).isIn("queued", "in-progress");
        assertThat(otherRecord.state()).isIn("prepare", "implement");
        assertThat(otherRecord.notes()).isEmpty();
    }

    @Test
    void keepsWorkingAfterAGitHubFailure() throws InterruptedException {
        FakeAgentRunner agent = new FakeAgentRunner()
                .thenChangeFileAnd(gitHub::failTheNextCommentEdit)
                .thenChangeFile().thenApproveAllReviews().thenAssessRisk("low", true);
        Worker worker = createWorker(agent, 2);
        worker.pollOnce();
        waitUntil(() -> worker.issuesInWork().isEmpty());
        assertThat(readRecord(ISSUE).orElseThrow().state()).isEqualTo("implement");

        worker.pollOnce();
        waitForTheLoops();

        assertThat(worker.isStopped()).isFalse();
        assertThat(readRecord(ISSUE).orElseThrow().state()).isEqualTo("done");
        assertThat(statusComments(ISSUE)).hasSize(1);
    }

    @Test
    void blocksTheIssueOnAnUnexpectedFailure() throws InterruptedException {
        Worker worker = createWorker(new FakeAgentRunner(), 2);

        worker.pollOnce();
        waitForTheLoops();

        IssueRecord record = readRecord(ISSUE).orElseThrow();
        assertThat(record.state()).isEqualTo("blocked");
        assertThat(record.previousState()).contains("implement");
        assertThat(record.notes()).anyMatch(note -> note.contains("IllegalStateException")
                && note.contains("The test has no scripted action"));
        assertThat(worker.isStopped()).isFalse();
    }

    @Test
    void neverQueuesAnIssueWithABrokenRecordAgain() {
        gitHub.addCommentBy(ISSUE, WORKER_LOGIN, "**Factory status:** Queued\n\n<!-- factory-record\n{broken\n-->");
        Worker worker = createWorker(new FakeAgentRunner(), 2);

        worker.pollOnce();
        worker.pollOnce();

        IssueRecord record = readRecord(ISSUE).orElseThrow();
        assertThat(record.state()).isEqualTo("blocked");
        assertThat(record.column()).isEqualTo("blocked");
        assertThat(record.notes()).containsExactly(BROKEN_RECORD_NOTE);
        assertThat(gitHub.commentsOf(ISSUE)).hasSize(2);
        assertThat(worker.issuesInWork()).isEmpty();
    }

    /** A worker runs the issue to the wait for the merge approval, with the worktree and the agent session. */
    private void runToTheMergeApproval() throws InterruptedException {
        createWorker(new FakeAgentRunner().thenChangeFile().thenApproveAllReviews().thenAssessRisk("medium", true), 2)
                .pollOnce();
        waitForTheLoops();
        assertThat(readRecord(ISSUE).orElseThrow().state()).isEqualTo("waits-for-approval");
    }

    /** The owner gives factory:enter on the waiting issue, and a new worker reads it. */
    private Worker enterTheWaitingIssue() throws InterruptedException {
        runToTheMergeApproval();
        gitHub.addLabel(ISSUE, ENTER_LABEL);
        Worker worker = createWorker(new FakeAgentRunner(), 2);
        worker.pollOnce();
        assertThat(readRecord(ISSUE).orElseThrow().state()).isEqualTo("live");
        return worker;
    }

    private Optional<String> readLiveLink() {
        return readRecord(ISSUE).orElseThrow().live().flatMap(LiveSession::link);
    }

    @Test
    void enterOpensTheConversationOfAWaitingIssue() throws InterruptedException {
        runToTheMergeApproval();
        gitHub.addLabel(ISSUE, ENTER_LABEL);
        Worker worker = createWorker(new FakeAgentRunner(), 2);

        worker.pollOnce();

        IssueRecord record = readRecord(ISSUE).orElseThrow();
        assertThat(liveSessions.openCalls()).hasSize(1);
        assertThat(gitHub.labelsOf(ISSUE)).doesNotContain(ENTER_LABEL);
        assertThat(record.column()).isEqualTo("waits-for-you");
        assertThat(record.state()).isEqualTo("live");
        assertThat(readLiveLink()).contains("https://claude.ai/code/session_test" + ISSUE);
    }

    @Test
    void enterStopsTheRunningPhaseAndOpensTheConversation() throws InterruptedException {
        FakeAgentRunner agent = new FakeAgentRunner().thenWaitForStop().thatIsStopped();
        Worker worker = createWorker(agent, 2);
        worker.pollOnce();
        waitUntil(() -> agent.waitingPhaseCount() == 1);
        gitHub.addLabel(ISSUE, ENTER_LABEL);

        worker.pollOnce();
        waitForTheLoops();

        IssueRecord record = readRecord(ISSUE).orElseThrow();
        assertThat(agent.stoppedIssues()).contains(ISSUE);
        assertThat(liveSessions.openCalls()).hasSize(1);
        assertThat(record.previousState()).contains("implement");
        assertThat(record.entriesOf("implement")).isZero();
        assertThat(record.agentSessionStarted()).isTrue();
        assertThat(record.state()).isEqualTo("live");
        assertThat(record.notes()).noneMatch(note -> note.startsWith("The agent session failed"));
        assertThat(gitHub.labelsOf(ISSUE)).doesNotContain(ENTER_LABEL);
    }

    @Test
    void aStoppedPhaseRunsAgainInTheSameSessionAfterTheContinue() throws InterruptedException {
        FakeAgentRunner agent = new FakeAgentRunner().thenWaitForStop().thatIsStopped()
                .thenChangeFile().thenApproveAllReviews().thenAssessRisk("medium", true);
        Worker worker = createWorker(agent, 2);
        worker.pollOnce();
        waitUntil(() -> agent.waitingPhaseCount() == 1);
        gitHub.addLabel(ISSUE, ENTER_LABEL);
        worker.pollOnce();
        waitForTheLoops();
        liveSessions.requestContinue(ISSUE);

        createWorker(agent, 2).pollOnce();
        waitForTheLoops();

        IssueRecord record = readRecord(ISSUE).orElseThrow();
        PhaseRequest secondRun = agent.requests().get(1);
        assertThat(secondRun.newSession()).isFalse();
        assertThat(secondRun.sessionId()).isEqualTo(agent.requests().get(0).sessionId());
        assertThat(secondRun.prompt()).startsWith("The owner discussed the issue with you");
        assertThat(record.live()).isEmpty();
        assertThat(record.entriesOf("implement")).isEqualTo(1);
        assertThat(record.state()).isEqualTo("waits-for-approval");
    }

    @Test
    void anOpenLiveSessionHoldsItsSlot() throws InterruptedException {
        enterTheWaitingIssue();
        addLabeledIssue(OTHER_ISSUE);
        FakeAgentRunner agent = new FakeAgentRunner();
        Worker worker = createWorker(agent, 1);

        worker.pollOnce();
        waitForTheLoops();

        assertThat(readRecord(OTHER_ISSUE).orElseThrow().column()).isEqualTo("queued");
        assertThat(agent.requests()).isEmpty();
        assertThat(worker.countUsedSlots()).isEqualTo(1);
    }

    @Test
    void anIssueThatWaitsForTheApprovalHoldsItsSlot() throws InterruptedException {
        runToTheMergeApproval();
        addLabeledIssue(OTHER_ISSUE);
        FakeAgentRunner agent = new FakeAgentRunner().thenChangeNothing();
        Worker worker = createWorker(agent, 1);

        worker.pollOnce();
        waitForTheLoops();

        assertThat(agent.requests()).extracting(PhaseRequest::issueNumber).isEmpty();
        assertThat(readRecord(OTHER_ISSUE).orElseThrow().column()).isEqualTo("queued");
        assertThat(worker.countUsedSlots()).isEqualTo(1);
    }

    @Test
    void aBlockedCardHoldsNoSlot() throws InterruptedException {
        runToTheMergeApproval();
        gitHub.addLabel(ISSUE, BLOCK_LABEL);
        addLabeledIssue(OTHER_ISSUE);
        FakeAgentRunner agent = new FakeAgentRunner().thenChangeNothing();
        Worker worker = createWorker(agent, 1);

        worker.pollOnce();
        waitForTheLoops();

        assertThat(readRecord(ISSUE).orElseThrow().column()).isEqualTo("blocked");
        assertThat(agent.requests()).extracting(PhaseRequest::issueNumber).containsOnly(OTHER_ISSUE);
    }

    @Test
    void aSessionThatTheLoopOpensHoldsItsSlotBeforeTheNextPoll() throws InterruptedException {
        FakeAgentRunner agent = new FakeAgentRunner().thenWaitForStop().thatIsStopped();
        Worker worker = createWorker(agent, 1);
        worker.pollOnce();
        waitUntil(() -> agent.waitingPhaseCount() == 1);
        gitHub.addLabel(ISSUE, ENTER_LABEL);

        worker.pollOnce();
        waitUntil(() -> worker.issuesInWork().isEmpty());

        assertThat(readRecord(ISSUE).orElseThrow().state()).isEqualTo("live");
        assertThat(worker.countUsedSlots()).isEqualTo(1);
    }

    @Test
    void anOpenSessionKeepsItsSlotWhenThePollDoesNotFindItsIssue() throws InterruptedException {
        enterTheWaitingIssue();
        FakeAgentRunner agent = new FakeAgentRunner();
        Worker worker = createWorker(agent, 1);
        worker.pollOnce();
        gitHub.removeLabel(ISSUE, FACTORY_LABEL);
        addLabeledIssue(OTHER_ISSUE);

        worker.pollOnce();
        waitForTheLoops();

        assertThat(liveSessions.isOpen(ISSUE)).isTrue();
        assertThat(readRecord(OTHER_ISSUE).orElseThrow().column()).isEqualTo("queued");
        assertThat(agent.requests()).isEmpty();
    }

    @Test
    void anIssueBackFromItsLiveSessionStartsBeforeAStartedIssueWithALowerNumber() throws InterruptedException {
        FakeAgentRunner agent = new FakeAgentRunner().thenWaitForStop().thatIsStopped()
                .thenChangeFile().thenApproveAllReviews().thenAssessRisk("medium", true);
        Worker worker = createWorker(agent, 1);
        worker.pollOnce();
        waitUntil(() -> agent.waitingPhaseCount() == 1);
        gitHub.addLabel(ISSUE, ENTER_LABEL);
        worker.pollOnce();
        waitUntil(() -> worker.issuesInWork().isEmpty());
        addLabeledIssue(3);
        worker.pollOnce();
        IssueRecord started = RecordStates.move(readRecord(3).orElseThrow().withAgentSessionStarted(), "implement");
        gitHub.editComment(started.statusCommentId().orElseThrow(), StatusComment.render(started, LIMITS, RecordStates.REVIEW_NAMES));
        liveSessions.requestContinue(ISSUE);

        worker.pollOnce();
        waitForTheLoops();

        assertThat(agent.requests()).extracting(PhaseRequest::issueNumber).doesNotContain(3);
        assertThat(readRecord(ISSUE).orElseThrow().state()).isEqualTo("waits-for-approval");
    }

    @Test
    void anIssueBackFromItsLiveSessionStartsBeforeANewIssue() throws InterruptedException {
        FakeAgentRunner agent = new FakeAgentRunner().thenWaitForStop().thatIsStopped()
                .thenChangeFile().thenApproveAllReviews().thenAssessRisk("medium", true);
        Worker worker = createWorker(agent, 1);
        worker.pollOnce();
        waitUntil(() -> agent.waitingPhaseCount() == 1);
        gitHub.addLabel(ISSUE, ENTER_LABEL);
        worker.pollOnce();
        waitUntil(() -> worker.issuesInWork().isEmpty());
        addLabeledIssue(3);
        worker.pollOnce();
        liveSessions.requestContinue(ISSUE);

        worker.pollOnce();
        waitForTheLoops();

        assertThat(agent.requests()).extracting(PhaseRequest::issueNumber).doesNotContain(3);
        assertThat(readRecord(3).orElseThrow().column()).isEqualTo("queued");
        assertThat(readRecord(ISSUE).orElseThrow().state()).isEqualTo("waits-for-approval");
    }

    @Test
    void enterBeforeTheSessionFileExistsOpensNoConversationAndBlocksNothing() throws InterruptedException {
        FakeAgentRunner agent = new FakeAgentRunner().withoutASavedSession().thenWaitForStop().thatIsStopped();
        Worker worker = createWorker(agent, 2);
        worker.pollOnce();
        waitUntil(() -> agent.waitingPhaseCount() == 1);
        gitHub.addLabel(ISSUE, ENTER_LABEL);

        worker.pollOnce();
        waitForTheLoops();

        IssueRecord record = readRecord(ISSUE).orElseThrow();
        assertThat(liveSessions.openCalls()).isEmpty();
        assertThat(record.column()).isEqualTo("in-progress");
        assertThat(record.agentSessionStarted()).isFalse();
        assertThat(gitHub.labelsOf(ISSUE)).doesNotContain(ENTER_LABEL);
    }

    @Test
    void enterStopsTheRunningBuildAndOpensTheConversation() throws InterruptedException {
        Path started = temporaryDirectory.resolve("build-started");
        buildCommand = "touch " + started + "; sleep 60";
        Worker worker = createWorker(new FakeAgentRunner().thenChangeFile(), 2);
        worker.pollOnce();
        waitUntil(() -> Files.exists(started));
        gitHub.addLabel(ISSUE, ENTER_LABEL);

        worker.pollOnce();
        waitForTheLoops();

        IssueRecord record = readRecord(ISSUE).orElseThrow();
        assertThat(liveSessions.openCalls()).hasSize(1);
        assertThat(record.previousState()).contains("build");
        assertThat(record.entriesOf("implement")).isEqualTo(1);
        assertThat(record.state()).isEqualTo("live");
    }

    @Test
    void enterKeepsTheResultOfAnActionThatEndsBeforeTheStop() throws InterruptedException {
        CountDownLatch phaseStarted = new CountDownLatch(1);
        CountDownLatch phaseMayEnd = new CountDownLatch(1);
        FakeAgentRunner agent = new FakeAgentRunner().then(request -> {
            phaseStarted.countDown();
            awaitQuietly(phaseMayEnd);
            Files.writeString(request.workingDirectory().resolve("work.txt"), "change in the phase\n");
        });
        Worker worker = createWorker(agent, 2);
        worker.pollOnce();
        awaitQuietly(phaseStarted);
        gitHub.addLabel(ISSUE, ENTER_LABEL);

        worker.pollOnce();
        phaseMayEnd.countDown();
        waitForTheLoops();

        IssueRecord record = readRecord(ISSUE).orElseThrow();
        assertThat(agent.requests()).hasSize(1);
        assertThat(liveSessions.openCalls()).hasSize(1);
        assertThat(record.previousState()).contains("build");
        assertThat(record.state()).isEqualTo("live");
    }

    /** Counts the calls that tell the page about a request. The other calls go to the board of the test. */
    private final class CountingBoardUpdates implements BoardUpdates {

        private int requestChanges;

        @Override
        public void recordPoll(List<Issue> labeledIssues) {
            board.recordPoll(labeledIssues);
        }

        @Override
        public void recordIssue(Issue issue, IssueRecord record) {
            board.recordIssue(issue, record);
        }

        @Override
        public synchronized void recordRequestChange() {
            requestChanges++;
        }

        synchronized int requestChanges() {
            return requestChanges;
        }
    }

    @Test
    void aBlockLabelFromTheGitHubAppBecomesAWaitingRequest() throws InterruptedException {
        CountingBoardUpdates updates = new CountingBoardUpdates();
        FakeAgentRunner agent = new FakeAgentRunner().thenWaitForStop().thatIsStopped();
        Worker worker = createWorker(agent, createConfiguration(2, Duration.ofSeconds(60)), updates);
        worker.pollOnce();
        waitUntil(() -> agent.waitingPhaseCount() == 1);
        gitHub.addLabel(ISSUE, BLOCK_LABEL);

        worker.pollOnce();
        waitForTheLoops();

        assertThat(pageRequests.isRemembered(ISSUE, BLOCK_LABEL)).isTrue();
        assertThat(updates.requestChanges()).isEqualTo(1);
    }

    @Test
    void theLastIssueOfAStoppingFactoryTellsThePage() throws InterruptedException {
        CountingBoardUpdates updates = new CountingBoardUpdates();
        CountDownLatch phaseStarted = new CountDownLatch(1);
        CountDownLatch phaseMayEnd = new CountDownLatch(1);
        FakeAgentRunner agent = new FakeAgentRunner().then(request -> {
            phaseStarted.countDown();
            awaitQuietly(phaseMayEnd);
        });
        Worker worker = createWorker(agent, createConfiguration(2, Duration.ofSeconds(60)), updates);
        worker.pollOnce();
        awaitQuietly(phaseStarted);

        factorySwitch().requestStop();
        phaseMayEnd.countDown();
        waitForTheLoops();

        assertThat(worker.issuesInWork()).isEmpty();
        assertThat(updates.requestChanges()).isEqualTo(1);
    }

    @Test
    void anEnterLabelFromTheGitHubAppBecomesAWaitingRequest() throws InterruptedException {
        FakeAgentRunner agent = new FakeAgentRunner().thenWaitForStop().thatIsStopped();
        Worker worker = createWorker(agent, 2);
        worker.pollOnce();
        waitUntil(() -> agent.waitingPhaseCount() == 1);
        gitHub.addLabel(ISSUE, ENTER_LABEL);

        worker.pollOnce();
        waitForTheLoops();

        assertThat(pageRequests.isRemembered(ISSUE, ENTER_LABEL)).isTrue();
    }

    @Test
    void anApproveLabelFromTheGitHubAppBecomesAWaitingRequest() throws InterruptedException {
        runToTheMergeApproval();
        gitHub.addLabel(ISSUE, APPROVE_LABEL);

        createWorker(new FakeAgentRunner(), 2).pollOnce();
        waitForTheLoops();

        assertThat(pageRequests.isRemembered(ISSUE, APPROVE_LABEL)).isTrue();
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            assertThat(latch.await(TIME_BOUND.toSeconds(), TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("The wait was interrupted", exception);
        }
    }

    @Test
    void continueStartsTheIssueAgain() throws InterruptedException {
        Worker worker = enterTheWaitingIssue();
        worker.pollOnce();
        assertThat(liveSessions.closeCalls()).isEmpty();
        liveSessions.requestContinue(ISSUE);

        worker.pollOnce();
        waitForTheLoops();

        IssueRecord record = readRecord(ISSUE).orElseThrow();
        assertThat(record.live()).isEmpty();
        assertThat(record.state()).isEqualTo("waits-for-approval");
        assertThat(liveSessions.closeCalls()).containsExactly(ISSUE);
        assertThat(liveSessions.isContinueRequested(ISSUE)).isFalse();
    }

    @Test
    void anEndedSessionAddsANoteOnce() throws InterruptedException {
        Worker worker = enterTheWaitingIssue();
        liveSessions.endSession(ISSUE);

        worker.pollOnce();
        worker.pollOnce();

        IssueRecord record = readRecord(ISSUE).orElseThrow();
        assertThat(record.notes()).filteredOn(ENDED_NOTE::equals).hasSize(1);
        assertThat(gitHub.editedBodies()).filteredOn(body -> body.contains(ENDED_NOTE)).hasSize(1);
        assertThat(readLiveLink()).isEmpty();
        assertThat(record.column()).isEqualTo("waits-for-you");
        assertThat(record.state()).isEqualTo("live");
        gitHub.addLabel(ISSUE, ENTER_LABEL);
        worker.pollOnce();
        assertThat(liveSessions.openCalls()).hasSize(2);
        assertThat(readLiveLink()).isPresent();
    }

    @Test
    void aRestartFindsTheOpenConversation() throws InterruptedException {
        enterTheWaitingIssue();
        Worker secondWorker = createWorker(new FakeAgentRunner(), 2);
        secondWorker.pollOnce();
        assertThat(readRecord(ISSUE).orElseThrow().notes()).doesNotContain(ENDED_NOTE);
        liveSessions.requestContinue(ISSUE);

        secondWorker.pollOnce();
        waitForTheLoops();

        IssueRecord record = readRecord(ISSUE).orElseThrow();
        assertThat(record.live()).isEmpty();
        assertThat(record.state()).isEqualTo("waits-for-approval");
        assertThat(liveSessions.openCalls()).hasSize(1);
    }

    @Test
    void blockClosesTheLiveSession() throws InterruptedException {
        Worker worker = enterTheWaitingIssue();
        gitHub.addLabel(ISSUE, BLOCK_LABEL);

        worker.pollOnce();
        worker.pollOnce();

        IssueRecord record = readRecord(ISSUE).orElseThrow();
        assertThat(liveSessions.closeCalls()).containsExactly(ISSUE);
        assertThat(liveSessions.isOpen(ISSUE)).isFalse();
        assertThat(record.state()).isEqualTo("blocked");
        assertThat(record.newestNote()).contains("The owner blocked the issue.");
        assertThat(record.live()).isEmpty();
        assertThat(record.notes()).doesNotContain(ENDED_NOTE);
    }

    @Test
    void aBlockAfterAContinueRequestKeepsTheIssueInTheBlockTarget() throws InterruptedException {
        Worker worker = enterTheWaitingIssue();
        liveSessions.requestContinue(ISSUE);
        gitHub.addLabel(ISSUE, BLOCK_LABEL);
        worker.pollOnce();

        worker.pollOnce();
        waitForTheLoops();

        IssueRecord record = readRecord(ISSUE).orElseThrow();
        assertThat(record.state()).isEqualTo("blocked");
        assertThat(record.newestNote()).contains("The owner blocked the issue.");
        assertThat(liveSessions.isContinueRequested(ISSUE)).isFalse();
    }

    @Test
    void shutDownKeepsTheLiveSession() throws InterruptedException {
        runToTheMergeApproval();
        addLabeledIssue(8);
        gitHub.addLabel(ISSUE, ENTER_LABEL);
        FakeAgentRunner agent = new FakeAgentRunner().thenWaitForStop().thatFails();
        Worker worker = createWorker(agent, 2);
        worker.pollOnce();
        waitUntil(() -> agent.waitingPhaseCount() == 1);

        assertThat(worker.shutDown(TIME_BOUND)).isTrue();

        assertThat(agent.stoppedIssues()).contains(8);
        assertThat(liveSessions.closeCalls()).isEmpty();
        assertThat(liveSessions.isOpen(ISSUE)).isTrue();
    }

    /** The implement phase waits until the test releases it, then changes a file. */
    private static FakeAgentRunner.Action changeFileAfter(CountDownLatch phaseStarted, CountDownLatch phaseMayEnd) {
        return request -> {
            phaseStarted.countDown();
            awaitQuietly(phaseMayEnd);
            Files.writeString(request.workingDirectory().resolve("work.txt"), "change in the phase\n");
        };
    }

    /** The owner continues the blocked issue: the record goes back to its previous state. */
    private void resumeTheBlockedIssue() {
        IssueRecord blocked = readRecord(ISSUE).orElseThrow();
        IssueRecord resumed = RecordStates.move(blocked, blocked.previousState().orElseThrow());
        gitHub.editComment(blocked.statusCommentId().orElseThrow(), StatusComment.render(resumed, LIMITS, RecordStates.REVIEW_NAMES));
    }

    @Test
    void aBlockDuringTheEnterClosesTheNewSession() throws InterruptedException {
        CountDownLatch phaseStarted = new CountDownLatch(1);
        CountDownLatch phaseMayEnd = new CountDownLatch(1);
        Worker worker = createWorker(new FakeAgentRunner().then(changeFileAfter(phaseStarted, phaseMayEnd)), 2);
        worker.pollOnce();
        awaitQuietly(phaseStarted);
        gitHub.addLabel(ISSUE, ENTER_LABEL);
        worker.pollOnce();
        liveSessions.whenOpening(() -> {
            gitHub.addLabel(ISSUE, BLOCK_LABEL);
            worker.pollOnce();
        });

        phaseMayEnd.countDown();
        waitForTheLoops();

        IssueRecord record = readRecord(ISSUE).orElseThrow();
        assertThat(liveSessions.openCalls()).hasSize(1);
        assertThat(liveSessions.closeCalls()).hasSize(2);
        assertThat(liveSessions.isOpen(ISSUE)).isFalse();
        assertThat(record.state()).isEqualTo("blocked");
        assertThat(record.newestNote()).contains("The owner blocked the issue.");
        assertThat(record.live()).isEmpty();
    }

    @Test
    void aBlockedEnterRequestDoesNotOpenALaterSession() throws InterruptedException {
        CountDownLatch phaseStarted = new CountDownLatch(1);
        CountDownLatch phaseMayEnd = new CountDownLatch(1);
        FakeAgentRunner agent = new FakeAgentRunner().then(changeFileAfter(phaseStarted, phaseMayEnd))
                .thenChangeFile();
        Worker worker = createWorker(agent, 2);
        worker.pollOnce();
        awaitQuietly(phaseStarted);
        gitHub.addLabel(ISSUE, ENTER_LABEL);
        worker.pollOnce();
        gitHub.addLabel(ISSUE, BLOCK_LABEL);
        worker.pollOnce();
        // The action ends only after the block, so the enter request can never open a session before it.
        phaseMayEnd.countDown();
        waitUntil(() -> worker.issuesInWork().isEmpty()
                && readRecord(ISSUE).orElseThrow().column().equals("blocked")
                && !gitHub.labelsOf(ISSUE).contains(BLOCK_LABEL));
        gitHub.removeLabel(ISSUE, ENTER_LABEL);
        resumeTheBlockedIssue();

        worker.pollOnce();
        waitForTheLoops();

        assertThat(liveSessions.openCalls()).isEmpty();
        assertThat(agent.requests()).hasSizeGreaterThan(1);
    }

    @Test
    void aLoopThatEndsWithAGitHubFailureForgetsTheEnterRequest() throws InterruptedException {
        CountDownLatch phaseStarted = new CountDownLatch(1);
        CountDownLatch phaseMayEnd = new CountDownLatch(1);
        FakeAgentRunner agent = new FakeAgentRunner().then(request -> {
            phaseStarted.countDown();
            awaitQuietly(phaseMayEnd);
            throw new GitHubException("GitHub answered 502 for GET /issues/7");
        }).thenChangeFile();
        Worker worker = createWorker(agent, 2);
        worker.pollOnce();
        awaitQuietly(phaseStarted);
        gitHub.addLabel(ISSUE, ENTER_LABEL);
        worker.pollOnce();
        gitHub.removeLabel(ISSUE, ENTER_LABEL);
        phaseMayEnd.countDown();
        waitUntil(() -> worker.issuesInWork().isEmpty());

        worker.pollOnce();
        waitForTheLoops();

        assertThat(liveSessions.openCalls()).isEmpty();
        assertThat(agent.requests()).hasSizeGreaterThan(1);
    }

    @Test
    void aGitHubFailureInTheEnterKeepsTheLabelForTheNextPoll() throws InterruptedException {
        runToTheMergeApproval();
        gitHub.addLabel(ISSUE, ENTER_LABEL);
        Worker worker = createWorker(new FakeAgentRunner(), 2);
        gitHub.failTheNextCommentEdit();

        worker.pollOnce();
        assertThat(gitHub.labelsOf(ISSUE)).contains(ENTER_LABEL);
        assertThat(liveSessions.isOpen(ISSUE)).isFalse();
        worker.pollOnce();

        assertThat(liveSessions.openCalls()).hasSize(2);
        assertThat(gitHub.labelsOf(ISSUE)).doesNotContain(ENTER_LABEL);
        assertThat(readRecord(ISSUE).orElseThrow().state()).isEqualTo("live");
    }

    @Test
    void aFailedLabelRemovalAfterTheEnterRemovesTheLabelAtTheNextPoll() throws InterruptedException {
        runToTheMergeApproval();
        gitHub.addLabel(ISSUE, ENTER_LABEL);
        Worker worker = createWorker(new FakeAgentRunner(), 2);
        gitHub.failTheNextRemoveLabel();

        worker.pollOnce();
        assertThat(gitHub.labelsOf(ISSUE)).contains(ENTER_LABEL);
        worker.pollOnce();

        assertThat(liveSessions.openCalls()).hasSize(1);
        assertThat(liveSessions.isOpen(ISSUE)).isTrue();
        assertThat(gitHub.labelsOf(ISSUE)).doesNotContain(ENTER_LABEL);
        assertThat(readRecord(ISSUE).orElseThrow().state()).isEqualTo("live");
    }

    @Test
    void doesNotContinueAfterAFailureWithAnOldContinueRequest() throws InterruptedException {
        enterTheWaitingIssue();
        liveSessions.failTheNextClose();
        liveSessions.requestContinue(ISSUE);
        createWorker(new FakeAgentRunner(), 2).pollOnce();
        waitForTheLoops();
        IssueRecord blocked = readRecord(ISSUE).orElseThrow();

        createWorker(new FakeAgentRunner(), 2).pollOnce();
        waitForTheLoops();

        assertThat(blocked.state()).isEqualTo("blocked");
        assertThat(blocked.previousState()).contains("merge");
        assertThat(blocked.live()).isEmpty();
        assertThat(liveSessions.isContinueRequested(ISSUE)).isFalse();
        assertThat(readRecord(ISSUE).orElseThrow()).isEqualTo(blocked);
    }

    @Test
    void continuesAfterAFailureWithANewContinueRequest() throws InterruptedException {
        enterTheWaitingIssue();
        liveSessions.failTheNextClose();
        liveSessions.requestContinue(ISSUE);
        createWorker(new FakeAgentRunner(), 2).pollOnce();
        waitForTheLoops();
        liveSessions.requestContinue(ISSUE);

        createWorker(new FakeAgentRunner(), 2).pollOnce();
        waitForTheLoops();

        IssueRecord record = readRecord(ISSUE).orElseThrow();
        assertThat(record.live()).isEmpty();
        assertThat(record.state()).isEqualTo("waits-for-approval");
        assertThat(liveSessions.isContinueRequested(ISSUE)).isFalse();
    }

    /** Another thread stops the worker, as the shutdown hook does. The action goes on after the stop. */
    private static void stopTheWorkerDuringTheAction(Worker worker) {
        new Thread(() -> worker.shutDown(TIME_BOUND), "test-shutdown").start();
        try {
            waitUntil(worker::isStopped);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("The wait was interrupted", exception);
        }
    }

    @Test
    void aStopDuringTheOpenAfterAQuestionKeepsTheLiveRecord() throws InterruptedException {
        Worker worker = createWorker(new FakeAgentRunner().thenAskQuestion("Which greeting do you want?"), 2);
        liveSessions.whenOpening(() -> stopTheWorkerDuringTheAction(worker));

        worker.pollOnce();
        waitForTheLoops();

        IssueRecord record = readRecord(ISSUE).orElseThrow();
        assertThat(worker.isStopped()).isTrue();
        assertThat(liveSessions.isOpen(ISSUE)).isTrue();
        assertThat(readLiveLink()).contains("https://claude.ai/code/session_test" + ISSUE);
        assertThat(record.state()).isEqualTo("live");
    }

    @Test
    void aStopDuringTheContinueKeepsTheRecordAfterTheContinue() throws InterruptedException {
        Worker worker = enterTheWaitingIssue();
        liveSessions.requestContinue(ISSUE);
        liveSessions.whenClosing(() -> stopTheWorkerDuringTheAction(worker));

        worker.pollOnce();
        waitForTheLoops();

        IssueRecord record = readRecord(ISSUE).orElseThrow();
        assertThat(worker.isStopped()).isTrue();
        assertThat(liveSessions.isOpen(ISSUE)).isFalse();
        assertThat(record.live()).isEmpty();
        assertThat(record.state()).isEqualTo("merge");
    }

    @Test
    void aShutDownAfterItsTimeoutInterruptsTheOpenOfASession() throws InterruptedException {
        CountDownLatch openStarted = new CountDownLatch(1);
        AtomicBoolean openInterrupted = new AtomicBoolean();
        Worker worker = createWorker(new FakeAgentRunner().thenAskQuestion("Which greeting do you want?"), 2);
        liveSessions.whenOpening(() -> {
            openStarted.countDown();
            try {
                Thread.sleep(TIME_BOUND.toMillis());
            } catch (InterruptedException exception) {
                openInterrupted.set(true);
                Thread.currentThread().interrupt();
            }
        });
        worker.pollOnce();
        awaitQuietly(openStarted);

        boolean loopsEnded = worker.shutDown(Duration.ofMillis(200));
        waitForTheLoops();

        assertThat(loopsEnded).isFalse();
        assertThat(openInterrupted).isTrue();
        assertThat(liveSessions.isOpen(ISSUE)).isFalse();
        assertThat(readRecord(ISSUE).orElseThrow().live()).isEmpty();
    }

    @Test
    void countsOneEntryAfterABlockDuringTheAction() throws InterruptedException {
        FakeAgentRunner agent = new FakeAgentRunner().thenWaitForStop().thatFails()
                .thenChangeFile().thenApproveAllReviews().thenAssessRisk("medium", true);
        Worker worker = createWorker(agent, 2);
        worker.pollOnce();
        waitUntil(() -> agent.waitingPhaseCount() == 1);
        gitHub.addLabel(ISSUE, BLOCK_LABEL);
        worker.pollOnce();
        waitForTheLoops();
        IssueRecord blocked = readRecord(ISSUE).orElseThrow();
        liveSessions.requestContinue(ISSUE);

        createWorker(agent, 2).pollOnce();
        waitForTheLoops();

        IssueRecord record = readRecord(ISSUE).orElseThrow();
        assertThat(blocked.state()).isEqualTo("blocked");
        assertThat(blocked.entriesOf("implement")).isZero();
        assertThat(record.state()).isEqualTo("waits-for-approval");
        assertThat(record.entriesOf("implement")).isEqualTo(1);
        assertThat(liveSessions.isContinueRequested(ISSUE)).isFalse();
    }

    @Test
    void entersAtOnceAndOpensTheConversation() throws InterruptedException {
        runToTheMergeApproval();
        gitHub.addLabel(ISSUE, ENTER_LABEL);
        Worker worker = createWorker(new FakeAgentRunner(), 2);

        worker.pollOnce();

        IssueRecord record = readRecord(ISSUE).orElseThrow();
        assertThat(record.state()).isEqualTo("live");
        assertThat(record.previousState()).contains("merge");
        assertThat(readLiveLink()).contains("https://claude.ai/code/session_test" + ISSUE);
        assertThat(liveSessions.openCalls()).hasSize(1);
        assertThat(gitHub.labelsOf(ISSUE)).doesNotContain(ENTER_LABEL);
        assertThat(gitHub.columnOf(ISSUE)).contains("waits-for-you");
        assertThat(worker.issuesInWork()).isEmpty();
    }

    @Test
    void stopsTheActionOnEnterAndEntersOnTheKeptRecord() throws InterruptedException {
        FakeAgentRunner agent = new FakeAgentRunner().thenWaitForStop().thatIsStopped();
        Worker worker = createWorker(agent, 2);
        worker.pollOnce();
        waitUntil(() -> agent.waitingPhaseCount() == 1);
        gitHub.addLabel(ISSUE, ENTER_LABEL);

        worker.pollOnce();
        waitForTheLoops();

        IssueRecord record = readRecord(ISSUE).orElseThrow();
        assertThat(agent.stoppedIssues()).contains(ISSUE);
        assertThat(record.state()).isEqualTo("live");
        assertThat(record.previousState()).contains("implement");
        assertThat(record.entriesOf("implement")).isZero();
        assertThat(record.agentSessionStarted()).isTrue();
        assertThat(readLiveLink()).contains("https://claude.ai/code/session_test" + ISSUE);
        assertThat(statusComments(ISSUE)).hasSize(1);
    }

    @Test
    void blocksTheIssueIntoTheBlockTarget() throws InterruptedException {
        runToTheMergeApproval();
        gitHub.addLabel(ISSUE, BLOCK_LABEL);
        Worker worker = createWorker(new FakeAgentRunner(), 2);

        worker.pollOnce();
        waitForTheLoops();

        IssueRecord record = readRecord(ISSUE).orElseThrow();
        assertThat(record.state()).isEqualTo("blocked");
        assertThat(record.column()).isEqualTo("blocked");
        assertThat(record.previousState()).contains("merge");
        assertThat(record.newestNote()).contains("The owner blocked the issue.");
        assertThat(gitHub.columnOf(ISSUE)).contains("blocked");
        assertThat(gitHub.labelsOf(ISSUE)).doesNotContain(BLOCK_LABEL);
    }

    @Test
    void doesNothingWhenTheIssueIsInTheBlockTargetAlready() throws InterruptedException {
        createWorker(new FakeAgentRunner(), 2).pollOnce();
        waitForTheLoops();
        IssueRecord blocked = readRecord(ISSUE).orElseThrow();
        int editsBefore = gitHub.editedBodies().size();
        gitHub.addLabel(ISSUE, BLOCK_LABEL);

        createWorker(new FakeAgentRunner(), 2).pollOnce();
        waitForTheLoops();

        assertThat(blocked.state()).isEqualTo("blocked");
        assertThat(blocked.newestNote()).hasValueSatisfying(note -> assertThat(note)
                .startsWith("An unexpected failure stopped the issue: "));
        assertThat(readRecord(ISSUE).orElseThrow()).isEqualTo(blocked);
        assertThat(gitHub.editedBodies()).hasSize(editsBefore);
        assertThat(gitHub.labelsOf(ISSUE)).doesNotContain(BLOCK_LABEL);
    }

    @Test
    void movesAnUnexpectedFailureToTheBlockTarget() throws InterruptedException {
        Worker worker = createWorker(new FakeAgentRunner(), 2);

        worker.pollOnce();
        waitForTheLoops();

        IssueRecord record = readRecord(ISSUE).orElseThrow();
        assertThat(record.state()).isEqualTo("blocked");
        assertThat(record.column()).isEqualTo("blocked");
        assertThat(record.previousState()).contains("implement");
        assertThat(record.newestNote()).hasValueSatisfying(note -> assertThat(note)
                .startsWith("An unexpected failure stopped the issue: java.lang.IllegalStateException: "));
    }

    @Test
    void startsAgainOnlyFromTheBlockTarget() throws InterruptedException {
        runToTheMergeApproval();
        gitHub.addLabel(ISSUE, START_AGAIN_LABEL);
        Worker worker = createWorker(new FakeAgentRunner(), 0);
        worker.pollOnce();
        IssueRecord waiting = readRecord(ISSUE).orElseThrow();
        gitHub.addLabel(ISSUE, BLOCK_LABEL);
        worker.pollOnce();
        gitHub.addLabel(ISSUE, START_AGAIN_LABEL);

        worker.pollOnce();

        IssueRecord record = readRecord(ISSUE).orElseThrow();
        assertThat(waiting.state()).isEqualTo("waits-for-approval");
        assertThat(waiting.newestNote())
                .contains("Start again has no effect: it works only on a Blocked issue without a live conversation.");
        assertThat(record.state()).isEqualTo("prepare");
        assertThat(record.column()).isEqualTo("queued");
        assertThat(record.entries()).isEmpty();
        assertThat(record.previousState()).contains("prepare");
        assertThat(gitHub.labelsOf(ISSUE)).doesNotContain(START_AGAIN_LABEL, BLOCK_LABEL);
    }

    /** The default branch gets a .factory with this build command. */
    private Worker createWorkerWithBuild(FakeAgentRunner agent, String newBuildCommand) {
        buildCommand = newBuildCommand;
        return createWorker(agent, 2);
    }

    /** Another factory takes the issue: its account becomes the only assignee. */
    private void reassignToAlice() {
        gitHub.unassign(ISSUE, WORKER_LOGIN);
        gitHub.assignBy(ISSUE, ALICE);
    }

    private void reassignToTheWorker() {
        gitHub.unassign(ISSUE, ALICE);
        gitHub.assignBy(ISSUE, WORKER_LOGIN);
    }

    private List<IssueComment> stopComments(int issueNumber) {
        return gitHub.commentsOf(issueNumber).stream()
                .filter(comment -> comment.body().startsWith(STOP_COMMENT_START))
                .toList();
    }

    /** The factory of alice wrote this record on the issue. */
    private void addRecordOfAlice(IssueRecord record) {
        gitHub.addCommentBy(ISSUE, ALICE, StatusComment.render(record, LIMITS, RecordStates.REVIEW_NAMES));
    }

    /** The issue had the assignee alice once, and has it no longer. */
    private void recordAnEarlierAssignmentOfAlice() {
        gitHub.assignBy(ISSUE, ALICE);
        gitHub.unassign(ISSUE, ALICE);
    }

    /** The factory of alice pushed the branch of the issue to the target repository: origin and GitHub. */
    private String pushTheBranchOfAlice() {
        Path aliceClone = temporaryDirectory.resolve("alice");
        run(temporaryDirectory, "clone", "-q", temporaryDirectory.resolve("origin.git").toString(),
                aliceClone.toString());
        run(aliceClone, "config", "user.name", "Alice");
        run(aliceClone, "config", "user.email", "alice@example.com");
        run(aliceClone, "checkout", "-q", "-b", WorkspaceLayout.branch(ISSUE));
        commitFile(aliceClone, "alice.txt", "alice\n", "Change of the factory of alice");
        run(aliceClone, "push", "-q", "origin", WorkspaceLayout.branch(ISSUE));
        run(aliceClone, "push", "-q", temporaryDirectory.resolve("github.git").toString(),
                WorkspaceLayout.branch(ISSUE));
        String pushedCommit = run(aliceClone, "rev-parse", "HEAD").trim();
        gitHub.addBranchHead(WorkspaceLayout.branch(ISSUE), pushedCommit);
        return pushedCommit;
    }

    @Test
    void pollAssignsAnIssueWithoutAssignee() {
        Worker worker = createWorker(new FakeAgentRunner(), 0);

        worker.pollOnce();

        assertThat(gitHub.assigneesOf(ISSUE)).containsExactly(WORKER_LOGIN);
        assertThat(readRecord(ISSUE)).isPresent();
    }

    @Test
    void aStoppedFactoryDoesNotAssignANewIssue() {
        Worker worker = createWorker(new FakeAgentRunner(), 0);
        factorySwitch().requestStop();

        worker.pollOnce();

        assertThat(gitHub.assigneesOf(ISSUE)).isEmpty();
        assertThat(gitHub.commentsOf(ISSUE)).isEmpty();
    }

    @Test
    void aStoppedFactoryDoesNotTakeOverANewIssueOfAnEarlierOwner() {
        recordAnEarlierAssignmentOfAlice();
        addRecordOfAlice(RecordStates.move(RecordStates.queued(ISSUE), "review"));
        gitHub.assignBy(ISSUE, WORKER_LOGIN);
        Worker worker = createWorker(new FakeAgentRunner(), 0);
        factorySwitch().requestStop();

        worker.pollOnce();

        assertThat(readRecord(ISSUE)).isEmpty();
        assertThat(statusComments(ISSUE)).hasSize(1);
    }

    @Test
    void aStoppedFactoryAssignsAgainAnIssueWithItsRecord() {
        createWorker(new FakeAgentRunner(), 0).pollOnce();
        gitHub.unassign(ISSUE, WORKER_LOGIN);
        Worker worker = createWorker(new FakeAgentRunner(), 0);
        factorySwitch().requestStop();

        worker.pollOnce();

        assertThat(gitHub.assigneesOf(ISSUE)).containsExactly(WORKER_LOGIN);
        assertThat(findCard(board, ISSUE)).isPresent();
    }

    @Test
    void pollLeavesAnIssueOfAnotherAccount() {
        addLabeledIssue(OTHER_ISSUE);
        gitHub.assignBy(OTHER_ISSUE, ALICE);
        Worker worker = createWorker(new FakeAgentRunner(), 0);

        worker.pollOnce();

        assertThat(gitHub.commentsOf(OTHER_ISSUE)).isEmpty();
        assertThat(gitHub.columnLabelsOf(OTHER_ISSUE)).isEmpty();
        assertThat(gitHub.assigneesOf(OTHER_ISSUE)).containsExactly(ALICE);
        assertThat(findCard(board, OTHER_ISSUE)).isEmpty();
        assertThat(findCard(board, ISSUE)).isPresent();
    }

    @Test
    void runningActionEndsWhenTheAssigneeChanges() throws InterruptedException {
        Path buildStarted = temporaryDirectory.resolve("build-started");
        CountDownLatch pollSawTheNewAssignee = new CountDownLatch(1);
        FakeAgentRunner agent = new FakeAgentRunner().thenChangeFileAnd(() -> {
            reassignToAlice();
            awaitQuietly(pollSawTheNewAssignee);
        });
        Worker worker = createWorkerWithBuild(agent, "touch " + buildStarted);
        worker.pollOnce();
        waitUntil(() -> gitHub.assigneesOf(ISSUE).contains(ALICE));

        worker.pollOnce();
        pollSawTheNewAssignee.countDown();
        waitForTheLoops();

        assertThat(agent.requests()).hasSize(1);
        assertThat(agent.stoppedIssues()).isEmpty();
        assertThat(buildStarted).doesNotExist();
        assertThat(readRecord(ISSUE).orElseThrow().handOver()).contains(HandOver.RELEASED);
        assertThat(stopComments(ISSUE)).hasSize(1);
        assertThat(worker.issuesInWork()).isEmpty();
    }

    @Test
    void buildPushRefusedEndsTheLoopWithoutABlock() throws InterruptedException, IOException {
        Path buildStarted = temporaryDirectory.resolve("build-started");
        Path buildMayEnd = temporaryDirectory.resolve("build-may-end");
        String waitingBuild = "touch " + buildStarted + "; while [ ! -f " + buildMayEnd + " ]; do sleep 0.05; done";
        Worker worker = createWorkerWithBuild(new FakeAgentRunner().thenChangeFile(), waitingBuild);
        worker.pollOnce();
        waitUntil(() -> Files.exists(buildStarted));

        reassignToAlice();
        Files.createFile(buildMayEnd);
        waitForTheLoops();

        IssueRecord record = readRecord(ISSUE).orElseThrow();
        assertThat(record.state()).isNotEqualTo("blocked");
        assertThat(record.handOver()).contains(HandOver.RELEASED);
        assertThat(gitHub.pullRequests()).isEmpty();
        assertThat(stopComments(ISSUE)).hasSize(1);
    }

    @Test
    void actionResultAfterTheLossDoesNotChangeTheColumnLabel() throws InterruptedException {
        CountDownLatch pollSawTheNewAssignee = new CountDownLatch(1);
        FakeAgentRunner agent = new FakeAgentRunner().thenChangeFileAnd(() -> {
            reassignToAlice();
            gitHub.setColumn(gitHub.issue(ISSUE), "waits-for-you");
            awaitQuietly(pollSawTheNewAssignee);
        });
        Worker worker = createWorker(agent, 2);
        worker.pollOnce();
        waitUntil(() -> gitHub.assigneesOf(ISSUE).contains(ALICE));
        Set<String> columnLabelsOfTheNewOwner = gitHub.columnLabelsOf(ISSUE);

        worker.pollOnce();
        pollSawTheNewAssignee.countDown();
        waitForTheLoops();

        assertThat(readRecord(ISSUE).orElseThrow().handOver()).contains(HandOver.RELEASED);
        assertThat(gitHub.columnLabelsOf(ISSUE)).isEqualTo(columnLabelsOfTheNewOwner);
    }

    @Test
    void losingBuildDoesNotRemoveTheApprovalOfTheNewOwner() throws InterruptedException, IOException {
        Path buildStarted = temporaryDirectory.resolve("build-started");
        Path buildMayEnd = temporaryDirectory.resolve("build-may-end");
        String waitingBuild = "touch " + buildStarted + "; while [ ! -f " + buildMayEnd + " ]; do sleep 0.05; done";
        Worker worker = createWorkerWithBuild(new FakeAgentRunner().thenChangeFile(), waitingBuild);
        worker.pollOnce();
        waitUntil(() -> Files.exists(buildStarted));

        reassignToAlice();
        gitHub.addLabel(ISSUE, APPROVE_LABEL);
        Files.createFile(buildMayEnd);
        waitForTheLoops();

        assertThat(gitHub.labelsOf(ISSUE)).contains(APPROVE_LABEL);
        assertThat(readRecord(ISSUE).orElseThrow().handOver()).contains(HandOver.RELEASED);
    }

    @Test
    void liveSessionStaysOpenWhenTheAssigneeChanges() throws InterruptedException {
        Worker worker = enterTheWaitingIssue();
        reassignToAlice();

        worker.pollOnce();
        liveSessions.requestContinue(ISSUE);
        worker.pollOnce();
        waitForTheLoops();

        assertThat(liveSessions.isOpen(ISSUE)).isTrue();
        assertThat(liveSessions.closeCalls()).isEmpty();
        assertThat(readRecord(ISSUE).orElseThrow().state()).isEqualTo("live");
        assertThat(stopComments(ISSUE)).hasSize(1);
    }

    @Test
    void newOwnerContinuesFromThePushedBranch() throws InterruptedException {
        String pushedCommit = pushTheBranchOfAlice();
        recordAnEarlierAssignmentOfAlice();
        addRecordOfAlice(RecordStates.move(RecordStates.queued(ISSUE), "review")
                .withIssueTextHash(IssueText.computeHash(gitHub.issue(ISSUE))));
        gitHub.assignBy(ISSUE, WORKER_LOGIN);
        FakeAgentRunner agent = new FakeAgentRunner().thenApproveAllReviews().thenAssessRisk("medium", true);

        createWorker(agent, 2).pollOnce();
        waitForTheLoops();

        IssueRecord record = readRecord(ISSUE).orElseThrow();
        assertThat(git.headCommit(worktree())).isEqualTo(pushedCommit);
        assertThat(record.state()).isEqualTo("waits-for-approval");
    }

    @Test
    void newOwnerWaitsForTheOwnerWhenTheIssueTextChanged() throws InterruptedException {
        pushTheBranchOfAlice();
        recordAnEarlierAssignmentOfAlice();
        String acceptedTextHash = IssueText.computeHash(gitHub.issue(ISSUE));
        addRecordOfAlice(RecordStates.move(RecordStates.queued(ISSUE), "review")
                .withIssueTextHash(acceptedTextHash));
        gitHub.editIssue(ISSUE, "Change the greeting 7", "Change the greeting to hello.");
        gitHub.assignBy(ISSUE, WORKER_LOGIN);
        FakeAgentRunner agent = new FakeAgentRunner().thenApproveAllReviews().thenAssessRisk("medium", true);

        createWorker(agent, 2).pollOnce();
        waitForTheLoops();

        IssueRecord record = readRecord(ISSUE).orElseThrow();
        assertThat(agent.requests()).isEmpty();
        assertThat(record.column()).isEqualTo("waits-for-you");
        assertThat(record.state()).isEqualTo("text-changed");
    }

    @Test
    void newOwnerWaitsForTheOwnerWhenTheEarlierRecordHasNoTextHash() throws InterruptedException {
        recordAnEarlierAssignmentOfAlice();
        addRecordOfAlice(RecordStates.move(RecordStates.queued(ISSUE), "review"));
        gitHub.assignBy(ISSUE, WORKER_LOGIN);
        FakeAgentRunner agent = new FakeAgentRunner().thenChangeFile();

        createWorker(agent, 2).pollOnce();
        waitForTheLoops();

        assertThat(agent.requests()).isEmpty();
        assertThat(readRecord(ISSUE).orElseThrow().state()).isEqualTo("text-changed");
    }

    @Test
    void newOwnerKeepsABlock() throws InterruptedException {
        recordAnEarlierAssignmentOfAlice();
        addRecordOfAlice(RecordStates.move(RecordStates.queued(ISSUE), "blocked")
                .withNewestNote("The owner blocked the issue."));
        gitHub.assignBy(ISSUE, WORKER_LOGIN);
        FakeAgentRunner agent = new FakeAgentRunner();

        createWorker(agent, 2).pollOnce();
        waitForTheLoops();

        IssueRecord record = readRecord(ISSUE).orElseThrow();
        assertThat(record.state()).isEqualTo("blocked");
        assertThat(record.column()).isEqualTo("blocked");
        assertThat(agent.requests()).isEmpty();
    }

    @Test
    void factoryThatComesBackContinuesAsANewOwner() throws InterruptedException {
        runToTheMergeApproval();
        reassignToAlice();
        createWorker(new FakeAgentRunner(), 0).pollOnce();
        assertThat(readRecord(ISSUE).orElseThrow().handOver()).contains(HandOver.RELEASED);
        reassignToTheWorker();

        createWorker(new FakeAgentRunner(), 0).pollOnce();

        IssueRecord record = readRecord(ISSUE).orElseThrow();
        assertThat(record.handOver()).contains(HandOver.CONTINUES_PUSHED_BRANCH);
        assertThat(record.pullRequestNumber()).contains(gitHub.pullRequests().getFirst().number());
    }

    @Test
    void factoryThatComesBackWhileOffContinuesThePushedBranch() throws InterruptedException {
        createWorker(new FakeAgentRunner().thenChangeFile().thatFails(), 2).pollOnce();
        waitForTheLoops();
        assertThat(readRecord(ISSUE).orElseThrow().handOver()).isEmpty();
        reassignToAlice();
        String pushedCommit = pushTheBranchOfAlice();
        addRecordOfAlice(RecordStates.move(RecordStates.queued(ISSUE), "review")
                .withIssueTextHash(IssueText.computeHash(gitHub.issue(ISSUE))));
        reassignToTheWorker();

        createWorker(new FakeAgentRunner(), 0).pollOnce();
        assertThat(readRecord(ISSUE).orElseThrow().handOver()).contains(HandOver.CONTINUES_PUSHED_BRANCH);
        createWorker(new FakeAgentRunner().thenApproveAllReviews().thenAssessRisk("medium", true), 2).pollOnce();
        waitForTheLoops();

        assertThat(git.headCommit(worktree())).isEqualTo(pushedCommit);
        assertThat(readRecord(ISSUE).orElseThrow().state()).isEqualTo("waits-for-approval");
    }

    @Test
    void secondPollAfterTheTakeOverDoesNotTakeOverAgain() {
        recordAnEarlierAssignmentOfAlice();
        addRecordOfAlice(RecordStates.move(RecordStates.queued(ISSUE), "review"));
        gitHub.assignBy(ISSUE, WORKER_LOGIN);
        createWorker(new FakeAgentRunner(), 0).pollOnce();
        IssueRecord afterTheTakeOver = readRecord(ISSUE).orElseThrow();
        int statusCommentsAfterTheTakeOver = statusComments(ISSUE).size();

        createWorker(new FakeAgentRunner(), 0).pollOnce();

        assertThat(readRecord(ISSUE)).contains(afterTheTakeOver);
        assertThat(statusComments(ISSUE)).hasSize(statusCommentsAfterTheTakeOver);
    }

    @Test
    void strangerStatusCommentDoesNotStartATakeOver() {
        gitHub.addCommentBy(ISSUE, "mallory", StatusComment.render(
                RecordStates.move(RecordStates.queued(ISSUE), "blocked"), LIMITS, RecordStates.REVIEW_NAMES));

        createWorker(new FakeAgentRunner(), 0).pollOnce();

        IssueRecord record = readRecord(ISSUE).orElseThrow();
        assertThat(record.column()).isEqualTo("queued");
        assertThat(record.state()).isEqualTo("prepare");
        assertThat(record.handOver()).isEmpty();
        assertThat(record.notes()).noneMatch(note -> note.contains("earlier owner"));
    }

    @Test
    void noActionStartsAfterTheAssigneeChangesBetweenPolls() throws InterruptedException {
        Path buildStarted = temporaryDirectory.resolve("build-started");
        gitHub.whenACommentEditContains("\"state\":\"build\"", this::reassignToAlice);
        FakeAgentRunner agent = new FakeAgentRunner().thenChangeFile();
        Worker worker = createWorkerWithBuild(agent, "touch " + buildStarted);

        worker.pollOnce();
        waitForTheLoops();

        assertThat(agent.requests()).hasSize(1);
        assertThat(buildStarted).doesNotExist();
        assertThat(readRecord(ISSUE).orElseThrow().handOver()).contains(HandOver.RELEASED);
        assertThat(stopComments(ISSUE)).hasSize(1);
    }

    @Test
    void aPollDuringTheReleaseWritesTheReleaseOnce() throws InterruptedException {
        FakeAgentRunner agent = new FakeAgentRunner().thenChangeFileAnd(this::reassignToAlice);
        Worker worker = createWorker(agent, 2);
        gitHub.whenACommentEditContains("\"handOver\":\"RELEASED\"", worker::pollOnce);

        worker.pollOnce();
        waitForTheLoops();

        assertThat(readRecord(ISSUE).orElseThrow().handOver()).contains(HandOver.RELEASED);
        assertThat(stopComments(ISSUE)).hasSize(1);
    }

    @Test
    void claimFailureSkipsOnlyThatIssue() throws InterruptedException {
        addLabeledIssue(3);
        gitHub.whenAssigning(() -> {
            throw new GitHubException("GitHub answered 502 for POST /issues/3/assignees");
        });
        FakeAgentRunner agent = new FakeAgentRunner().thenWaitForStop().thatFails();
        Worker worker = createWorker(agent, 2);

        worker.pollOnce();

        assertThat(worker.issuesInWork()).containsExactly(ISSUE);
        assertThat(readRecord(3)).isEmpty();
        waitUntil(() -> agent.waitingPhaseCount() == 1);
        agent.stopPhase(ISSUE);
        waitForTheLoops();
    }
}

