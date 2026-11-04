package com.meterian.factory.worker;

import static com.meterian.factory.testing.TemporaryRepository.createBare;
import static com.meterian.factory.testing.TemporaryRepository.createClone;
import static com.meterian.factory.testing.TemporaryRepository.run;
import static org.assertj.core.api.Assertions.assertThat;

import com.meterian.factory.board.BoardState;
import com.meterian.factory.board.PickUpSwitch;
import com.meterian.factory.build.BuildRunner;
import com.meterian.factory.configuration.ClaudeSettings;
import com.meterian.factory.configuration.FactoryConfiguration;
import com.meterian.factory.configuration.IsolationMode;
import com.meterian.factory.configuration.Limits;
import com.meterian.factory.configuration.TargetRepository;
import com.meterian.factory.git.Gatekeeper;
import com.meterian.factory.git.Git;
import com.meterian.factory.git.ProcessGit;
import com.meterian.factory.github.HttpGitHubClient;
import com.meterian.factory.merge.Merger;
import com.meterian.factory.prompt.PromptLibrary;
import com.meterian.factory.state.IssueRecord;
import com.meterian.factory.state.Phase;
import com.meterian.factory.state.StatusComment;
import com.meterian.factory.testing.FakeAgentRunner;
import com.meterian.factory.testing.FakeGitHubClient;
import com.meterian.factory.testing.FakeGitHubServer;
import com.meterian.factory.testing.FakeLiveSessions;
import com.meterian.factory.testing.MutableClock;
import com.meterian.factory.state.IssueStatus;
import com.meterian.factory.usage.ContextTracker;
import com.meterian.factory.usage.UsageTracker;
import com.meterian.factory.workflow.IssueWorkflow;
import com.meterian.factory.workflow.PageRequests;
import com.meterian.factory.workflow.RequestReader;
import com.meterian.factory.workflow.WorkspaceLayout;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.SimpleFormatter;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Counts the REST requests of the real HttpGitHubClient against a local fake GitHub, poll by poll. */
class GitHubRequestBudgetTest {

    private static final int ISSUE = 7;
    private static final String WORKER_LOGIN = "factory-bot";
    private static final String TOKEN = "test-token-value";
    private static final Instant NOW = Instant.parse("2026-11-01T10:00:00Z");
    private static final Instant RESET = Instant.parse("2026-11-01T10:20:00Z");
    private static final String RATE_LIMIT_LINE =
            "The GitHub rate limit is reached. The factory waits until 2026-11-01T10:20:00Z.";
    private static final int MEASURED_POLLS = 5;
    private static final String ISSUE_LIST_REQUEST = "GET /repos/{o}/{r}/issues";
    private static final String NOT_MODIFIED = " (304)";
    /** The issue list has the labels, so an issue in work without a request label needs no other read. */
    private static final int MAXIMUM_REQUESTS_OF_AN_IDLE_POLL = 1;
    /** The issue list, one read of the events for all request labels, and one read of the comments. */
    private static final int MAXIMUM_REQUESTS_OF_A_POLL_WITH_REQUEST_LABELS = 3;
    /** One read of the events gives the actors of all request labels of the issue. */
    private static final int MAXIMUM_EVENT_READS_OF_ONE_ISSUE = 1;
    private static final String EVENTS_REQUEST = "GET /repos/{o}/{r}/issues/{n}/events";
    private static final Duration TIME_BOUND = Duration.ofSeconds(30);
    /** A static reference: the logging framework keeps only a weak reference to a logger. */
    private static final Logger WORKER_LOGGER = Logger.getLogger(Worker.class.getName());

    @TempDir
    Path temporaryDirectory;

