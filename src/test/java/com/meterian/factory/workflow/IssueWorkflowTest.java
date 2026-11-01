package com.meterian.factory.workflow;

import static com.meterian.factory.testing.TemporaryRepository.createBare;
import static com.meterian.factory.testing.TemporaryRepository.createClone;
import static com.meterian.factory.testing.TemporaryRepository.run;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.meterian.factory.agent.ApiKeyDetectedException;
import com.meterian.factory.agent.PhaseRequest;
import com.meterian.factory.agent.UsageWindow;
import com.meterian.factory.build.BuildRunner;
import com.meterian.factory.configuration.ClaudeSettings;
import com.meterian.factory.configuration.FactoryConfiguration;
import com.meterian.factory.configuration.IsolationMode;
import com.meterian.factory.configuration.Limits;
import com.meterian.factory.configuration.ProjectReference;
import com.meterian.factory.configuration.TargetRepository;
import com.meterian.factory.git.Gatekeeper;
import com.meterian.factory.git.Git;
import com.meterian.factory.git.ProcessGit;
import com.meterian.factory.github.IssueComment;
import com.meterian.factory.merge.Merger;
import com.meterian.factory.prompt.PromptLibrary;
import com.meterian.factory.state.IssueRecord;
import com.meterian.factory.state.IssueStatus;
import com.meterian.factory.state.WaitReason;
import com.meterian.factory.testing.FakeAgentRunner;
import com.meterian.factory.testing.FakeGitHubClient;
import com.meterian.factory.usage.UsageTracker;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class IssueWorkflowTest {

    private static final int ISSUE = 7;
    private static final String WORKER_LOGIN = "factory-bot";
    private static final String APPROVE_LABEL = "factory:approve";
    private static final String MALLORY_NOTE =
            "The label factory:approve from mallory is ignored: the account is not in accounts.allowed.";

    @TempDir
    Path temporaryDirectory;

    private final Git git = new ProcessGit();
    private final FakeGitHubClient gitHub = new FakeGitHubClient();
    private final FakeAgentRunner agent = new FakeAgentRunner();
    private final UsageTracker usage = new UsageTracker(0.90, Clock.systemUTC());
    private Path clone;
    private Path githubRepository;

    @BeforeEach
    void createRepositoriesAndIssue() {
        Path origin = createBare(temporaryDirectory.resolve("origin.git"));
        clone = createClone(origin, temporaryDirectory.resolve("clone"));
        githubRepository = createBare(temporaryDirectory.resolve("github.git"));
        Path gatekeeper = temporaryDirectory.resolve("gatekeeper.git");
        String targetUrl = createTarget("true").sshUrl();
        Gatekeeper.ensure(gatekeeper, targetUrl);
        // The gatekeeper forwards to the local "github" repository, not to the network.
        run(gatekeeper, "config", "url." + githubRepository + ".insteadOf", targetUrl);
        gitHub.setAuthenticatedLogin(WORKER_LOGIN);
        gitHub.addIssue(ISSUE, "Change the greeting", "The greeting must change.");
        gitHub.addLabelBy(ISSUE, "factory", "bbossola");
    }

    private TargetRepository createTarget(String buildCommand) {
        return new TargetRepository("MeterianHQ", "sample", clone, buildCommand, Map.of());
    }

    private IssueWorkflow createWorkflow(String buildCommand) {
        TargetRepository target = createTarget(buildCommand);
        FactoryConfiguration configuration = new FactoryConfiguration(target, "main",
                new ProjectReference("MeterianHQ", 2), new Limits(2, 2, Duration.ofSeconds(60), 0.90),
                List.of("bbossola"), IsolationMode.SAME_USER, ClaudeSettings.defaults(),
                temporaryDirectory.resolve("work"), temporaryDirectory.resolve("gatekeeper.git"), Optional.empty());
        BuildRunner buildRunner = new BuildRunner();
        Merger merger = new Merger(git, gitHub, buildRunner, target, "main", Duration.ZERO);
        return new IssueWorkflow(configuration, gitHub, git, agent, new PromptLibrary(Optional.empty(), "main"),
                buildRunner, merger, usage, new RequestReader(gitHub, List.of("bbossola")),
                new WorkspaceLayout(temporaryDirectory.resolve("work")), WORKER_LOGIN);
    }

    private IssueRecord advanceUntilWaiting(IssueWorkflow workflow, IssueRecord start) throws ApiKeyDetectedException {
        IssueRecord record = start;
        int steps = 0;
        do {
            record = workflow.advance(record);
            steps++;
        } while (!workflow.isWaiting(record) && steps < 30);
        return record;
    }

    private Path worktree() {
        return temporaryDirectory.resolve("work").resolve("issue-" + ISSUE);
    }

    private List<IssueComment> statusComments() {
        return gitHub.commentsOf(ISSUE).stream()
                .filter(comment -> comment.body().contains("<!-- factory-record"))
                .toList();
    }

    private String visiblePartOfStatusComment() {
        String body = statusComments().getFirst().body();
        return body.substring(0, body.indexOf("<!-- factory-record"));
    }

    private IssueRecord waitForApprovalOfAMediumRisk(IssueWorkflow workflow) throws ApiKeyDetectedException {
        agent.thenChangeFile().thenApproveAllReviews().thenAssessRisk("medium", true);
        return advanceUntilWaiting(workflow, IssueRecord.queued(ISSUE));
    }

    @Test
    void fixesALowRiskIssueToDone() throws ApiKeyDetectedException {
        agent.thenChangeFile().thenApproveAllReviews().thenAssessRisk("low", true);

        IssueRecord record = advanceUntilWaiting(createWorkflow("true"), IssueRecord.queued(ISSUE));

        assertThat(record.status()).isEqualTo(IssueStatus.DONE);
        assertThat(gitHub.squashMergeCalls()).hasSize(1);
        assertThat(gitHub.labelsOf(ISSUE)).doesNotContain("factory", APPROVE_LABEL);
        assertThat(gitHub.commentsOf(ISSUE)).hasSize(1);
        assertThat(statusComments()).hasSize(1);
        assertThat(gitHub.statusOf(ISSUE)).contains(IssueStatus.DONE);
        assertThat(run(githubRepository, "branch", "--list", "factory/issue-7")).contains("factory/issue-7");
        assertThat(gitHub.pullRequests()).hasSize(1);
        assertThat(worktree()).doesNotExist();
    }

    @Test
    void waitsForTheOwnerOnAMediumRisk() throws ApiKeyDetectedException {
        IssueRecord record = waitForApprovalOfAMediumRisk(createWorkflow("true"));

        assertThat(record.status()).isEqualTo(IssueStatus.WAITS_FOR_YOU);
        assertThat(record.waitReason()).contains(WaitReason.MERGE_NEEDS_APPROVAL);
        assertThat(gitHub.squashMergeCalls()).isEmpty();
        assertThat(gitHub.statusOf(ISSUE)).contains(IssueStatus.WAITS_FOR_YOU);
    }

    @Test
    void mergesAfterAnApprovalFromAnAllowedAccount() throws ApiKeyDetectedException {
        IssueWorkflow workflow = createWorkflow("true");
        IssueRecord waiting = waitForApprovalOfAMediumRisk(workflow);
        gitHub.addLabelBy(ISSUE, APPROVE_LABEL, "bbossola");

        IssueRecord record = advanceUntilWaiting(workflow, waiting);

        assertThat(record.status()).isEqualTo(IssueStatus.DONE);
        assertThat(gitHub.squashMergeCalls()).hasSize(1);
        assertThat(gitHub.labelsOf(ISSUE)).doesNotContain("factory", APPROVE_LABEL);
    }

    @Test
    void ignoresAnApprovalFromAnotherAccount() throws ApiKeyDetectedException {
        IssueWorkflow workflow = createWorkflow("true");
        IssueRecord record = waitForApprovalOfAMediumRisk(workflow);
        gitHub.addLabelBy(ISSUE, APPROVE_LABEL, "mallory");

        for (int step = 0; step < 3; step++) {
            record = workflow.advance(record);
        }

        assertThat(record.status()).isEqualTo(IssueStatus.WAITS_FOR_YOU);
        assertThat(record.notes()).contains(MALLORY_NOTE);
        assertThat(visiblePartOfStatusComment().split(MALLORY_NOTE, -1)).hasSize(2);
        assertThat(gitHub.squashMergeCalls()).isEmpty();
    }

    @Test
    void runsAFixLoopAfterARejectingReview() throws ApiKeyDetectedException {
        agent.thenChangeFile()
                .thenApproveReview().thenRejectReview("Missing input check").thenApproveReview().thenApproveReview()
                .thenChangeFile().thenApproveAllReviews().thenAssessRisk("low", true);

        IssueRecord record = advanceUntilWaiting(createWorkflow("true"), IssueRecord.queued(ISSUE));

        List<PhaseRequest> requests = agent.requests();
        PhaseRequest firstImplement = requests.get(0);
        PhaseRequest secondImplement = requests.get(5);
        assertThat(record.status()).isEqualTo(IssueStatus.DONE);
        assertThat(record.fixLoops()).isEqualTo(1);
        assertThat(secondImplement.prompt()).contains("Missing input check");
        assertThat(secondImplement.sessionId()).isEqualTo(firstImplement.sessionId());
        assertThat(secondImplement.newSession()).isFalse();
        assertThat(firstImplement.newSession()).isTrue();
    }

    @Test
    void blocksAfterTheFixLoopLimit() throws ApiKeyDetectedException {
        agent.thenChangeFile().thenChangeFile().thenChangeFile();

        IssueRecord record = advanceUntilWaiting(createWorkflow("false"), IssueRecord.queued(ISSUE));

        assertThat(record.status()).isEqualTo(IssueStatus.BLOCKED);
        assertThat(record.waitReason()).contains(WaitReason.FIX_LOOP_LIMIT);
        assertThat(agent.requests()).hasSize(3);
        assertThat(gitHub.statusOf(ISSUE)).contains(IssueStatus.BLOCKED);
    }

    @Test
    void waitsForTheOwnerOnAQuestion() throws ApiKeyDetectedException {
        agent.thenAskQuestion("Which greeting do you want?");

        IssueRecord record = advanceUntilWaiting(createWorkflow("true"), IssueRecord.queued(ISSUE));

        assertThat(record.status()).isEqualTo(IssueStatus.WAITS_FOR_YOU);
        assertThat(record.waitReason()).contains(WaitReason.QUESTION);
        assertThat(gitHub.commentsOf(ISSUE)).anySatisfy(comment -> assertThat(comment.body())
                .contains("Which greeting do you want?").contains("@bbossola"));
        assertThat(run(worktree(), "log", "--all", "--oneline", "--", ".factory")).isEmpty();
        assertThat(run(worktree(), "rev-parse", "HEAD")).isEqualTo(run(worktree(), "rev-parse", "origin/main"));
    }

    @Test
    void blocksWhenTheAgentChangesNothing() throws ApiKeyDetectedException {
        agent.thenChangeNothing();

        IssueRecord record = advanceUntilWaiting(createWorkflow("true"), IssueRecord.queued(ISSUE));

        assertThat(record.status()).isEqualTo(IssueStatus.BLOCKED);
        assertThat(record.waitReason()).contains(WaitReason.FAILURE);
        assertThat(record.notes()).contains("The agent made no change.");
    }

    @Test
    void reviewsRunInNewSessions() throws ApiKeyDetectedException {
        agent.thenChangeFile().thenApproveAllReviews().thenAssessRisk("low", true);

        advanceUntilWaiting(createWorkflow("true"), IssueRecord.queued(ISSUE));

        List<PhaseRequest> requests = agent.requests();
        UUID sessionInCharge = requests.get(0).sessionId();
        List<PhaseRequest> reviews = requests.subList(1, 5);
        assertThat(reviews).allSatisfy(review -> assertThat(review.newSession()).isTrue());
        assertThat(reviews.stream().map(PhaseRequest::sessionId).collect(Collectors.toSet()))
                .hasSize(4)
                .doesNotContain(sessionInCharge);
    }

    @Test
    void aReviewOfAnotherTreeIsAReject() throws ApiKeyDetectedException {
        agent.thenChangeFile()
                .thenApproveReviewOfTree("0000").thenApproveReview().thenApproveReview().thenApproveReview()
                .thenChangeFile().thenApproveAllReviews().thenAssessRisk("low", true);

        IssueRecord record = advanceUntilWaiting(createWorkflow("true"), IssueRecord.queued(ISSUE));

        assertThat(record.fixLoops()).isEqualTo(1);
        assertThat(agent.requests().get(5).newSession()).isFalse();
        assertThat(record.status()).isEqualTo(IssueStatus.DONE);
    }

    @Test
    void startsNoPhaseAboveTheUsageThreshold() throws ApiKeyDetectedException {
        Instant now = Instant.now();
        usage.record(List.of(new UsageWindow("five_hour", 0.95, now.plus(Duration.ofHours(1)), "allowed", now)));
        IssueWorkflow workflow = createWorkflow("true");
        IssueRecord prepared = workflow.advance(IssueRecord.queued(ISSUE));

        IssueRecord record = workflow.advance(prepared);

        assertThat(record).isEqualTo(prepared);
        assertThat(agent.requests()).isEmpty();
    }

    @Test
    void stopsOnAnApiKey() throws ApiKeyDetectedException {
        agent.thenDetectAnApiKey();
        IssueWorkflow workflow = createWorkflow("true");
        IssueRecord prepared = workflow.advance(IssueRecord.queued(ISSUE));

        assertThatThrownBy(() -> workflow.advance(prepared)).isInstanceOf(ApiKeyDetectedException.class);
    }
}
