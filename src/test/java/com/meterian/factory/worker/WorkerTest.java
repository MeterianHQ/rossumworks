package com.meterian.factory.worker;

import static com.meterian.factory.testing.TemporaryRepository.createBare;
import static com.meterian.factory.testing.TemporaryRepository.createClone;
import static com.meterian.factory.testing.TemporaryRepository.run;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import com.meterian.factory.agent.PhaseRequest;
import com.meterian.factory.board.BoardState;
import com.meterian.factory.board.IssueCard;
import com.meterian.factory.build.BuildRunner;
import com.meterian.factory.configuration.ClaudeSettings;
import com.meterian.factory.configuration.FactoryConfiguration;
import com.meterian.factory.configuration.IsolationMode;
import com.meterian.factory.configuration.Limits;
import com.meterian.factory.configuration.TargetRepository;
import com.meterian.factory.git.Gatekeeper;
import com.meterian.factory.git.Git;
import com.meterian.factory.git.ProcessGit;
import com.meterian.factory.github.GitHubException;
import com.meterian.factory.github.IssueComment;
import com.meterian.factory.merge.Merger;
import com.meterian.factory.definition.BuiltInDefinition;
import com.meterian.factory.prompt.PromptLibrary;
import com.meterian.factory.prompt.PromptTexts;
import com.meterian.factory.state.IssueRecord;
import com.meterian.factory.state.IssueStatus;
import com.meterian.factory.state.LiveSession;
import com.meterian.factory.state.Phase;
import com.meterian.factory.state.WaitReason;
import com.meterian.factory.state.StatusComment;
import com.meterian.factory.testing.FakeAgentRunner;
import com.meterian.factory.testing.FakeGitHubClient;
import com.meterian.factory.testing.FakeLiveSessions;
import com.meterian.factory.testing.MutableClock;
import com.meterian.factory.usage.ContextTracker;
import com.meterian.factory.usage.UsageTracker;
import com.meterian.factory.workflow.IssueText;
import com.meterian.factory.workflow.IssueWorkflow;
import com.meterian.factory.workflow.PageRequests;
import com.meterian.factory.workflow.RequestReader;
import com.meterian.factory.workflow.WorkspaceLayout;
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
    private static final int STRANGER_ISSUE = 8;
    private static final String WORKER_LOGIN = "factory-bot";
    private static final String FACTORY_LABEL = "factory";
    private static final String BLOCK_LABEL = "factory:block";
    private static final String APPROVE_LABEL = "factory:approve";
    private static final String ENTER_LABEL = "factory:enter";
    private static final String MALLORY_ENTER_NOTE =
            "The label factory:enter from mallory is ignored: the account is not in accounts.allowed.";
    private static final String ENDED_NOTE = "The live conversation ended without /factory:continue."
            + " Add the label factory:enter to open a new one.";
    private static final String MALLORY_NOTE =
            "The label factory from mallory is ignored: the account is not in accounts.allowed.";
    private static final String MALLORY_BLOCK_NOTE =
            "The label factory:block from mallory is ignored: the account is not in accounts.allowed.";
    private static final String DONE_BLOCK_NOTE = "The issue is already done; the block has no effect.";
    private static final String FINISH_FAILURE_NOTE =
            "A step after the merge failed: java.lang.IllegalStateException. The change is merged.";
    private static final String BROKEN_RECORD_NOTE =
            "The status record of the factory is broken. The factory does not work on this issue.";
    private static final Duration TIME_BOUND = Duration.ofSeconds(30);
    /** A static reference: the logging framework keeps only a weak reference to a logger. */
    private static final Logger WORKER_LOGGER = Logger.getLogger(Worker.class.getName());

    @TempDir
    Path temporaryDirectory;

    private final PageRequests pageRequests = new PageRequests(Clock.systemUTC());
    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-01T09:00:00Z"));
    private final BoardState board = new BoardState(clock);

    private final Git git = new ProcessGit();
    private final FakeGitHubClient gitHub = new FakeGitHubClient();
    private final FakeLiveSessions liveSessions = new FakeLiveSessions();
    private final List<ExecutorService> executors = new ArrayList<>();
    private Path clone;

    /** The tests cause the failures on purpose, so the log of the worker is only noise. */
    @BeforeAll
    static void silenceTheWorkerLog() {
        WORKER_LOGGER.setLevel(Level.OFF);
    }

    @BeforeEach
    void createRepositoriesAndIssue() {
        Path origin = createBare(temporaryDirectory.resolve("origin.git"));
        clone = createClone(origin, temporaryDirectory.resolve("clone"));
        Path githubRepository = createBare(temporaryDirectory.resolve("github.git"));
        Path gatekeeper = temporaryDirectory.resolve("gatekeeper.git");
        String targetUrl = createTarget().sshUrl();
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

    private void addLabeledIssue(int number) {
        gitHub.addIssue(number, "Change the greeting " + number, "The greeting must change.");
        gitHub.addLabelBy(number, FACTORY_LABEL, "bbossola");
    }

    private TargetRepository createTarget() {
        return createTarget("true");
    }

    private TargetRepository createTarget(String buildCommand) {
        return new TargetRepository("MeterianHQ", "sample", clone, buildCommand, Map.of());
    }

    private FactoryConfiguration createConfiguration(int parallelIssues, Duration pollInterval) {
        return createConfiguration(parallelIssues, pollInterval, "true");
    }

    private FactoryConfiguration createConfiguration(int parallelIssues, Duration pollInterval, String buildCommand) {
        return new FactoryConfiguration(createTarget(buildCommand), "main",
                new Limits(parallelIssues, 2, pollInterval, 0.90),
                List.of("bbossola"), IsolationMode.SAME_USER, ClaudeSettings.createDefaults(),
                temporaryDirectory.resolve("work"), temporaryDirectory.resolve("gatekeeper.git"), Optional.empty(),
                temporaryDirectory.resolve("live"), Optional.empty(),
                FactoryConfiguration.DEFAULT_TERMINAL_COMMAND);
    }

    private Worker createWorker(FakeAgentRunner agent, int parallelIssues) {
        return createWorker(agent, createConfiguration(parallelIssues, Duration.ofSeconds(60)));
    }

    private Worker createWorker(FakeAgentRunner agent, FactoryConfiguration configuration) {
        return createWorker(agent, configuration, board);
    }

    private Worker createWorker(FakeAgentRunner agent, FactoryConfiguration configuration, BoardState board) {
        BuildRunner buildRunner = new BuildRunner();
        RequestReader requests =
                new RequestReader(gitHub, configuration.allowedAccounts(), WORKER_LOGIN, pageRequests);
        Merger merger = new Merger(git, gitHub, buildRunner, configuration.target(), "main",
                configuration.gatekeeperDirectory(), Duration.ZERO);
        IssueWorkflow workflow = new IssueWorkflow(configuration, gitHub, git, agent,
                new PromptLibrary(PromptTexts.from(BuiltInDefinition.load()), "main"), buildRunner, merger,
                new UsageTracker(0.90, Clock.systemUTC()), requests,
                new WorkspaceLayout(configuration.workDirectory()), WORKER_LOGIN, liveSessions,
                board, new ContextTracker(), pageRequests);
        ExecutorService executor = Executors.newFixedThreadPool(Math.max(1, configuration.limits().parallelIssues()));
        executors.add(executor);
        PhaseStopper phaseStopper = new PhaseStopper(agent, buildRunner,
                new WorkspaceLayout(configuration.workDirectory()), liveSessions);
        return new Worker(configuration, gitHub, workflow, phaseStopper, requests, WORKER_LOGIN, executor,
                liveSessions, Clock.systemUTC(), board);
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
        assertThat(readRecord(ISSUE).orElseThrow().phase()).isEqualTo(Phase.REVIEW);
        return firstAgent;
    }

    @Test
    void thePollPutsTheIssuesOnTheBoard() {
        addLabeledIssue(1);
        Worker worker = createWorker(new FakeAgentRunner(), 0);

        worker.pollOnce();

        IssueCard card = findCard(board, 1).orElseThrow();
        assertThat(card.title()).isEqualTo("Change the greeting 1");
        assertThat(card.record().status()).isEqualTo(IssueStatus.QUEUED);
        assertThat(board.snapshot().lastCheckAt()).contains(clock.instant());
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
    void aStepUpdatesTheCardBetweenPolls() throws InterruptedException {
        Worker worker = createWorker(new FakeAgentRunner().thenAskQuestion("Which greeting do you want?"), 2);

        worker.pollOnce();
        waitForTheLoops();

        IssueRecord onGitHub = readRecord(ISSUE).orElseThrow();
        assertThat(onGitHub.status()).isEqualTo(IssueStatus.WAITS_FOR_YOU);
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
    void anIssueFromAnotherAccountMakesNoCard() {
        gitHub.addIssue(STRANGER_ISSUE, "Delete the history", "Delete it.");
        gitHub.addLabelBy(STRANGER_ISSUE, FACTORY_LABEL, "stranger");
        Worker worker = createWorker(new FakeAgentRunner(), 0);

        worker.pollOnce();

        assertThat(findCard(board, STRANGER_ISSUE)).isEmpty();
        assertThat(findCard(board, ISSUE)).isPresent();
    }

    @Test
    void takesALabeledIssueIntoTheQueue() {
        Worker worker = createWorker(new FakeAgentRunner(), 0);

        worker.pollOnce();
        worker.pollOnce();

        assertThat(gitHub.statusOf(ISSUE)).contains(IssueStatus.QUEUED);
        assertThat(statusComments(ISSUE)).hasSize(1);
        assertThat(readRecord(ISSUE).orElseThrow().status()).isEqualTo(IssueStatus.QUEUED);
        assertThat(readRecord(ISSUE).orElseThrow().issueTextHash())
                .contains(IssueText.computeHash(gitHub.issue(ISSUE)));
    }

    @Test
    void ignoresAnIssueLabeledByAnotherAccount() {
        gitHub.addIssue(8, "Delete the history", "Delete it.");
        gitHub.addLabelBy(8, FACTORY_LABEL, "mallory");
        Worker worker = createWorker(new FakeAgentRunner(), 0);

        worker.pollOnce();
        worker.pollOnce();

        assertThat(readRecord(8)).isEmpty();
        assertThat(gitHub.commentsOf(8)).extracting(IssueComment::body).containsExactly(MALLORY_NOTE);
        assertThat(gitHub.statusOf(8)).isEmpty();
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
        assertThat(readRecord(9).orElseThrow().status()).isEqualTo(IssueStatus.QUEUED);
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
        gitHub.addLabelBy(ISSUE, BLOCK_LABEL, "bbossola");

        worker.pollOnce();
        waitForTheLoops();

        IssueRecord record = readRecord(ISSUE).orElseThrow();
        assertThat(agent.stoppedIssues()).contains(ISSUE);
        assertThat(record.status()).isEqualTo(IssueStatus.BLOCKED);
        assertThat(record.waitReason()).contains(WaitReason.BLOCKED_BY_OWNER);
        assertThat(record.phase()).isEqualTo(Phase.IMPLEMENT);
        assertThat(record.notes()).noneMatch(note -> note.startsWith("The agent session failed"));
        assertThat(gitHub.statusOf(ISSUE)).contains(IssueStatus.BLOCKED);
        assertThat(gitHub.labelsOf(ISSUE)).doesNotContain(BLOCK_LABEL);
        assertThat(statusComments(ISSUE)).hasSize(1);
        assertThat(worker.issuesInWork()).isEmpty();
    }

    @Test
    void blocksAnIssueThatIsNotInWork() {
        Worker worker = createWorker(new FakeAgentRunner(), 0);
        worker.pollOnce();
        gitHub.addLabelBy(ISSUE, BLOCK_LABEL, "bbossola");

        worker.pollOnce();

        IssueRecord record = readRecord(ISSUE).orElseThrow();
        assertThat(record.status()).isEqualTo(IssueStatus.BLOCKED);
        assertThat(record.waitReason()).contains(WaitReason.BLOCKED_BY_OWNER);
        assertThat(gitHub.labelsOf(ISSUE)).doesNotContain(BLOCK_LABEL);
    }

    @Test
    void ignoresABlockFromAnotherAccount() {
        Worker worker = createWorker(new FakeAgentRunner(), 0);
        worker.pollOnce();
        gitHub.addLabelBy(ISSUE, BLOCK_LABEL, "mallory");

        worker.pollOnce();
        worker.pollOnce();

        assertThat(readRecord(ISSUE).orElseThrow().status()).isEqualTo(IssueStatus.QUEUED);
        assertThat(gitHub.commentsOf(ISSUE)).extracting(IssueComment::body)
                .filteredOn(body -> body.equals(MALLORY_BLOCK_NOTE)).hasSize(1);
    }

    @Test
    void aBlockDuringTheMergeKeepsTheMerge() throws InterruptedException {
        FakeAgentRunner agent = new FakeAgentRunner()
                .thenChangeFile().thenApproveAllReviews().thenAssessRisk("low", true);
        Worker worker = createWorker(agent, 2);
        gitHub.whenSquashMerging(() -> {
            gitHub.addLabelBy(ISSUE, BLOCK_LABEL, "bbossola");
            worker.pollOnce();
        });

        worker.pollOnce();
        waitForTheLoops();

        assertThat(agent.stoppedIssues()).contains(ISSUE);
        assertThat(readRecord(ISSUE).orElseThrow().status()).isEqualTo(IssueStatus.DONE);
        assertThat(gitHub.statusOf(ISSUE)).contains(IssueStatus.DONE);
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
            gitHub.addLabelBy(ISSUE, BLOCK_LABEL, "bbossola");
            worker.pollOnce();
        });

        worker.pollOnce();
        waitForTheLoops();

        IssueRecord record = readRecord(ISSUE).orElseThrow();
        assertThat(record.phase()).isEqualTo(Phase.DONE);
        assertThat(record.status()).isNotEqualTo(IssueStatus.BLOCKED);
        assertThat(record.waitReason()).isEmpty();
        assertThat(record.notes()).containsExactly(DONE_BLOCK_NOTE);
        assertThat(gitHub.labelsOf(ISSUE)).doesNotContain(BLOCK_LABEL);
        assertThat(gitHub.squashMergeCalls()).hasSize(1);
    }

    @Test
    void aDefectInTheFinishStepKeepsTheIssueDone() throws InterruptedException {
        gitHub.failTheRemoveLabelAfterTheMergeWithADefect();
        createWorker(new FakeAgentRunner().thenChangeFile().thenApproveAllReviews().thenAssessRisk("low", true), 2)
                .pollOnce();
        waitForTheLoops();
        IssueRecord afterTheFailure = readRecord(ISSUE).orElseThrow();
        assertThat(afterTheFailure.phase()).isEqualTo(Phase.DONE);
        assertThat(afterTheFailure.status()).isNotEqualTo(IssueStatus.BLOCKED);
        assertThat(afterTheFailure.notes()).containsExactly(FINISH_FAILURE_NOTE);

        createWorker(new FakeAgentRunner(), 2).pollOnce();
        waitForTheLoops();

        IssueRecord record = readRecord(ISSUE).orElseThrow();
        assertThat(record.status()).isEqualTo(IssueStatus.DONE);
        assertThat(record.notes()).containsExactly(FINISH_FAILURE_NOTE);
        assertThat(gitHub.labelsOf(ISSUE)).doesNotContain(FACTORY_LABEL);
        assertThat(worktree()).doesNotExist();
        assertThat(gitHub.squashMergeCalls()).hasSize(1);
    }

    @Test
    void aFailedFinishStepLeavesTheLabelFactoryForTheNextPoll() throws InterruptedException {
        gitHub.failTheRemoveLabelAfterTheMerge();
        createWorker(new FakeAgentRunner().thenChangeFile().thenApproveAllReviews().thenAssessRisk("low", true), 2)
                .pollOnce();
        waitForTheLoops();
        assertThat(gitHub.statusOf(ISSUE)).contains(IssueStatus.DONE);
        assertThat(gitHub.labelsOf(ISSUE)).contains(FACTORY_LABEL);
        assertThat(worktree()).doesNotExist();

        createWorker(new FakeAgentRunner(), 2).pollOnce();
        waitForTheLoops();

        assertThat(readRecord(ISSUE).orElseThrow().status()).isEqualTo(IssueStatus.DONE);
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
            gitHub.addLabelBy(ISSUE, BLOCK_LABEL, "bbossola");
            worker.pollOnce();
        });
        worker.pollOnce();
        waitForTheLoops();
        assertThat(readRecord(ISSUE).orElseThrow().status()).isNotEqualTo(IssueStatus.BLOCKED);

        createWorker(new FakeAgentRunner(), 2).pollOnce();
        waitForTheLoops();

        IssueRecord record = readRecord(ISSUE).orElseThrow();
        assertThat(record.status()).isEqualTo(IssueStatus.DONE);
        assertThat(gitHub.editedBodies()).noneMatch(body -> body.contains("Blocked"));
        assertThat(gitHub.labelsOf(ISSUE)).doesNotContain(BLOCK_LABEL, FACTORY_LABEL);
        assertThat(gitHub.squashMergeCalls()).hasSize(1);
    }

    @Test
    void aBlockOnADoneIssueKeepsItDone() throws InterruptedException {
        createWorker(new FakeAgentRunner().thenChangeFile().thenApproveAllReviews().thenAssessRisk("low", true), 2)
                .pollOnce();
        waitForTheLoops();
        gitHub.addLabelBy(ISSUE, FACTORY_LABEL, "bbossola");
        gitHub.addLabelBy(ISSUE, BLOCK_LABEL, "bbossola");
        Worker worker = createWorker(new FakeAgentRunner(), 2);

        worker.pollOnce();
        gitHub.addLabelBy(ISSUE, BLOCK_LABEL, "bbossola");
        worker.pollOnce();

        IssueRecord record = readRecord(ISSUE).orElseThrow();
        assertThat(record.status()).isEqualTo(IssueStatus.DONE);
        assertThat(record.notes()).containsExactly(DONE_BLOCK_NOTE);
        assertThat(gitHub.statusOf(ISSUE)).contains(IssueStatus.DONE);
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
            assertThat(record.status()).isEqualTo(IssueStatus.IN_PROGRESS);
            assertThat(record.phase()).isEqualTo(Phase.IMPLEMENT);
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
        assertThat(gitHub.statusOf(ISSUE)).contains(IssueStatus.QUEUED);
    }

    @Test
    void aPollDuringTheBlockDoesNotBlockTwice() throws InterruptedException {
        FakeAgentRunner agent = new FakeAgentRunner().thenWaitForStop().thatFails();
        Worker worker = createWorker(agent, 2);
        worker.pollOnce();
        waitUntil(() -> agent.waitingPhaseCount() == 1);
        gitHub.whenACommentEditContains(WaitReason.BLOCKED_BY_OWNER.sentence(), worker::pollOnce);
        gitHub.addLabelBy(ISSUE, BLOCK_LABEL, "bbossola");

        worker.pollOnce();
        waitForTheLoops();

        assertThat(agent.stoppedIssues()).containsExactly(ISSUE);
        assertThat(readRecord(ISSUE).orElseThrow().waitReason()).contains(WaitReason.BLOCKED_BY_OWNER);
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
            assertThat(readRecord(issueNumber).orElseThrow().status()).isNotEqualTo(IssueStatus.BLOCKED);
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
        assertThat(stopped.phase()).isEqualTo(Phase.REVIEW);
        assertThat(stopped.fixLoops()).isEqualTo(1);
        run(clone, "worktree", "remove", "--force", worktree().toString());
        FakeAgentRunner secondAgent = new FakeAgentRunner()
                .thenChangeFile().thenApproveAllReviews().thenAssessRisk("low", true);

        createWorker(secondAgent, 2).pollOnce();
        waitForTheLoops();

        PhaseRequest implement = secondAgent.requests().getFirst();
        IssueRecord record = readRecord(ISSUE).orElseThrow();
        assertThat(record.status()).isEqualTo(IssueStatus.DONE);
        assertThat(record.fixLoops()).isEqualTo(1);
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

        assertThat(gitHub.statusOf(8)).contains(IssueStatus.QUEUED);
        assertThat(worker.isStopped()).isFalse();
    }

    @Test
    void aRestartRunsThePhaseAgainWithoutASecondComment() throws InterruptedException {
        stopAWorkerDuringTheReview();
        FakeAgentRunner secondAgent = new FakeAgentRunner().thenApproveAllReviews().thenAssessRisk("low", true);

        createWorker(secondAgent, 2).pollOnce();
        waitForTheLoops();

        assertThat(readRecord(ISSUE).orElseThrow().status()).isEqualTo(IssueStatus.DONE);
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
        assertThat(readRecord(ISSUE).orElseThrow().status()).isEqualTo(IssueStatus.DONE);
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
        assertThat(readRecord(ISSUE).orElseThrow().waitReason()).contains(WaitReason.MERGE_NEEDS_APPROVAL);
        gitHub.addLabelBy(ISSUE, APPROVE_LABEL, "bbossola");

        createWorker(new FakeAgentRunner(), 2).pollOnce();
        waitForTheLoops();

        assertThat(readRecord(ISSUE).orElseThrow().status()).isEqualTo(IssueStatus.DONE);
        assertThat(gitHub.squashMergeCalls()).hasSize(1);
    }

    @Test
    void anApproveFromThePageMergesTheIssue() throws InterruptedException {
        createWorker(new FakeAgentRunner().thenChangeFile().thenApproveAllReviews().thenAssessRisk("medium", true), 2)
                .pollOnce();
        waitForTheLoops();
        assertThat(readRecord(ISSUE).orElseThrow().waitReason()).contains(WaitReason.MERGE_NEEDS_APPROVAL);
        pageRequests.remember(ISSUE, APPROVE_LABEL);
        gitHub.addLabelBy(ISSUE, APPROVE_LABEL, WORKER_LOGIN);

        createWorker(new FakeAgentRunner(), 2).pollOnce();
        waitForTheLoops();

        assertThat(readRecord(ISSUE).orElseThrow().status()).isEqualTo(IssueStatus.DONE);
        assertThat(gitHub.squashMergeCalls()).hasSize(1);
    }

    @Test
    void aPageLabelAfterARestartIsIgnoredWithANote() throws InterruptedException {
        createWorker(new FakeAgentRunner().thenChangeFile().thenApproveAllReviews().thenAssessRisk("medium", true), 2)
                .pollOnce();
        waitForTheLoops();
        gitHub.addLabelBy(ISSUE, APPROVE_LABEL, WORKER_LOGIN);

        createWorker(new FakeAgentRunner(), 2).pollOnce();
        waitForTheLoops();

        assertThat(gitHub.squashMergeCalls()).isEmpty();
        assertThat(gitHub.commentsOf(ISSUE)).extracting(IssueComment::body)
                .anyMatch(body -> body.contains("from " + WORKER_LOGIN + " is ignored"));
    }

    @Test
    void continuesAnIssueAfterTheOwnerAcceptsAChangedText() throws InterruptedException {
        createWorker(new FakeAgentRunner(), 0).pollOnce();
        gitHub.editIssue(ISSUE, "Change the greeting 7", "Change the greeting to hello.");
        createWorker(new FakeAgentRunner(), 2).pollOnce();
        waitForTheLoops();
        assertThat(readRecord(ISSUE).orElseThrow().waitReason()).contains(WaitReason.ISSUE_TEXT_CHANGED);
        gitHub.addLabelBy(ISSUE, APPROVE_LABEL, "bbossola");
        FakeAgentRunner agent = new FakeAgentRunner()
                .thenChangeFile().thenApproveAllReviews().thenAssessRisk("low", true);

        createWorker(agent, 2).pollOnce();
        waitForTheLoops();

        assertThat(readRecord(ISSUE).orElseThrow().status()).isEqualTo(IssueStatus.DONE);
        assertThat(agent.requests().getFirst().prompt()).contains("Change the greeting to hello.");
    }

    @Test
    void aBlockStopsTheRunningBuild() throws InterruptedException {
        Path started = temporaryDirectory.resolve("build-started");
        Worker worker = createWorker(new FakeAgentRunner().thenChangeFile(),
                createConfiguration(2, Duration.ofSeconds(60), "touch " + started + "; sleep 60"));
        worker.pollOnce();
        waitUntil(() -> Files.exists(started));
        gitHub.addLabelBy(ISSUE, BLOCK_LABEL, "bbossola");

        worker.pollOnce();
        waitForTheLoops();

        IssueRecord record = readRecord(ISSUE).orElseThrow();
        assertThat(record.status()).isEqualTo(IssueStatus.BLOCKED);
        assertThat(record.waitReason()).contains(WaitReason.BLOCKED_BY_OWNER);
        assertThat(record.phase()).isEqualTo(Phase.BUILD);
        assertThat(record.fixLoops()).isZero();
    }

    @Test
    void shutDownStopsTheRunningBuild() throws InterruptedException {
        Path started = temporaryDirectory.resolve("build-started");
        Worker worker = createWorker(new FakeAgentRunner().thenChangeFile(),
                createConfiguration(2, Duration.ofSeconds(60), "touch " + started + "; sleep 60"));
        worker.pollOnce();
        waitUntil(() -> Files.exists(started));

        boolean loopsEnded = worker.shutDown(Duration.ofSeconds(20));

        IssueRecord record = readRecord(ISSUE).orElseThrow();
        assertThat(loopsEnded).isTrue();
        assertThat(record.phase()).isEqualTo(Phase.BUILD);
        assertThat(record.status()).isEqualTo(IssueStatus.IN_PROGRESS);
        assertThat(record.fixLoops()).isZero();
    }

    @Test
    void aRestartWithoutTheWorktreeOfAMergedIssueEndsDone() throws InterruptedException {
        gitHub.failTheCommentEditAfterTheMerge();
        createWorker(new FakeAgentRunner().thenChangeFile().thenApproveAllReviews().thenAssessRisk("low", true), 2)
                .pollOnce();
        waitForTheLoops();
        assertThat(readRecord(ISSUE).orElseThrow().phase()).isEqualTo(Phase.MERGE);
        run(clone, "worktree", "remove", "--force", worktree().toString());
        FakeAgentRunner secondAgent = new FakeAgentRunner();

        createWorker(secondAgent, 2).pollOnce();
        waitForTheLoops();

        IssueRecord record = readRecord(ISSUE).orElseThrow();
        assertThat(record.status()).isEqualTo(IssueStatus.DONE);
        assertThat(secondAgent.requests()).isEmpty();
        assertThat(gitHub.squashMergeCalls()).hasSize(1);
        assertThat(gitHub.labelsOf(ISSUE)).doesNotContain(FACTORY_LABEL);
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
        assertThat(otherRecord.status()).isIn(IssueStatus.QUEUED, IssueStatus.IN_PROGRESS);
        assertThat(otherRecord.phase()).isIn(Phase.PREPARE, Phase.IMPLEMENT);
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
        assertThat(readRecord(ISSUE).orElseThrow().phase()).isEqualTo(Phase.IMPLEMENT);

        worker.pollOnce();
        waitForTheLoops();

        assertThat(worker.isStopped()).isFalse();
        assertThat(readRecord(ISSUE).orElseThrow().status()).isEqualTo(IssueStatus.DONE);
        assertThat(statusComments(ISSUE)).hasSize(1);
    }

    @Test
    void blocksTheIssueOnAnUnexpectedFailure() throws InterruptedException {
        Worker worker = createWorker(new FakeAgentRunner(), 2);

        worker.pollOnce();
        waitForTheLoops();

        IssueRecord record = readRecord(ISSUE).orElseThrow();
        assertThat(record.status()).isEqualTo(IssueStatus.BLOCKED);
        assertThat(record.waitReason()).contains(WaitReason.FAILURE);
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
        assertThat(record.status()).isEqualTo(IssueStatus.BLOCKED);
        assertThat(record.waitReason()).contains(WaitReason.FAILURE);
        assertThat(record.notes()).containsExactly(BROKEN_RECORD_NOTE);
        assertThat(gitHub.commentsOf(ISSUE)).hasSize(2);
        assertThat(worker.issuesInWork()).isEmpty();
    }

    /** A worker runs the issue to the wait for the merge approval, with the worktree and the agent session. */
    private void runToTheMergeApproval() throws InterruptedException {
        createWorker(new FakeAgentRunner().thenChangeFile().thenApproveAllReviews().thenAssessRisk("medium", true), 2)
                .pollOnce();
        waitForTheLoops();
        assertThat(readRecord(ISSUE).orElseThrow().waitReason()).contains(WaitReason.MERGE_NEEDS_APPROVAL);
    }

    /** The owner gives factory:enter on the waiting issue, and a new worker reads it. */
    private Worker enterTheWaitingIssue() throws InterruptedException {
        runToTheMergeApproval();
        gitHub.addLabelBy(ISSUE, ENTER_LABEL, "bbossola");
        Worker worker = createWorker(new FakeAgentRunner(), 2);
        worker.pollOnce();
        assertThat(readRecord(ISSUE).orElseThrow().waitReason()).contains(WaitReason.LIVE_CONVERSATION);
        return worker;
    }

    private Optional<String> readLiveLink() {
        return readRecord(ISSUE).orElseThrow().live().flatMap(LiveSession::link);
    }

    @Test
    void enterOpensTheConversationOfAWaitingIssue() throws InterruptedException {
        runToTheMergeApproval();
        gitHub.addLabelBy(ISSUE, ENTER_LABEL, "bbossola");
        Worker worker = createWorker(new FakeAgentRunner(), 2);

        worker.pollOnce();

        IssueRecord record = readRecord(ISSUE).orElseThrow();
        assertThat(liveSessions.openCalls()).hasSize(1);
        assertThat(gitHub.labelsOf(ISSUE)).doesNotContain(ENTER_LABEL);
        assertThat(record.status()).isEqualTo(IssueStatus.WAITS_FOR_YOU);
        assertThat(record.waitReason()).contains(WaitReason.LIVE_CONVERSATION);
        assertThat(readLiveLink()).contains("https://claude.ai/code/session_test" + ISSUE);
    }

    @Test
    void enterWaitsForTheRunningPhaseToEnd() throws InterruptedException {
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
        gitHub.addLabelBy(ISSUE, ENTER_LABEL, "bbossola");

        worker.pollOnce();
        assertThat(liveSessions.openCalls()).isEmpty();
        phaseMayEnd.countDown();
        waitForTheLoops();

        IssueRecord record = readRecord(ISSUE).orElseThrow();
        assertThat(agent.stoppedIssues()).isEmpty();
        assertThat(agent.requests()).hasSize(1);
        assertThat(liveSessions.openCalls()).hasSize(1);
        assertThat(record.phase()).isEqualTo(Phase.BUILD);
        assertThat(record.waitReason()).contains(WaitReason.LIVE_CONVERSATION);
        assertThat(gitHub.labelsOf(ISSUE)).doesNotContain(ENTER_LABEL);
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
    void enterFromAnotherAccountIsIgnored() throws InterruptedException {
        runToTheMergeApproval();
        gitHub.addLabelBy(ISSUE, ENTER_LABEL, "mallory");
        Worker worker = createWorker(new FakeAgentRunner(), 2);

        worker.pollOnce();
        worker.pollOnce();

        assertThat(gitHub.commentsOf(ISSUE)).extracting(IssueComment::body)
                .filteredOn(MALLORY_ENTER_NOTE::equals).hasSize(1);
        assertThat(liveSessions.openCalls()).isEmpty();
        assertThat(readRecord(ISSUE).orElseThrow().waitReason()).contains(WaitReason.MERGE_NEEDS_APPROVAL);
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
        assertThat(record.waitReason()).contains(WaitReason.MERGE_NEEDS_APPROVAL);
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
        assertThat(record.status()).isEqualTo(IssueStatus.WAITS_FOR_YOU);
        assertThat(record.waitReason()).contains(WaitReason.LIVE_CONVERSATION);
        gitHub.addLabelBy(ISSUE, ENTER_LABEL, "bbossola");
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
        assertThat(record.waitReason()).contains(WaitReason.MERGE_NEEDS_APPROVAL);
        assertThat(liveSessions.openCalls()).hasSize(1);
    }

    @Test
    void blockClosesTheLiveSession() throws InterruptedException {
        Worker worker = enterTheWaitingIssue();
        gitHub.addLabelBy(ISSUE, BLOCK_LABEL, "bbossola");

        worker.pollOnce();
        worker.pollOnce();

        IssueRecord record = readRecord(ISSUE).orElseThrow();
        assertThat(liveSessions.closeCalls()).containsExactly(ISSUE);
        assertThat(liveSessions.isOpen(ISSUE)).isFalse();
        assertThat(record.status()).isEqualTo(IssueStatus.BLOCKED);
        assertThat(record.waitReason()).contains(WaitReason.BLOCKED_BY_OWNER);
        assertThat(record.live()).isEmpty();
        assertThat(record.notes()).doesNotContain(ENDED_NOTE);
    }

    @Test
    void shutDownKeepsTheLiveSession() throws InterruptedException {
        runToTheMergeApproval();
        addLabeledIssue(8);
        gitHub.addLabelBy(ISSUE, ENTER_LABEL, "bbossola");
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

    /** The owner starts the blocked issue again: the record goes back to the implement step in progress. */
    private void resumeTheBlockedIssue() {
        IssueRecord blocked = readRecord(ISSUE).orElseThrow();
        IssueRecord resumed = blocked.withStatus(IssueStatus.IN_PROGRESS).withoutWaitReason();
        gitHub.editComment(blocked.statusCommentId().orElseThrow(), StatusComment.render(resumed, 2));
    }

    @Test
    void aBlockDuringTheEnterClosesTheNewSession() throws InterruptedException {
        CountDownLatch phaseStarted = new CountDownLatch(1);
        CountDownLatch phaseMayEnd = new CountDownLatch(1);
        Worker worker = createWorker(new FakeAgentRunner().then(changeFileAfter(phaseStarted, phaseMayEnd)), 2);
        worker.pollOnce();
        awaitQuietly(phaseStarted);
        gitHub.addLabelBy(ISSUE, ENTER_LABEL, "bbossola");
        worker.pollOnce();
        liveSessions.whenOpening(() -> {
            gitHub.addLabelBy(ISSUE, BLOCK_LABEL, "bbossola");
            worker.pollOnce();
        });

        phaseMayEnd.countDown();
        waitForTheLoops();

        IssueRecord record = readRecord(ISSUE).orElseThrow();
        assertThat(liveSessions.openCalls()).hasSize(1);
        assertThat(liveSessions.closeCalls()).hasSize(2);
        assertThat(liveSessions.isOpen(ISSUE)).isFalse();
        assertThat(record.status()).isEqualTo(IssueStatus.BLOCKED);
        assertThat(record.waitReason()).contains(WaitReason.BLOCKED_BY_OWNER);
        assertThat(record.live()).isEmpty();
    }

    @Test
    void aBlockedEnterRequestDoesNotOpenALaterSession() throws InterruptedException {
        FakeAgentRunner agent = new FakeAgentRunner().thenWaitForStop().thatFails().thenChangeFile();
        Worker worker = createWorker(agent, 2);
        worker.pollOnce();
        waitUntil(() -> agent.waitingPhaseCount() == 1);
        gitHub.addLabelBy(ISSUE, ENTER_LABEL, "bbossola");
        worker.pollOnce();
        gitHub.addLabelBy(ISSUE, BLOCK_LABEL, "bbossola");
        worker.pollOnce();
        waitUntil(() -> worker.issuesInWork().isEmpty()
                && readRecord(ISSUE).orElseThrow().status() == IssueStatus.BLOCKED);
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
        gitHub.addLabelBy(ISSUE, ENTER_LABEL, "bbossola");
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
        gitHub.addLabelBy(ISSUE, ENTER_LABEL, "bbossola");
        Worker worker = createWorker(new FakeAgentRunner(), 2);
        gitHub.failTheNextCommentEdit();

        worker.pollOnce();
        assertThat(gitHub.labelsOf(ISSUE)).contains(ENTER_LABEL);
        assertThat(liveSessions.isOpen(ISSUE)).isFalse();
        worker.pollOnce();

        assertThat(liveSessions.openCalls()).hasSize(2);
        assertThat(gitHub.labelsOf(ISSUE)).doesNotContain(ENTER_LABEL);
        assertThat(readRecord(ISSUE).orElseThrow().waitReason()).contains(WaitReason.LIVE_CONVERSATION);
    }

    @Test
    void aFailedLabelRemovalAfterTheEnterRemovesTheLabelAtTheNextPoll() throws InterruptedException {
        runToTheMergeApproval();
        gitHub.addLabelBy(ISSUE, ENTER_LABEL, "bbossola");
        Worker worker = createWorker(new FakeAgentRunner(), 2);
        gitHub.failTheNextRemoveLabel();

        worker.pollOnce();
        assertThat(gitHub.labelsOf(ISSUE)).contains(ENTER_LABEL);
        worker.pollOnce();

        assertThat(liveSessions.openCalls()).hasSize(1);
        assertThat(liveSessions.isOpen(ISSUE)).isTrue();
        assertThat(gitHub.labelsOf(ISSUE)).doesNotContain(ENTER_LABEL);
        assertThat(readRecord(ISSUE).orElseThrow().waitReason()).contains(WaitReason.LIVE_CONVERSATION);
    }

    @Test
    void aBlockedLiveIssueWithAContinueRequestContinues() throws InterruptedException {
        enterTheWaitingIssue();
        liveSessions.failTheNextClose();
        liveSessions.requestContinue(ISSUE);
        createWorker(new FakeAgentRunner(), 2).pollOnce();
        waitForTheLoops();
        IssueRecord blocked = readRecord(ISSUE).orElseThrow();
        assertThat(blocked.status()).isEqualTo(IssueStatus.BLOCKED);
        assertThat(blocked.live()).isPresent();

        createWorker(new FakeAgentRunner(), 2).pollOnce();
        waitForTheLoops();

        IssueRecord record = readRecord(ISSUE).orElseThrow();
        assertThat(record.live()).isEmpty();
        assertThat(record.waitReason()).contains(WaitReason.MERGE_NEEDS_APPROVAL);
        assertThat(liveSessions.isContinueRequested(ISSUE)).isFalse();
    }

    /** Another thread stops the worker, as the shutdown hook does. The step goes on after the stop. */
    private static void stopTheWorkerDuringTheStep(Worker worker) {
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
        liveSessions.whenOpening(() -> stopTheWorkerDuringTheStep(worker));

        worker.pollOnce();
        waitForTheLoops();

        IssueRecord record = readRecord(ISSUE).orElseThrow();
        assertThat(worker.isStopped()).isTrue();
        assertThat(liveSessions.isOpen(ISSUE)).isTrue();
        assertThat(readLiveLink()).contains("https://claude.ai/code/session_test" + ISSUE);
        assertThat(record.waitReason()).contains(WaitReason.LIVE_CONVERSATION);
    }

    @Test
    void aStopDuringTheContinueKeepsTheRecordAfterTheContinue() throws InterruptedException {
        Worker worker = enterTheWaitingIssue();
        liveSessions.requestContinue(ISSUE);
        liveSessions.whenClosing(() -> stopTheWorkerDuringTheStep(worker));

        worker.pollOnce();
        waitForTheLoops();

        IssueRecord record = readRecord(ISSUE).orElseThrow();
        assertThat(worker.isStopped()).isTrue();
        assertThat(liveSessions.isOpen(ISSUE)).isFalse();
        assertThat(record.live()).isEmpty();
        assertThat(record.waitReason()).contains(WaitReason.MERGE_NEEDS_APPROVAL);
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
}