    private final Git git = new ProcessGit();
    private final FakeGitHubClient fakeGitHub = new FakeGitHubClient();
    private final FakeLiveSessions liveSessions = new FakeLiveSessions();
    private final MutableClock clock = new MutableClock(NOW);
    private final List<String> workerLog = new CopyOnWriteArrayList<>();
    private final Handler workerLogHandler = new Handler() {
        @Override
        public void publish(LogRecord logRecord) {
            workerLog.add(new SimpleFormatter().formatMessage(logRecord));
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    };
    private FakeGitHubServer server;
    private HttpGitHubClient gitHub;
    private Path clone;
    private Worker worker;

    @BeforeAll
    static void silenceTheWorkerLog() {
        WORKER_LOGGER.setLevel(Level.OFF);
    }

    @BeforeEach
    void createRepositoriesAndGitHub() {
        Path origin = createBare(temporaryDirectory.resolve("origin.git"));
        clone = createClone(origin, temporaryDirectory.resolve("clone"));
        Path githubRepository = createBare(temporaryDirectory.resolve("github.git"));
        Path gatekeeper = temporaryDirectory.resolve("gatekeeper.git");
        String targetUrl = createTarget().sshUrl();
        Gatekeeper.ensure(gatekeeper, targetUrl);
        // The gatekeeper forwards to the local "github" repository, not to the network.
        run(gatekeeper, "config", "url." + githubRepository + ".insteadOf", targetUrl);
        fakeGitHub.setAuthenticatedLogin(WORKER_LOGIN);
        fakeGitHub.readPullRequestHeadsFrom(githubRepository);
        server = new FakeGitHubServer(fakeGitHub, "MeterianHQ", "sample");
        gitHub = new HttpGitHubClient(server.apiBase(), TOKEN, createTarget(), HttpClient.newHttpClient(), clock);
        fakeGitHub.addIssue(ISSUE, "Change the greeting", "The greeting must change.");
        fakeGitHub.addLabelBy(ISSUE, "factory", "bbossola");
    }

    @BeforeEach
    void recordTheWorkerLog() {
        WORKER_LOGGER.addHandler(workerLogHandler);
        WORKER_LOGGER.setLevel(Level.ALL);
    }

    @AfterEach
    void stopTheWorkerAndTheServer() {
        if (worker != null) {
            worker.shutDown(TIME_BOUND);
        }
        server.close();
        WORKER_LOGGER.removeHandler(workerLogHandler);
        WORKER_LOGGER.setLevel(Level.OFF);
    }

    private TargetRepository createTarget() {
        return new TargetRepository("MeterianHQ", "sample", clone, "true", Map.of());
    }

    private Worker createWorker(FakeAgentRunner agent) {
        BoardState board = new BoardState(clock);
        FactoryConfiguration configuration = new FactoryConfiguration(createTarget(), "main",
                new Limits(1, 2, Duration.ofSeconds(60), 0.90),
                List.of("bbossola"), IsolationMode.SAME_USER, ClaudeSettings.createDefaults(),
                temporaryDirectory.resolve("work"), temporaryDirectory.resolve("gatekeeper.git"), Optional.empty(),
                temporaryDirectory.resolve("live"), Optional.empty(),
                FactoryConfiguration.DEFAULT_TERMINAL_COMMAND);
        BuildRunner buildRunner = new BuildRunner();
        RequestReader requests = new RequestReader(gitHub, configuration.allowedAccounts());
        Merger merger = new Merger(git, gitHub, buildRunner, configuration.target(), "main",
                configuration.gatekeeperDirectory(), Duration.ZERO);
        WorkspaceLayout layout = new WorkspaceLayout(configuration.workDirectory());
        IssueWorkflow workflow = new IssueWorkflow(configuration, gitHub, git, agent,
                new PromptLibrary(Optional.empty(), "main"), buildRunner, merger,
                new UsageTracker(0.90, Clock.systemUTC()), requests, layout, WORKER_LOGIN, liveSessions,
                board, new ContextTracker(), new PageRequests(Clock.systemUTC()));
        ExecutorService executor = Executors.newFixedThreadPool(1);
        PhaseStopper phaseStopper = new PhaseStopper(agent, buildRunner, layout, liveSessions);
        return new Worker(configuration, gitHub, workflow, phaseStopper, requests, WORKER_LOGIN, executor,
                liveSessions, clock, board, new PickUpSwitch(temporaryDirectory.resolve("pick-up-paused")));
    }

    private Optional<IssueRecord> readRecord() {
        return StatusComment.findRecord(fakeGitHub.commentsOf(ISSUE), WORKER_LOGIN);
    }

    private static void waitUntil(BooleanSupplier condition) throws InterruptedException {
        Instant deadline = Instant.now().plus(TIME_BOUND);
        while (!condition.getAsBoolean()) {
            assertThat(Instant.now()).isBefore(deadline);
            Thread.sleep(20);
        }
    }

    /** Runs the polls and gives the request counts of each poll. */
    private List<Map<String, Integer>> measurePolls(int polls) {
        List<Map<String, Integer>> countsByPoll = new ArrayList<>();
        for (int poll = 0; poll < polls; poll++) {
            server.resetRequestCounts();
            worker.pollOnce();
            countsByPoll.add(server.requestCounts());
        }
        return countsByPoll;
    }

    @Test
    void anIssueInWorkWithoutARequestLabelCostsOnlyTheIssueList() throws InterruptedException {
        FakeAgentRunner agent = new FakeAgentRunner().thenChangeFile().thenWaitForStop().thatFails();
        worker = createWorker(agent);
        worker.pollOnce();
        waitUntil(() -> agent.waitingPhaseCount() == 1);
        assertThat(readRecord().orElseThrow().phase()).isEqualTo(Phase.REVIEW);

        List<Map<String, Integer>> countsByPoll = measurePolls(MEASURED_POLLS);

        assertThat(countsByPoll).allSatisfy(counts -> {
            assertThat(counts.keySet()).isSubsetOf(ISSUE_LIST_REQUEST, ISSUE_LIST_REQUEST + NOT_MODIFIED);
            assertThat(sumCounted(counts)).isLessThanOrEqualTo(MAXIMUM_REQUESTS_OF_AN_IDLE_POLL);
        });
    }

    @Test
    void anIssueInWorkWithRequestLabelsFromAnotherAccountStaysInTheBudget() throws InterruptedException {
        FakeAgentRunner agent = new FakeAgentRunner().thenChangeFile().thenWaitForStop().thatFails();
        worker = createWorker(agent);
        worker.pollOnce();
        waitUntil(() -> agent.waitingPhaseCount() == 1);
        fakeGitHub.addLabelBy(ISSUE, EnterRequests.ENTER_LABEL, "mallory");
        fakeGitHub.addLabelBy(ISSUE, "factory:block", "mallory");
        worker.pollOnce();

        List<Map<String, Integer>> countsByPoll = measurePolls(MEASURED_POLLS);

        assertThat(countsByPoll).allSatisfy(counts -> {
            assertThat(sumCounted(counts)).isLessThanOrEqualTo(MAXIMUM_REQUESTS_OF_A_POLL_WITH_REQUEST_LABELS);
            assertThat(counts.getOrDefault(EVENTS_REQUEST, 0)).isLessThanOrEqualTo(MAXIMUM_EVENT_READS_OF_ONE_ISSUE);
        });
    }

    private static int sum(Map<String, Integer> counts) {
        return counts.values().stream().mapToInt(Integer::intValue).sum();
    }

    /** GitHub does not count a 304 answer against the rate limit. */
    private static int sumCounted(Map<String, Integer> counts) {
        return counts.entrySet().stream().filter(entry -> !entry.getKey().endsWith(NOT_MODIFIED))
                .mapToInt(Map.Entry::getValue).sum();
    }

    private List<String> rateLimitLines() {
        return workerLog.stream().filter(line -> line.startsWith("The GitHub rate limit")).toList();
    }

    @Test
    void sendsNoRequestUntilTheResetWhenFewRequestsRemain() throws InterruptedException {
        FakeAgentRunner agent = new FakeAgentRunner().thenChangeFile().thenWaitForStop().thatFails();
        worker = createWorker(agent);
        worker.pollOnce();
        waitUntil(() -> agent.waitingPhaseCount() == 1);
        server.sendRateLimitHeaders(50, RESET);
        worker.pollOnce();
        int requestsBeforeTheWait = server.receivedRequestCount();

        clock.set(RESET.minusSeconds(1));
        worker.pollOnce();
        worker.pollOnce();

        assertThat(server.receivedRequestCount()).isEqualTo(requestsBeforeTheWait);
        assertThat(rateLimitLines()).containsExactly(RATE_LIMIT_LINE);

        server.answerNormally();
        clock.set(RESET);
        worker.pollOnce();

        assertThat(server.receivedRequestCount()).isGreaterThan(requestsBeforeTheWait);
        assertThat(rateLimitLines()).containsExactly(RATE_LIMIT_LINE);
    }

    @Test
    void anIssueThreadStillPublishesItsRecordWhenFewRequestsRemain() throws InterruptedException {
        FakeAgentRunner agent = new FakeAgentRunner()
                .thenChangeFileAnd(() -> server.sendRateLimitHeaders(50, RESET))
                .thenWaitForStop().thatFails();
        worker = createWorker(agent);
        worker.pollOnce();
        waitUntil(() -> agent.waitingPhaseCount() == 1);

        assertThat(readRecord().orElseThrow().phase()).isEqualTo(Phase.REVIEW);
        int requestsBeforeThePolls = server.receivedRequestCount();
        clock.set(RESET.minusSeconds(1));
        worker.pollOnce();
        worker.pollOnce();

        assertThat(server.receivedRequestCount()).isEqualTo(requestsBeforeThePolls);
        assertThat(rateLimitLines()).containsExactly(RATE_LIMIT_LINE);
    }

    /** The commit of the first run is the change of the phase, so a resumed run without a change does not block. */
    @Test
    void aStepThatMeetsTheRateLimitRunsAgainAfterTheResetAndDoesNotBlock() throws InterruptedException {
        FakeAgentRunner agent = new FakeAgentRunner()
                .thenChangeFileAnd(() -> server.refuseWithTheRateLimit(RESET))
                .thenChangeNothing().thenWaitForStop().thatFails();
        worker = createWorker(agent);
        worker.pollOnce();
        waitUntil(() -> worker.issuesInWork().isEmpty());

        assertThat(rateLimitLines()).containsExactly(RATE_LIMIT_LINE);
        assertThat(readRecord().orElseThrow().status()).isNotEqualTo(IssueStatus.BLOCKED);
        assertThat(readRecord().orElseThrow().fixLoops()).isZero();
        int requestsInTheWait = server.receivedRequestCount();
        worker.pollOnce();
        assertThat(server.receivedRequestCount()).isEqualTo(requestsInTheWait);

        server.answerNormally();
        clock.set(RESET);
        worker.pollOnce();
        waitUntil(() -> agent.waitingPhaseCount() == 1);

        assertThat(readRecord().orElseThrow().phase()).isEqualTo(Phase.REVIEW);
        assertThat(readRecord().orElseThrow().status()).isNotEqualTo(IssueStatus.BLOCKED);
        assertThat(rateLimitLines()).containsExactly(RATE_LIMIT_LINE);
        assertThat(workerLog).noneMatch(line -> line.contains(TOKEN));
    }

    @Test
    void logsTheRequestCountsOfEachPollAndStartsThemAgainAtZero() throws InterruptedException {
        FakeAgentRunner agent = new FakeAgentRunner().thenChangeFile().thenWaitForStop().thatFails();
        worker = createWorker(agent);
        worker.pollOnce();
        waitUntil(() -> agent.waitingPhaseCount() == 1);
        workerLog.clear();
        worker.pollOnce();
        assertThat(pollLines()).as("the requests of the issue thread count in the next poll line")
                .singleElement().asString().contains("GET /repos/{o}/{r}/pulls=1");
        workerLog.clear();

        server.resetRequestCounts();
        worker.pollOnce();

        Map<String, Integer> counts = server.requestCounts();
        String expectedCounts = counts.entrySet().stream()
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .reduce((first, second) -> first + ", " + second).orElseThrow();
        assertThat(pollLines()).containsExactly(
                "The poll sent " + sum(counts) + " GitHub requests: " + expectedCounts + ".");
        assertThat(gitHub.requestCounts()).isEmpty();
    }

    @Test
    void putsTheLargestCountFirstInTheLogLine() {
        FakeAgentRunner agent = new FakeAgentRunner().thenChangeFile().thenWaitForStop().thatFails();
        worker = createWorker(agent);

        worker.pollOnce();

        assertThat(pollLines()).hasSize(1).first().asString()
                .matches("The poll sent \\d+ GitHub requests: POST /repos/\\{o}/\\{r}/labels=11, .*");
    }

    private List<String> pollLines() {
        return workerLog.stream().filter(line -> line.startsWith("The poll sent")).toList();
    }
}
