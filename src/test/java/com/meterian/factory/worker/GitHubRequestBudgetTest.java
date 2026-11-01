package com.meterian.factory.worker;

import static com.meterian.factory.testing.TemporaryRepository.createBare;
import static com.meterian.factory.testing.TemporaryRepository.createClone;
import static com.meterian.factory.testing.TemporaryRepository.run;
import static org.assertj.core.api.Assertions.assertThat;

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
import com.meterian.factory.usage.UsageTracker;
import com.meterian.factory.workflow.IssueWorkflow;
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
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;
import java.util.logging.Level;
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
    private static final int MEASURED_POLLS = 5;
    private static final String ISSUE_LIST_REQUEST = "GET /repos/{o}/{r}/issues";
    /** The issue list has the labels, so an issue in work without a request label needs no other read. */
    private static final int MAXIMUM_REQUESTS_OF_AN_IDLE_POLL = 1;
    private static final Duration TIME_BOUND = Duration.ofSeconds(30);
    /** A static reference: the logging framework keeps only a weak reference to a logger. */
    private static final Logger WORKER_LOGGER = Logger.getLogger(Worker.class.getName());

    @TempDir
    Path temporaryDirectory;

    private final Git git = new ProcessGit();
    private final FakeGitHubClient fakeGitHub = new FakeGitHubClient();
    private final FakeLiveSessions liveSessions = new FakeLiveSessions();
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
        gitHub = new HttpGitHubClient(server.apiBase(), "test-token", createTarget(), HttpClient.newHttpClient());
        fakeGitHub.addIssue(ISSUE, "Change the greeting", "The greeting must change.");
        fakeGitHub.addLabelBy(ISSUE, "factory", "bbossola");
    }

    @AfterEach
    void stopTheWorkerAndTheServer() {
        if (worker != null) {
            worker.shutDown(TIME_BOUND);
        }
        server.close();
    }

    private TargetRepository createTarget() {
        return new TargetRepository("MeterianHQ", "sample", clone, "true", Map.of());
    }

    private Worker createWorker(FakeAgentRunner agent) {
        FactoryConfiguration configuration = new FactoryConfiguration(createTarget(), "main",
                new Limits(1, 2, Duration.ofSeconds(60), 0.90),
                List.of("bbossola"), IsolationMode.SAME_USER, ClaudeSettings.createDefaults(),
                temporaryDirectory.resolve("work"), temporaryDirectory.resolve("gatekeeper.git"), Optional.empty(),
                temporaryDirectory.resolve("live"));
        BuildRunner buildRunner = new BuildRunner();
        RequestReader requests = new RequestReader(gitHub, configuration.allowedAccounts());
        Merger merger = new Merger(git, gitHub, buildRunner, configuration.target(), "main",
                configuration.gatekeeperDirectory(), Duration.ZERO);
        WorkspaceLayout layout = new WorkspaceLayout(configuration.workDirectory());
        IssueWorkflow workflow = new IssueWorkflow(configuration, gitHub, git, agent,
                new PromptLibrary(Optional.empty(), "main"), buildRunner, merger,
                new UsageTracker(0.90, Clock.systemUTC()), requests, layout, WORKER_LOGIN, liveSessions);
        ExecutorService executor = Executors.newFixedThreadPool(1);
        PhaseStopper phaseStopper = new PhaseStopper(agent, buildRunner, layout, liveSessions);
        return new Worker(configuration, gitHub, workflow, phaseStopper, requests, WORKER_LOGIN, executor,
                liveSessions);
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
            gitHub.resetRequestCounts();
            worker.pollOnce();
            countsByPoll.add(gitHub.requestCounts());
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
            assertThat(counts).containsOnlyKeys(ISSUE_LIST_REQUEST);
            assertThat(sum(counts)).isLessThanOrEqualTo(MAXIMUM_REQUESTS_OF_AN_IDLE_POLL);
        });
    }

    private static int sum(Map<String, Integer> counts) {
        return counts.values().stream().mapToInt(Integer::intValue).sum();
    }
}
