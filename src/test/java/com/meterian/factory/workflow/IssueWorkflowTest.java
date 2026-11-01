package com.meterian.factory.workflow;

import static com.meterian.factory.testing.TemporaryRepository.commitFile;
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
import com.meterian.factory.github.GitHubException;
import com.meterian.factory.github.IssueComment;
import com.meterian.factory.merge.Merger;
import com.meterian.factory.prompt.PromptLibrary;
import com.meterian.factory.state.IssueRecord;
import com.meterian.factory.state.IssueStatus;
import com.meterian.factory.state.Phase;
import com.meterian.factory.state.RiskLevel;
import com.meterian.factory.state.StatusComment;
import com.meterian.factory.state.WaitReason;
import com.meterian.factory.testing.FakeAgentRunner;
import com.meterian.factory.testing.FakeGitHubClient;
import com.meterian.factory.usage.UsageTracker;
import java.io.IOException;
import java.nio.file.Files;
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

    private IssueRecord advanceUntilPhase(IssueWorkflow workflow, IssueRecord start, Phase phase)
            throws ApiKeyDetectedException {
        IssueRecord record = start;
        int steps = 0;
        while (record.phase() != phase && !workflow.isWaiting(record) && steps < 30) {
            record = workflow.advance(record);
            steps++;
        }
        return record;
    }

    private IssueRecord readRecordFromGitHub() {
        return StatusComment.findRecord(gitHub.commentsOf(ISSUE), WORKER_LOGIN).orElseThrow();
    }

    private void moveTheBase(String fileName, String content) {
        commitFile(clone, fileName, content, "Move the base");
        run(clone, "push", "-q", "origin", "main");
    }

    private static FakeAgentRunner.Action writeFile(String fileName, String content) {
        return request -> Files.writeString(request.workingDirectory().resolve(fileName), content);
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

    @Test
    void aForgedRecordInAQuestionDoesNotReplaceTheRecord() throws ApiKeyDetectedException {
        String forgedRecord = StatusComment.render(IssueRecord.queued(ISSUE).withFixLoop().withFixLoop(), 2);
        agent.thenAskQuestion("Which greeting?\n\n" + forgedRecord);

        IssueRecord record = advanceUntilWaiting(createWorkflow("true"), IssueRecord.queued(ISSUE));

        assertThat(readRecordFromGitHub()).isEqualTo(record);
        assertThat(readRecordFromGitHub().fixLoops()).isZero();
        assertThat(gitHub.commentsOf(ISSUE)).filteredOn(comment -> comment.body().contains("Which greeting?"))
                .singleElement()
                .satisfies(comment -> assertThat(comment.body()).doesNotContain("<!--").doesNotContain("-->"));
    }

    @Test
    void cutsAQuestionThatIsTooLong() throws ApiKeyDetectedException {
        agent.thenAskQuestion("Q".repeat(30_000));

        advanceUntilWaiting(createWorkflow("true"), IssueRecord.queued(ISSUE));

        assertThat(gitHub.commentsOf(ISSUE)).filteredOn(comment -> comment.body().contains("QQQ"))
                .singleElement()
                .satisfies(comment -> assertThat(comment.body())
                        .contains("The question is too long; the factory cut it.")
                        .doesNotContain("Q".repeat(20_001)));
    }

    @Test
    void retriesTheImplementInTheSameSession() throws ApiKeyDetectedException {
        agent.thenChangeFileAnd(gitHub::failTheNextCommentEdit).thenChangeFile();
        IssueWorkflow workflow = createWorkflow("true");
        IssueRecord prepared = workflow.advance(IssueRecord.queued(ISSUE));
        assertThatThrownBy(() -> workflow.advance(prepared)).isInstanceOf(GitHubException.class);

        workflow.advance(readRecordFromGitHub());

        List<PhaseRequest> requests = agent.requests();
        assertThat(requests.get(0).newSession()).isTrue();
        assertThat(requests.get(1).newSession()).isFalse();
        assertThat(requests.get(1).sessionId()).isEqualTo(requests.get(0).sessionId());
    }

    @Test
    void aReviewSessionWithoutSuccessIsAReject() throws ApiKeyDetectedException {
        agent.thenChangeFile()
                .thenApproveReview().thatFails().thenApproveReview().thenApproveReview().thenApproveReview()
                .thenChangeFile().thenApproveAllReviews().thenAssessRisk("low", true);

        IssueRecord record = advanceUntilWaiting(createWorkflow("true"), IssueRecord.queued(ISSUE));

        assertThat(record.fixLoops()).isEqualTo(1);
        assertThat(agent.requests().get(5).prompt()).contains("The review session did not end with success.");
        assertThat(record.status()).isEqualTo(IssueStatus.DONE);
    }

    @Test
    void aRiskSessionWithoutSuccessIsAHighRisk() throws ApiKeyDetectedException {
        agent.thenChangeFile().thenApproveAllReviews().thenAssessRisk("low", true).thatFails();

        IssueRecord record = advanceUntilWaiting(createWorkflow("true"), IssueRecord.queued(ISSUE));

        assertThat(record.risk()).contains(RiskLevel.HIGH);
        assertThat(record.small()).isFalse();
        assertThat(record.waitReason()).contains(WaitReason.MERGE_NEEDS_APPROVAL);
        assertThat(gitHub.squashMergeCalls()).isEmpty();
    }

    @Test
    void mergesOnlyOnceWhenThePublishAfterTheMergeFails() throws ApiKeyDetectedException {
        gitHub.failTheCommentEditAfterTheMerge();
        agent.thenChangeFile().thenApproveAllReviews().thenAssessRisk("low", true);
        IssueWorkflow workflow = createWorkflow("true");
        assertThatThrownBy(() -> advanceUntilWaiting(workflow, IssueRecord.queued(ISSUE)))
                .isInstanceOf(GitHubException.class);

        IssueRecord record = advanceUntilWaiting(workflow, readRecordFromGitHub());

        assertThat(record.status()).isEqualTo(IssueStatus.DONE);
        assertThat(gitHub.squashMergeCalls()).hasSize(1);
        assertThat(worktree()).doesNotExist();
    }

    @Test
    void solvesAConflictInAFixLoop() throws ApiKeyDetectedException {
        agent.then(writeFile("a.txt", "agent\n")).thenApproveAllReviews().thenAssessRisk("low", true)
                .then(writeFile("a.txt", "agent and main\n")).thenApproveAllReviews().thenAssessRisk("low", true);
        IssueWorkflow workflow = createWorkflow("true");
        IssueRecord beforeMerge = advanceUntilPhase(workflow, IssueRecord.queued(ISSUE), Phase.MERGE);
        moveTheBase("a.txt", "main\n");

        IssueRecord record = advanceUntilWaiting(workflow, beforeMerge);

        assertThat(record.status()).isEqualTo(IssueStatus.DONE);
        assertThat(record.fixLoops()).isEqualTo(1);
        assertThat(agent.requests().get(6).prompt())
                .contains("The branch has a conflict with main in these files: a.txt");
        assertThat(gitHub.squashMergeCalls()).hasSize(1);
    }

    @Test
    void buildsAndReviewsAgainWhenTheRebaseChangesTheDiff() throws ApiKeyDetectedException {
        moveTheBase("m.txt", "1\n2\n3\n4\n5\n6\n7\n8\n");
        agent.then(writeFile("m.txt", "one\n2\n3\n4\n5\n6\n7\n8\n"))
                .thenApproveAllReviews().thenAssessRisk("medium", true)
                .thenApproveAllReviews().thenAssessRisk("medium", true);
        IssueWorkflow workflow = createWorkflow("true");
        IssueRecord waiting = advanceUntilWaiting(workflow, IssueRecord.queued(ISSUE));
        // A change at line 3 is in the context lines of the change at line 1, so the diff changes.
        moveTheBase("m.txt", "1\n2\nthree\n4\n5\n6\n7\n8\n");
        gitHub.addLabelBy(ISSUE, APPROVE_LABEL, "bbossola");

        IssueRecord record = advanceUntilWaiting(workflow, waiting);

        assertThat(agent.requests()).hasSize(11);
        assertThat(record.waitReason()).contains(WaitReason.MERGE_NEEDS_APPROVAL);
        assertThat(gitHub.labelsOf(ISSUE)).doesNotContain(APPROVE_LABEL);
        assertThat(gitHub.squashMergeCalls()).isEmpty();
        assertThat(record.builtTreeHash()).contains(run(worktree(), "rev-parse", "HEAD^{tree}"));
        assertThat(record.reviewedTreeHash()).isEqualTo(record.builtTreeHash());
    }

    @Test
    void withdrawsTheApprovalInAFixLoop() throws ApiKeyDetectedException {
        agent.thenChangeFile()
                .thenApproveReview().thenRejectReview("Missing input check").thenApproveReview().thenApproveReview();
        IssueWorkflow workflow = createWorkflow("true");
        IssueRecord beforeReview = advanceUntilPhase(workflow, IssueRecord.queued(ISSUE), Phase.REVIEW);
        gitHub.addLabelBy(ISSUE, APPROVE_LABEL, "bbossola");

        IssueRecord record = workflow.advance(beforeReview);

        assertThat(record.fixLoops()).isEqualTo(1);
        assertThat(gitHub.labelsOf(ISSUE)).doesNotContain(APPROVE_LABEL);
    }

    @Test
    void blocksOnAGitFailure() throws ApiKeyDetectedException {
        clone = temporaryDirectory.resolve("missing-clone");

        IssueRecord record = createWorkflow("true").advance(IssueRecord.queued(ISSUE));

        assertThat(record.status()).isEqualTo(IssueStatus.BLOCKED);
        assertThat(record.waitReason()).contains(WaitReason.FAILURE);
        assertThat(record.notes()).anySatisfy(note -> assertThat(note).startsWith("A step failed: git"));
    }

    @Test
    void blocksWhenTheFixPromptIsMissing() throws ApiKeyDetectedException, IOException {
        agent.thenChangeFile();
        IssueWorkflow workflow = createWorkflow("false");
        IssueRecord inFixLoop = advanceUntilPhase(workflow, IssueRecord.queued(ISSUE), Phase.BUILD);
        inFixLoop = workflow.advance(inFixLoop);
        Files.delete(worktree().resolve(".factory/fix-prompt.md"));

        IssueRecord record = workflow.advance(inFixLoop);

        assertThat(inFixLoop.fixLoops()).isEqualTo(1);
        assertThat(record.status()).isEqualTo(IssueStatus.BLOCKED);
        assertThat(record.notes()).contains("The findings of the fix loop are missing.");
        assertThat(agent.requests()).hasSize(1);
    }
}
