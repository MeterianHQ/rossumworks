package com.meterian.factory.worker;

import static com.meterian.factory.testing.TemporaryRepository.createBare;
import static com.meterian.factory.testing.TemporaryRepository.createClone;
import static com.meterian.factory.testing.TemporaryRepository.run;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import com.meterian.factory.agent.PhaseRequest;
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
import com.meterian.factory.prompt.PromptLibrary;
import com.meterian.factory.state.IssueRecord;
import com.meterian.factory.state.IssueStatus;
import com.meterian.factory.state.Phase;
import com.meterian.factory.state.WaitReason;
import com.meterian.factory.state.StatusComment;
import com.meterian.factory.testing.FakeAgentRunner;
import com.meterian.factory.testing.FakeGitHubClient;
import com.meterian.factory.usage.UsageTracker;
import com.meterian.factory.workflow.IssueText;
import com.meterian.factory.workflow.IssueWorkflow;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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
    private static final String WORKER_LOGIN = "factory-bot";
    private static final String FACTORY_LABEL = "factory";
    private static final String BLOCK_LABEL = "factory:block";
    private static final String APPROVE_LABEL = "factory:approve";
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

    private final Git git = new ProcessGit();
    private final FakeGitHubClient gitHub = new FakeGitHubClient();
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
                temporaryDirectory.resolve("live"));
    }

    private Worker createWorker(FakeAgentRunner agent, int parallelIssues) {
        return createWorker(agent, createConfiguration(parallelIssues, Duration.ofSeconds(60)));
    }

    private Worker createWorker(FakeAgentRunner agent, FactoryConfiguration configuration) {
        BuildRunner buildRunner = new BuildRunner();
        RequestReader requests = new RequestReader(gitHub, configuration.allowedAccounts());
        Merger merger = new Merger(git, gitHub, buildRunner, configuration.target(), "main",
                configuration.gatekeeperDirectory(), Duration.ZERO);
        IssueWorkflow workflow = new IssueWorkflow(configuration, gitHub, git, agent,
                new PromptLibrary(Optional.empty(), "main"), buildRunner, merger,
                new UsageTracker(0.90, Clock.systemUTC()), requests,
                new WorkspaceLayout(configuration.workDirectory()), WORKER_LOGIN);
        ExecutorService executor = Executors.newFixedThreadPool(Math.max(1, configuration.limits().parallelIssues()));
        executors.add(executor);
        PhaseStopper phaseStopper = new PhaseStopper(agent, buildRunner,
                new WorkspaceLayout(configuration.workDirectory()));
        return new Worker(configuration, gitHub, workflow, phaseStopper, requests, WORKER_LOGIN, executor);
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
        assertThat(secondAgent.requests().getFirst().prompt()).contains(".factory/review-");
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
        assertThat(secondImplement.prompt()).doesNotContain(".factory/review-");
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
}
