package com.meterian.factory.workflow;

import static com.meterian.factory.testing.TemporaryRepository.commitFile;
import static com.meterian.factory.testing.TemporaryRepository.createBare;
import static com.meterian.factory.testing.TemporaryRepository.createClone;
import static com.meterian.factory.testing.TemporaryRepository.run;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meterian.factory.agent.CredentialMismatchException;
import com.meterian.factory.agent.PhaseRequest;
import com.meterian.factory.agent.UsageWindow;
import com.meterian.factory.board.BoardUpdates;
import com.meterian.factory.build.BuildRunner;
import com.meterian.factory.configuration.ClaudeSettings;
import com.meterian.factory.configuration.FactoryConfiguration;
import com.meterian.factory.configuration.IsolationMode;
import com.meterian.factory.configuration.RepositoryName;
import com.meterian.factory.configuration.WorkerLimits;
import com.meterian.factory.git.Gatekeeper;
import com.meterian.factory.git.Git;
import com.meterian.factory.git.ProcessGit;
import com.meterian.factory.github.GitHubException;
import com.meterian.factory.github.IssueComment;
import com.meterian.factory.github.MergeOutcome;
import com.meterian.factory.definition.BuiltInDefinition;
import com.meterian.factory.definition.ClasspathDefinitionFiles;
import com.meterian.factory.definition.DefinitionException;
import com.meterian.factory.definition.DefinitionFiles;
import com.meterian.factory.definition.DefinitionLoader;
import com.meterian.factory.definition.DefinitionLookup;
import com.meterian.factory.definition.FactoryDefinition;
import com.meterian.factory.ownership.LostIssues;
import com.meterian.factory.ownership.OwnershipGuard;
import com.meterian.factory.ownership.OwnershipLostException;
import com.meterian.factory.prompt.PromptLibrary;
import com.meterian.factory.prompt.PromptTexts;
import com.meterian.factory.state.HandOver;
import com.meterian.factory.state.IssueRecord;
import com.meterian.factory.testing.CollectedEvents;
import com.meterian.factory.testing.RecordStates;
import com.meterian.factory.state.LivePlace;
import com.meterian.factory.state.LiveSession;
import com.meterian.factory.state.MainRecordReader;
import com.meterian.factory.state.PendingPrompt;
import com.meterian.factory.state.RiskLevel;
import com.meterian.factory.state.StatusComment;
import com.meterian.factory.state.Verdict;
import com.meterian.factory.testing.DefinitionCommits;
import com.meterian.factory.testing.FakeAgentRunner;
import com.meterian.factory.testing.FakeGitHubClient;
import com.meterian.factory.testing.FakeLiveSessions;
import com.meterian.factory.usage.ContextTracker;
import com.meterian.factory.usage.UsageTracker;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class IssueWorkflowTest {

    private static final int ISSUE = 7;
    private static final String WORKER_LOGIN = "factory-bot";
    private static final String APPROVE_LABEL = "factory:approve";
    private static final String IMPLEMENT_PROMPT_START = "Do the work of the issue below in the current workspace.";
    private static final String REVIEW_RETRY_NOTE = "A review session failed; the factory runs the reviews again.";
    private static final String TEXT_CHANGED_SENTENCE = "The issue text changed after the label factory."
            + " Add the label factory:approve to accept the new text.";
    private static final String UNKNOWN_COMMITS_NOTE = "The forward can fail when the branch on GitHub has"
            + " commits that the factory does not know. Check the branch on GitHub. To let the factory replace the"
            + " branch, delete the branch on GitHub, and then add the label factory:start-again.";

    @TempDir
    Path temporaryDirectory;

    private final Git git = new ProcessGit();
    private final CollectedEvents events = new CollectedEvents();
    private final FakeGitHubClient gitHub = new FakeGitHubClient();
    private final FakeAgentRunner agent = new FakeAgentRunner();
    private final UsageTracker usage = new UsageTracker(0.90, Clock.systemUTC());
    private final BuildRunner buildRunner = new BuildRunner();
    private final FakeLiveSessions liveSessions = new FakeLiveSessions();
    private final PageRequests pageRequests = new PageRequests(Clock.systemUTC());
    private Path origin;
    private Path clone;
    private Path githubRepository;

    @BeforeEach
    void createRepositoriesAndIssue() {
        origin = createBare(temporaryDirectory.resolve("origin.git"));
        clone = createClone(origin, temporaryDirectory.resolve("work").resolve("repository"));
        githubRepository = createBare(temporaryDirectory.resolve("github.git"));
        Path gatekeeper = temporaryDirectory.resolve("gatekeeper.git");
        String targetUrl = createRepositoryName().sshUrl();
        Gatekeeper.ensure(gatekeeper, targetUrl);
        // The gatekeeper forwards to the local "github" repository, not to the network.
        run(gatekeeper, "config", "url." + githubRepository + ".insteadOf", targetUrl);
        gitHub.setAuthenticatedLogin(WORKER_LOGIN);
        gitHub.readPullRequestHeadsFrom(githubRepository);
        gitHub.addIssue(ISSUE, "Change the greeting", "The greeting must change.");
        gitHub.addLabel(ISSUE, "factory");
    }

    /** The worker stores the hash of the issue text at the intake. */
    private IssueRecord queuedRecord(int issueNumber) {
        return RecordStates.queued(issueNumber).withIssueTextHash(IssueText.computeHash(gitHub.issue(issueNumber)));
    }

    private IssueRecord queuedRecord() {
        return queuedRecord(ISSUE);
    }

    private RepositoryName createRepositoryName() {
        return new RepositoryName("MeterianHQ", "sample");
    }

    private IssueWorkflow createWorkflow(String buildCommand) {
        return createWorkflow(buildCommand, DefinitionLookup.builtInOnly(BuiltInDefinition.load()));
    }

    private IssueWorkflow createWorkflow(String buildCommand, DefinitionLookup definitions) {
        return createWorkflow(buildCommand, definitions, RetrySettings.defaults());
    }

    private IssueWorkflow createWorkflow(String buildCommand, RetrySettings retries) {
        return createWorkflow(buildCommand, DefinitionLookup.builtInOnly(BuiltInDefinition.load()), retries);
    }

    private IssueWorkflow createWorkflow(String buildCommand, OwnershipGuard ownershipGuard) {
        return createWorkflow(buildCommand, DefinitionLookup.builtInOnly(BuiltInDefinition.load()),
                RetrySettings.defaults(), ownershipGuard);
    }

    private IssueWorkflow createWorkflow(String buildCommand, DefinitionLookup definitions, RetrySettings retries) {
        return createWorkflow(buildCommand, definitions, retries, OwnershipGuard.none());
    }

    private IssueWorkflow createWorkflow(String buildCommand, DefinitionLookup definitions, RetrySettings retries,
                                         OwnershipGuard ownershipGuard) {
        FactoryConfiguration configuration = new FactoryConfiguration(createRepositoryName(),
                temporaryDirectory.resolve("work"), temporaryDirectory.resolve("gatekeeper.git"),
                temporaryDirectory.resolve("live"), WorkerLimits.createDefaults(), Map.of(),
                IsolationMode.SAME_USER, ClaudeSettings.createDefaults(), Optional.empty(),
                FactoryConfiguration.DEFAULT_TERMINAL_COMMAND);
        DefinitionLookup withTheBuildOfTheTest =
                record -> DefinitionCommits.withBuild(definitions.definitionOf(record), buildCommand);
        return new IssueWorkflow(configuration, gitHub, git, agent, withTheBuildOfTheTest,
                buildRunner, Duration.ZERO, usage,
                new WorkspaceLayout(temporaryDirectory.resolve("work")), WORKER_LOGIN, liveSessions,
                BoardUpdates.IGNORED, new ContextTracker(), pageRequests, retries, ownershipGuard, new LostIssues(),
                events.workerEvents());
    }

    private IssueRecord advanceUntilWaiting(IssueWorkflow workflow, IssueRecord start) throws CredentialMismatchException {
        IssueRecord record = start;
        int advances = 0;
        do {
            record = workflow.advance(record);
            advances++;
        } while (!IssueWorkflow.isAtRest(record) && advances < 30);
        return record;
    }

    private IssueRecord advanceUntilState(IssueWorkflow workflow, IssueRecord start, String stateName)
            throws CredentialMismatchException {
        IssueRecord record = start;
        int advances = 0;
        while (!record.state().equals(stateName) && !IssueWorkflow.isAtRest(record) && advances < 30) {
            record = workflow.advance(record);
            advances++;
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

    /** The post-receive hook of the gatekeeper forwards to a repository that does not exist. */
    private void breakTheForward() {
        Path gatekeeper = temporaryDirectory.resolve("gatekeeper.git");
        String targetUrl = createRepositoryName().sshUrl();
        run(gatekeeper, "config", "--unset", "url." + githubRepository + ".insteadOf");
        run(gatekeeper, "config", "url." + temporaryDirectory.resolve("missing.git") + ".insteadOf", targetUrl);
    }

    private void repairTheForward() {
        Path gatekeeper = temporaryDirectory.resolve("gatekeeper.git");
        String targetUrl = createRepositoryName().sshUrl();
        run(gatekeeper, "config", "--unset", "url." + temporaryDirectory.resolve("missing.git") + ".insteadOf");
        run(gatekeeper, "config", "url." + githubRepository + ".insteadOf", targetUrl);
    }

    private String countTheBuilds() {
        return "echo ran >> " + temporaryDirectory.resolve("builds.txt");
    }

    private long buildCount() throws IOException {
        Path buildLog = temporaryDirectory.resolve("builds.txt");
        return Files.exists(buildLog) ? Files.readAllLines(buildLog).size() : 0;
    }

    private Path createPersonClone() {
        Path person = temporaryDirectory.resolve("person");
        run(temporaryDirectory, "clone", "-q", origin.toString(), person.toString());
        run(person, "config", "user.name", "A Person");
        run(person, "config", "user.email", "person@example.com");
        return person;
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

    private IssueRecord waitForApprovalOfAMediumRisk(IssueWorkflow workflow) throws CredentialMismatchException {
        agent.thenChangeFile().thenApproveAllReviews().thenAssessRisk("medium", true);
        return advanceUntilWaiting(workflow, queuedRecord());
    }

    @Test
    void fixesALowRiskIssueToDone() throws CredentialMismatchException {
        agent.thenChangeFile().thenApproveAllReviews().thenAssessRisk("low", true);

        IssueRecord record = advanceUntilWaiting(createWorkflow("true"), queuedRecord());

        assertThat(record.state()).isEqualTo("done");
        assertThat(gitHub.squashMergeCalls()).hasSize(1);
        assertThat(gitHub.labelsOf(ISSUE)).doesNotContain("factory", APPROVE_LABEL);
        assertThat(gitHub.commentsOf(ISSUE)).hasSize(1);
        assertThat(statusComments()).hasSize(1);
        assertThat(gitHub.columnOf(ISSUE)).contains("done");
        assertThat(run(githubRepository, "branch", "--list", "factory/issue-7")).contains("factory/issue-7");
        assertThat(gitHub.pullRequests()).hasSize(1);
        assertThat(worktree()).doesNotExist();
    }

    @Test
    void writesTheEventsOfALowRiskIssueToDone() throws CredentialMismatchException {
        agent.thenChangeFile().thenApproveAllReviews().thenAssessRisk("low", true);

        advanceUntilWaiting(createWorkflow("true"), queuedRecord());

        JsonNode toBuild = findMoveTo("build");
        assertThat(toBuild.path("issue").asInt()).isEqualTo(ISSUE);
        assertThat(toBuild.path("from").asText()).isEqualTo("implement");
        assertThat(toBuild.path("event").asText()).isEqualTo("changed");
        assertThat(toBuild.path("column").asText()).isEqualTo("in-progress");
        assertThat(toBuild.path("entries").path("implement").asText()).isEqualTo("1/3");
        assertThat(toBuild.path("entries").path("review").asText()).isEqualTo("0/10");
        assertThat(findActionEnd("coding").path("prompt").asText()).isEqualTo("implement");
        assertThat(findActionEnd("coding").path("result").asText()).isEqualTo("changed");
        assertThat(findActionEnd("build").path("exit_code").asInt()).isZero();
        JsonNode reviews = findActionEnd("reviews");
        assertThat(reviews.path("result").asText()).isEqualTo("approved");
        assertThat(reviews.path("reviews").path("correctness").path("verdict").asText()).isEqualTo("approve");
        assertThat(reviews.path("reviews").path("correctness").path("findings").asInt()).isZero();
        JsonNode risk = findActionEnd("risk");
        assertThat(risk.path("risk").asText()).isEqualTo("low");
        assertThat(risk.path("small").asBoolean()).isTrue();
        assertThat(risk.path("duration_s").isNumber()).isTrue();
        int pullRequestNumber = gitHub.pullRequests().getFirst().number();
        assertThat(events.ofType("pr")).extracting(line -> line.path("event").asText())
                .containsExactly("opened", "merged");
        assertThat(events.ofType("pr").getLast().path("url").asText()).isEqualTo("https://github.com/"
                + createRepositoryName().fullName() + "/pull/" + pullRequestNumber);
        assertThat(events.ofType("pr").getLast().path("commit").asText()).isEqualTo("merge-of-" + pullRequestNumber);
        assertThat(findMoveTo("done").path("event").asText()).isEqualTo("merged");
    }

    @Test
    void writesTheFindingsOfARejectingReviewAndThePromptFix() throws CredentialMismatchException {
        agent.thenChangeFile()
                .thenApproveReview().thenRejectReview("Missing input check").thenApproveReview().thenApproveReview()
                .thenChangeFile().thenApproveAllReviews().thenAssessRisk("low", true);

        advanceUntilWaiting(createWorkflow("true"), queuedRecord());

        JsonNode rejected = events.ofType("action-end").stream()
                .filter(line -> line.path("result").asText().equals("rejected")).findFirst().orElseThrow();
        assertThat(rejected.path("reviews").path("security").path("verdict").asText()).isEqualTo("reject");
        assertThat(rejected.path("reviews").path("security").path("findings").asInt()).isEqualTo(1);
        assertThat(events.ofType("action-end").stream().filter(line -> line.path("action").asText().equals("coding")))
                .extracting(line -> line.path("prompt").asText()).containsExactly("implement", "fix");
    }

    @Test
    void writesTheBlockAndTheExitCodeOfAFailedBuild() throws CredentialMismatchException {
        agent.thenChangeFile().thenChangeFile().thenChangeFile();

        advanceUntilWaiting(createWorkflow("false"), queuedRecord());

        assertThat(findActionEnd("build").path("result").asText()).isEqualTo("failed");
        assertThat(findActionEnd("build").path("exit_code").asInt()).isEqualTo(1);
        assertThat(events.ofType("blocked")).singleElement().satisfies(line -> assertThat(line.path("reason").asText())
                .isEqualTo("The state implement reached its limit of 3 entries."));
        assertThat(findMoveTo("blocked").has("event")).isFalse();
    }

    @Test
    void writesTheReasonOfAMergeGateThatNeedsTheOwner() throws CredentialMismatchException {
        waitForApprovalOfAMediumRisk(createWorkflow("true"));

        JsonNode mergeGate = findActionEnd("merge-gate");
        assertThat(mergeGate.path("result").asText()).isEqualTo("needs-approval");
        assertThat(mergeGate.path("reason").asText()).isEqualTo("The merge needs approval.");
    }

    @Test
    void writesTheFirstLineOfAQuestion() throws CredentialMismatchException {
        agent.thenAskQuestion("Which greeting do you want?\n\nThe issue names two.");

        advanceUntilWaiting(createWorkflow("true"), queuedRecord());

        assertThat(events.ofType("question")).singleElement().satisfies(line -> assertThat(
                line.path("first_line").asText()).isEqualTo("Which greeting do you want?"));
    }

    private JsonNode findMoveTo(String stateName) {
        return events.ofType("move").stream().filter(line -> line.path("to").asText().equals(stateName))
                .findFirst().orElseThrow();
    }

    private JsonNode findActionEnd(String actionName) {
        return events.ofType("action-end").stream().filter(line -> line.path("action").asText().equals(actionName))
                .findFirst().orElseThrow();
    }

    private String pushABranchOfAnEarlierOwnerToOrigin() {
        Path person = createPersonClone();
        run(person, "checkout", "-q", "-b", "factory/issue-7");
        commitFile(person, "earlier.txt", "earlier\n", "Change of the earlier owner");
        run(person, "push", "-q", "origin", "factory/issue-7");
        return run(person, "rev-parse", "HEAD").trim();
    }

    @Test
    void prepareContinuesThePushedBranch() throws CredentialMismatchException {
        String pushedCommit = pushABranchOfAnEarlierOwnerToOrigin();
        IssueRecord handedOver = queuedRecord().withHandOver(HandOver.CONTINUES_PUSHED_BRANCH);

        IssueRecord record = createWorkflow("true").advance(handedOver);

        assertThat(record.state()).isEqualTo("build");
        assertThat(record.handOver()).isEmpty();
        assertThat(git.headCommit(worktree())).isEqualTo(pushedCommit);
    }

    @Test
    void prepareWithoutHandOverStartsFromTheMergeTarget() throws CredentialMismatchException {
        pushABranchOfAnEarlierOwnerToOrigin();

        IssueRecord record = createWorkflow("true").advance(queuedRecord());

        assertThat(record.state()).isEqualTo("implement");
        assertThat(git.headCommit(worktree())).isEqualTo(run(origin, "rev-parse", "main").trim());
    }

    @Test
    void approvalOfAContinuationRunsTheReviewsAgain() throws CredentialMismatchException {
        IssueWorkflow workflow = createWorkflow("true");
        IssueRecord waiting =
                waitForApprovalOfAMediumRisk(workflow).withHandOver(HandOver.CONTINUES_PUSHED_BRANCH);
        run(worktree(), "push", "-q", origin.toString(), "HEAD:refs/heads/factory/issue-7");
        gitHub.addLabel(ISSUE, APPROVE_LABEL);

        IssueRecord approved = workflow.advance(waiting);
        IssueRecord prepared = workflow.advance(approved);

        assertThat(approved.state()).isEqualTo("prepare");
        assertThat(approved.column()).isEqualTo("in-progress");
        assertThat(approved.handOver()).contains(HandOver.CONTINUES_PUSHED_BRANCH);
        assertThat(prepared.state()).isEqualTo("build");
        assertThat(prepared.handOver()).isEmpty();
    }

    @Test
    void waitsForTheOwnerOnAMediumRisk() throws CredentialMismatchException {
        IssueRecord record = waitForApprovalOfAMediumRisk(createWorkflow("true"));

        assertThat(record.column()).isEqualTo("waits-for-you");
        assertThat(record.state()).isEqualTo("waits-for-approval");
        assertThat(gitHub.squashMergeCalls()).isEmpty();
        assertThat(gitHub.columnOf(ISSUE)).contains("waits-for-you");
    }

    @Test
    void mergesAfterAnApproval() throws CredentialMismatchException {
        IssueWorkflow workflow = createWorkflow("true");
        IssueRecord waiting = waitForApprovalOfAMediumRisk(workflow);
        gitHub.addLabel(ISSUE, APPROVE_LABEL);

        IssueRecord record = advanceUntilWaiting(workflow, waiting);

        assertThat(record.state()).isEqualTo("done");
        assertThat(gitHub.squashMergeCalls()).hasSize(1);
        assertThat(gitHub.labelsOf(ISSUE)).doesNotContain("factory", APPROVE_LABEL);
    }

    @Test
    void aMergeAfterAnApprovalGivesTheNewestNote() throws CredentialMismatchException {
        IssueWorkflow workflow = createWorkflow("true");
        IssueRecord waiting = waitForApprovalOfAMediumRisk(workflow);
        gitHub.addLabel(ISSUE, APPROVE_LABEL);

        IssueRecord record = advanceUntilWaiting(workflow, waiting);

        assertThat(waiting.notes()).contains("The merge needs approval.");
        assertThat(record.notes()).last().isEqualTo("The pull request is merged.");
    }

    @Test
    void aMergeByAPersonOnGitHubGivesTheNewestNote() throws CredentialMismatchException {
        IssueWorkflow workflow = createWorkflow("true");
        IssueRecord waiting = waitForApprovalOfAMediumRisk(workflow);
        gitHub.mergeByAPerson(waiting.pullRequestNumber().orElseThrow());
        gitHub.addLabel(ISSUE, APPROVE_LABEL);

        IssueRecord record = advanceUntilWaiting(workflow, waiting);

        assertThat(record.state()).isEqualTo("done");
        assertThat(gitHub.squashMergeCalls()).isEmpty();
        assertThat(record.notes()).last().isEqualTo("The pull request is merged.");
    }

    private static FakeAgentRunner.Action writeFactoryFile() {
        return request -> {
            Path prompts = request.workingDirectory().resolve(".factory/prompts");
            Files.createDirectories(prompts);
            Files.writeString(prompts.resolve("fix.md"), "Fix the issue.\n");
        };
    }

    @Test
    void waitsForTheOwnerWhenThePullRequestChangesTheFactory() throws CredentialMismatchException {
        agent.then(writeFactoryFile()).thenApproveAllReviews().thenAssessRisk("low", true);

        IssueRecord record = advanceUntilWaiting(createWorkflow("true"), queuedRecord());

        assertThat(record.column()).isEqualTo("waits-for-you");
        assertThat(record.state()).isEqualTo("factory-change-approval");
        assertThat(gitHub.squashMergeCalls()).isEmpty();
    }

    @Test
    void mergesAChangeOfTheFactoryAfterTheApproval() throws CredentialMismatchException {
        agent.then(writeFactoryFile()).thenApproveAllReviews().thenAssessRisk("low", true);
        IssueWorkflow workflow = createWorkflow("true");
        IssueRecord waiting = advanceUntilWaiting(workflow, queuedRecord());
        gitHub.addLabel(ISSUE, APPROVE_LABEL);

        IssueRecord record = advanceUntilWaiting(workflow, waiting);

        assertThat(record.state()).isEqualTo("done");
        assertThat(gitHub.squashMergeCalls()).hasSize(1);
    }

    @Test
    void mergesAtOnceWithAnApprovalGivenBefore() throws CredentialMismatchException {
        IssueWorkflow workflow = createWorkflow("true");
        IssueRecord waiting = waitForApprovalOfAMediumRisk(workflow);
        IssueRecord atTheGate = RecordStates.move(waiting, "merge")
                .withApprovedTree(run(worktree(), "rev-parse", "HEAD^{tree}"));

        IssueRecord record = advanceUntilWaiting(workflow, atTheGate);

        assertThat(record.state()).isEqualTo("done");
        assertThat(gitHub.squashMergeCalls()).hasSize(1);
    }

    @Test
    void recordsTheApprovedFilesWhenTheApprovalSendsApproved() throws CredentialMismatchException {
        IssueWorkflow workflow = createWorkflow("true");
        IssueRecord waiting = waitForApprovalOfAMediumRisk(workflow);
        gitHub.addLabel(ISSUE, APPROVE_LABEL);

        IssueRecord approved = workflow.advance(waiting);

        assertThat(approved.state()).isEqualTo("merge");
        assertThat(approved.approvedTree()).contains(run(worktree(), "rev-parse", "HEAD^{tree}"));
    }

    @Test
    void acceptsFactoryChangedForTheApprovedFiles() throws CredentialMismatchException {
        agent.then(writeFactoryFile()).thenApproveAllReviews().thenAssessRisk("low", true);
        IssueWorkflow workflow = createWorkflow("true");
        IssueRecord waiting = advanceUntilWaiting(workflow, queuedRecord());
        gitHub.addLabel(ISSUE, APPROVE_LABEL);
        IssueRecord approved = workflow.advance(waiting);
        gitHub.removeLabel(ISSUE, APPROVE_LABEL);

        IssueRecord record = advanceUntilWaiting(workflow, approved);

        assertThat(approved.state()).isEqualTo("merge");
        assertThat(record.state()).isEqualTo("done");
        assertThat(gitHub.squashMergeCalls()).hasSize(1);
    }

    @Test
    void asksAgainAfterACodeChange() throws CredentialMismatchException {
        agent.thenChangeFile()
                .thenApproveReview().thenRejectReview("Missing input check").thenApproveReview().thenApproveReview()
                .thenChangeFile();
        IssueWorkflow workflow = createWorkflow("true");
        IssueRecord beforeReview = advanceUntilState(workflow, queuedRecord(), "review").withApprovedTree("stale");

        IssueRecord inFix = workflow.advance(beforeReview);
        IssueRecord record = workflow.advance(inFix);

        assertThat(record.state()).isEqualTo("build");
        assertThat(record.approvedTree()).isEmpty();
    }

    @Test
    void aCodeChangeRemovesTheRiskResult() throws CredentialMismatchException {
        agent.thenChangeFile()
                .thenApproveReview().thenRejectReview("Missing input check").thenApproveReview().thenApproveReview()
                .thenChangeFile();
        IssueWorkflow workflow = createWorkflow("true");
        IssueRecord beforeReview = advanceUntilState(workflow, queuedRecord(), "review")
                .withRisk(RiskLevel.LOW, true, List.of("An earlier assessment."));

        IssueRecord inFix = workflow.advance(beforeReview);
        IssueRecord record = workflow.advance(inFix);

        assertThat(record.state()).isEqualTo("build");
        assertThat(record.risk()).isEmpty();
        assertThat(record.small()).isFalse();
    }

    @Test
    void givesFactoryChangedAlsoWithAutoMerge() throws Exception {
        agent.then(writeFactoryFile()).thenApproveAllReviews().thenAssessRisk("low", true);
        DefinitionLookup definitions = DefinitionLookup.builtInOnly(loadBuiltInWithAutoMerge());

        IssueRecord record = advanceUntilWaiting(createWorkflow("true", definitions), queuedRecord());

        assertThat(record.state()).isEqualTo("factory-change-approval");
        assertThat(gitHub.squashMergeCalls()).isEmpty();
    }

    @Test
    void mergesWithAutoMergeWithoutTheRiskAnswer() throws Exception {
        agent.thenChangeFile().thenApproveAllReviews().thenAssessRisk("high", false);
        DefinitionLookup definitions = DefinitionLookup.builtInOnly(loadBuiltInWithAutoMerge());

        IssueRecord record = advanceUntilWaiting(createWorkflow("true", definitions), queuedRecord());

        assertThat(record.state()).isEqualTo("done");
    }

    private static FactoryDefinition loadBuiltInWithAutoMerge() throws DefinitionException {
        DefinitionFiles builtIn = new ClasspathDefinitionFiles();
        String mainFile = builtIn.read("factory.yaml").orElseThrow().replace("mode: risk", "mode: auto-merge");
        return DefinitionLoader.load(path -> path.equals("factory.yaml") ? Optional.of(mainFile) : builtIn.read(path));
    }

    @Test
    void runsAFixLoopAfterARejectingReview() throws CredentialMismatchException {
        agent.thenChangeFile()
                .thenApproveReview().thenRejectReview("Missing input check").thenApproveReview().thenApproveReview()
                .thenChangeFile().thenApproveAllReviews().thenAssessRisk("low", true);

        IssueRecord record = advanceUntilWaiting(createWorkflow("true"), queuedRecord());

        List<PhaseRequest> requests = agent.requests();
        PhaseRequest firstImplement = requests.get(0);
        PhaseRequest secondImplement = requests.get(5);
        assertThat(record.state()).isEqualTo("done");
        assertThat(record.entriesOf("implement")).isEqualTo(2);
        assertThat(secondImplement.prompt()).contains("Missing input check");
        assertThat(secondImplement.sessionId()).isEqualTo(firstImplement.sessionId());
        assertThat(secondImplement.newSession()).isFalse();
        assertThat(firstImplement.newSession()).isTrue();
    }

    private static FakeAgentRunner.Action changeFileAndDescribe(String description) {
        return request -> {
            Files.writeString(request.workingDirectory().resolve("work.txt"), description + "\n");
            Files.createDirectories(request.workingDirectory().resolve(".factory-work"));
            Files.writeString(request.workingDirectory().resolve(".factory-work/description.md"), description);
        };
    }

    @Test
    void putsTheDescriptionOfTheAgentInThePullRequest() throws CredentialMismatchException {
        agent.then(changeFileAndDescribe("The greeting says hello.")).thenApproveAllReviews()
                .thenAssessRisk("low", true);

        advanceUntilWaiting(createWorkflow("true"), queuedRecord());

        assertThat(gitHub.pullRequests().getFirst().body())
                .isEqualTo("Fixes #7\n\n## The change\n\nThe greeting says hello.\n\n"
                        + "The factory made this change for the issue #7.");
        assertThat(run(githubRepository, "ls-tree", "-r", "--name-only", "factory/issue-7"))
                .contains("work.txt").doesNotContain("description.md");
    }

    @Test
    void editsThePullRequestBodyAfterAFixLoopChangesTheDescription() throws CredentialMismatchException {
        agent.then(changeFileAndDescribe("The first description."))
                .thenApproveReview().thenRejectReview("Missing input check").thenApproveReview().thenApproveReview()
                .then(changeFileAndDescribe("The second description.")).thenApproveAllReviews()
                .thenAssessRisk("low", true);

        advanceUntilWaiting(createWorkflow("true"), queuedRecord());

        assertThat(gitHub.editedPullRequestBodies()).hasSize(1);
        assertThat(gitHub.pullRequests().getFirst().body()).contains("The second description.")
                .doesNotContain("The first description.");
    }

    @Test
    void keepsThePullRequestBodyWhenAFixLoopLeavesNoDescription() throws CredentialMismatchException {
        agent.then(changeFileAndDescribe("The first description."))
                .thenApproveReview().thenRejectReview("Missing input check").thenApproveReview().thenApproveReview()
                .then(request -> {
                    Files.writeString(request.workingDirectory().resolve("work.txt"), "fixed\n");
                    Files.delete(request.workingDirectory().resolve(".factory-work/description.md"));
                }).thenApproveAllReviews().thenAssessRisk("low", true);

        advanceUntilWaiting(createWorkflow("true"), queuedRecord());

        assertThat(gitHub.editedPullRequestBodies()).isEmpty();
        assertThat(gitHub.pullRequests().getFirst().body()).contains("The first description.");
    }

    @Test
    void aReviewNoteStartsNoFixLoopAndReachesThePullRequest() throws CredentialMismatchException {
        agent.then(changeFileAndDescribe("The greeting says hello."))
                .thenApproveReviewWithNote("Info", "Fixes #12 can come later.")
                .thenApproveReview().thenApproveReview().thenApproveReview()
                .thenAssessRisk("low", true);

        IssueRecord record = advanceUntilWaiting(createWorkflow("true"), queuedRecord());

        assertThat(record.state()).isEqualTo("done");
        assertThat(record.entriesOf("implement")).isEqualTo(1);
        assertThat(agent.requests()).hasSize(6);
        assertThat(gitHub.pullRequests().getFirst().body())
                .isEqualTo("Fixes #7\n\n## The change\n\nThe greeting says hello.\n\n"
                        + "The factory made this change for the issue #7.\n\n"
                        + "## Notes of the reviews\n\n"
                        + "- correctness: Info: Fixes issue 12 can come later.");
    }

    @Test
    void writesTheReviewNotesAfterTheRecordOfTheRound() throws CredentialMismatchException {
        agent.then(changeFileAndDescribe("The greeting says hello."))
                .thenApproveReviewWithNote("Info", "A note.")
                .thenApproveReview().thenApproveReview().thenApproveReview()
                .thenAssessRisk("low", true);
        List<String> statesAtTheEdit = new ArrayList<>();
        gitHub.runOnPullRequestBodyEdit(() -> statesAtTheEdit.add(
                StatusComment.findRecord(gitHub.commentsOf(ISSUE), WORKER_LOGIN).orElseThrow().state()));

        advanceUntilWaiting(createWorkflow("true"), queuedRecord());

        assertThat(statesAtTheEdit).containsExactly("risk");
    }

    @Test
    void aFailedWriteOfTheReviewNotesKeepsTheReviewRound() throws CredentialMismatchException {
        agent.then(changeFileAndDescribe("The greeting says hello."))
                .thenApproveReviewWithNote("Info", "A note.")
                .thenApproveReview().thenApproveReview().thenApproveReview()
                .thenAssessRisk("low", true);
        gitHub.failTheNextPullRequestBodyEdit();

        IssueRecord record = advanceUntilWaiting(createWorkflow("true"), queuedRecord());

        assertThat(record.state()).isEqualTo("done");
        assertThat(record.entriesOf("implement")).isEqualTo(1);
        assertThat(agent.requests()).hasSize(6);
        assertThat(gitHub.pullRequests().getFirst().body()).doesNotContain("## Notes of the reviews");
    }

    @Test
    void ignoresTheNotesOfAResultForAnotherTree() throws CredentialMismatchException {
        agent.then(changeFileAndDescribe("The greeting says hello."))
                .thenApproveReviewOfTreeWithNote("0000000", "Info", "A note for another tree.")
                .thenApproveReview().thenApproveReview().thenApproveReview()
                .thenChangeFile().thenApproveAllReviews().thenAssessRisk("low", true);

        advanceUntilWaiting(createWorkflow("true"), queuedRecord());

        assertThat(gitHub.editedPullRequestBodies()).noneMatch(body -> body.contains("A note for another tree."));
        assertThat(gitHub.pullRequests().getFirst().body()).doesNotContain("A note for another tree.");
    }

    @Test
    void editsNoPullRequestBodyWhenTheDescriptionStaysTheSame() throws CredentialMismatchException {
        agent.then(changeFileAndDescribe("The same description."))
                .thenApproveReview().thenRejectReview("Missing input check").thenApproveReview().thenApproveReview()
                .thenChangeFile().thenApproveAllReviews().thenAssessRisk("low", true);

        advanceUntilWaiting(createWorkflow("true"), queuedRecord());

        assertThat(gitHub.editedPullRequestBodies()).isEmpty();
    }

    @Test
    void blocksAfterTheFixLoopLimit() throws CredentialMismatchException {
        agent.thenChangeFile().thenChangeFile().thenChangeFile();

        IssueRecord record = advanceUntilWaiting(createWorkflow("false"), queuedRecord());

        assertThat(record.state()).isEqualTo("blocked");
        assertThat(record.newestNote()).contains("The state implement reached its limit of 3 entries.");
        assertThat(record.previousState()).contains("implement");
        assertThat(agent.requests()).hasSize(3);
        assertThat(gitHub.columnOf(ISSUE)).contains("blocked");
    }

    @Test
    void followsADefinitionWithAnotherLimit() throws Exception {
        agent.thenChangeFile().thenChangeFile().thenChangeFile();
        DefinitionLookup definitions = DefinitionLookup.builtInOnly(loadBuiltInWithImplementLimit(2));

        IssueRecord record = advanceUntilWaiting(createWorkflow("false", definitions), queuedRecord());

        assertThat(record.state()).isEqualTo("blocked");
        assertThat(record.newestNote()).contains("The state implement reached its limit of 2 entries.");
        assertThat(agent.requests()).hasSize(2);
    }

    @Test
    void showsTheLimitOfTheDefinitionOfTheRecord() throws Exception {
        agent.thenChangeFile().thenChangeFile().thenChangeFile();
        DefinitionLookup definitions = DefinitionLookup.builtInOnly(loadBuiltInWithImplementLimit(2));

        IssueRecord record = advanceUntilWaiting(createWorkflow("false", definitions), queuedRecord());

        assertThat(gitHub.commentsOf(ISSUE)).anySatisfy(comment -> assertThat(comment.body())
                .contains("Entries: implement " + record.entriesOf("implement") + " of 2, review 0 of 10"));
    }

    @Test
    void blocksAnIssueWhoseDefinitionDoesNotLoad() throws Exception {
        String commit = "0123456789abcdef0123456789abcdef01234567";
        DefinitionLookup definitions = record -> {
            if (record.definitionVersion().isPresent()) {
                throw new IllegalStateException("factory.yaml: unknown key workflow.x");
            }
            return BuiltInDefinition.load();
        };

        IssueRecord record = createWorkflow("true", definitions).advance(queuedRecord().withDefinitionVersion(commit));

        assertThat(record.state()).isEqualTo("prepare");
        assertThat(record.column()).isEqualTo("blocked");
        assertThat(record.notes()).contains(
                "The definition 0123456 of the issue does not load: factory.yaml: unknown key workflow.x.");
        assertThat(agent.requests()).isEmpty();
    }

    @Test
    void doesNotStartARecordWhoseDefinitionDoesNotLoad() throws Exception {
        String commit = "0123456789abcdef0123456789abcdef01234567";
        DefinitionLookup definitions = record -> {
            if (record.definitionVersion().isPresent()) {
                throw new IllegalStateException("factory.yaml: unknown key workflow.x");
            }
            return BuiltInDefinition.load();
        };
        IssueWorkflow workflow = createWorkflow("true", definitions);
        IssueRecord inImplement = RecordStates.move(queuedRecord(), "implement").withDefinitionVersion(commit);

        IssueRecord record = workflow.advance(inImplement);

        assertThat(record.state()).isEqualTo("implement");
        assertThat(record.action()).isEmpty();
        assertThat(record.column()).isEqualTo("blocked");
        assertThat(IssueWorkflow.isWaiting(record)).isTrue();
        assertThat(workflow.isStartable(gitHub.issue(ISSUE), record)).isFalse();
        assertThat(workflow.advance(record)).isEqualTo(record);
        assertThat(agent.requests()).isEmpty();
    }

    @Test
    void runsEachIssueWithItsOwnVersion() throws Exception {
        agent.thenChangeFile().thenChangeFile().thenChangeFile().thenChangeFile().thenChangeFile();
        FactoryDefinition twoEntries = loadBuiltInWithImplementLimit(2);
        DefinitionLookup definitions = record -> record.definitionVersion().isPresent() ? twoEntries
                : BuiltInDefinition.load();
        IssueWorkflow workflow = createWorkflow("false", definitions);

        IssueRecord record = advanceUntilWaiting(workflow, queuedRecord().withDefinitionVersion("1234567890"));

        assertThat(record.state()).isEqualTo("blocked");
        assertThat(agent.requests()).hasSize(2);
    }

    private static FactoryDefinition loadBuiltInWithImplementLimit(int entries) throws DefinitionException {
        DefinitionFiles builtIn = new ClasspathDefinitionFiles();
        String mainFile = builtIn.read("factory.yaml").orElseThrow().replace("limit: { entries: 3, then: blocked }",
                "limit: { entries: " + entries + ", then: blocked }");
        return DefinitionLoader.load(path -> path.equals("factory.yaml") ? Optional.of(mainFile) : builtIn.read(path));
    }

    @Test
    void waitsForTheOwnerOnAQuestion() throws CredentialMismatchException {
        agent.thenAskQuestion("Which greeting do you want?");

        IssueRecord record = advanceUntilWaiting(createWorkflow("true"), queuedRecord());

        assertThat(record.column()).isEqualTo("waits-for-you");
        assertThat(record.state()).isEqualTo("live");
        assertThat(gitHub.commentsOf(ISSUE)).anySatisfy(comment -> assertThat(comment.body())
                .contains("Which greeting do you want?").doesNotContain("@"));
        assertThat(run(worktree(), "log", "--all", "--oneline", "--", ".factory-work")).isEmpty();
        assertThat(run(worktree(), "rev-parse", "HEAD")).isEqualTo(run(worktree(), "rev-parse", "origin/main"));
    }

    @Test
    void blocksWhenTheAgentChangesNothing() throws CredentialMismatchException {
        agent.thenChangeNothing();

        IssueRecord record = advanceUntilWaiting(createWorkflow("true"), queuedRecord());

        assertThat(record.state()).isEqualTo("blocked");
        assertThat(record.newestNote()).contains("The agent made no change.");
    }

    @Test
    void reviewsRunInNewSessions() throws CredentialMismatchException {
        agent.thenChangeFile().thenApproveAllReviews().thenAssessRisk("low", true);

        advanceUntilWaiting(createWorkflow("true"), queuedRecord());

        List<PhaseRequest> requests = agent.requests();
        UUID sessionInCharge = requests.get(0).sessionId();
        List<PhaseRequest> reviews = requests.subList(1, 5);
        assertThat(reviews).allSatisfy(review -> assertThat(review.newSession()).isTrue());
        assertThat(reviews.stream().map(PhaseRequest::sessionId).collect(Collectors.toSet()))
                .hasSize(4)
                .doesNotContain(sessionInCharge);
    }

    @Test
    void runsEachReviewOfTheDefinitionInOrder() throws Exception {
        agent.thenChangeFile().thenApproveAllReviews(2).thenAssessRisk("low", true);
        DefinitionLookup definitions = DefinitionLookup.builtInOnly(loadBuiltInWithSecurityAndStyleReviews());

        IssueRecord record = advanceUntilWaiting(createWorkflow("true", definitions), queuedRecord());

        List<PhaseRequest> reviews = agent.requests().subList(1, 3);
        assertThat(agent.requests()).hasSize(4);
        assertThat(reviews.get(0).prompt()).contains(".factory-work/review-security.json");
        assertThat(reviews.get(1).prompt()).contains(".factory-work/review-style.json");
        assertThat(record.state()).isEqualTo("done");
        assertThat(record.reviews().keySet()).containsExactly("security", "style");
    }

    @Test
    void rejectsWhenOneReviewHasFindings() throws Exception {
        agent.thenChangeFile().thenApproveReview().thenRejectReview("Bad style")
                .thenChangeFile().thenApproveAllReviews(2).thenAssessRisk("low", true);
        DefinitionLookup definitions = DefinitionLookup.builtInOnly(loadBuiltInWithSecurityAndStyleReviews());

        IssueRecord record = advanceUntilWaiting(createWorkflow("true", definitions), queuedRecord());

        PhaseRequest secondImplement = agent.requests().get(3);
        assertThat(secondImplement.prompt()).contains("The review style: Bad style");
        assertThat(record.entriesOf("implement")).isEqualTo(2);
        assertThat(record.state()).isEqualTo("done");
    }

    @Test
    void showsTheVerdictOfEachReviewOnGitHubWhenTheReviewEnds() throws Exception {
        List<Map<String, Verdict>> verdictsAtReviewStart = recordVerdictsOnGitHubAtEachReviewStart();
        agent.thenChangeFile().thenApproveReview().thenRejectReview("Bad style")
                .thenChangeFile().thenApproveAllReviews(2).thenAssessRisk("low", true);
        DefinitionLookup definitions = DefinitionLookup.builtInOnly(loadBuiltInWithSecurityAndStyleReviews());

        advanceUntilWaiting(createWorkflow("true", definitions), queuedRecord());

        assertThat(verdictsAtReviewStart).containsExactly(
                Map.of(),
                Map.of("security", Verdict.APPROVE),
                Map.of(),
                Map.of("security", Verdict.APPROVE));
    }

    @Test
    void aFailedReviewSessionKeepsTheVerdictsOfTheFinishedReviews() throws Exception {
        agent.thenChangeFile().thenApproveReview().thenApproveReview().thatFails();
        DefinitionLookup definitions = DefinitionLookup.builtInOnly(loadBuiltInWithSecurityAndStyleReviews());
        IssueWorkflow workflow = createWorkflow("true", definitions);
        IssueRecord ready = advanceUntilState(workflow, queuedRecord(), "review");

        IssueRecord record = workflow.advance(ready);

        assertThat(record.reviews()).isEqualTo(Map.of("security", Verdict.APPROVE));
        assertThat(readRecordFromGitHub().reviews()).isEqualTo(Map.of("security", Verdict.APPROVE));
    }

    @Test
    void aStoppedReviewSessionKeepsTheVerdictsOfTheFinishedReviews() throws Exception {
        agent.thenChangeFile().thenApproveReview().thenApproveReview().thatIsStopped();
        DefinitionLookup definitions = DefinitionLookup.builtInOnly(loadBuiltInWithSecurityAndStyleReviews());
        IssueWorkflow workflow = createWorkflow("true", definitions);
        IssueRecord ready = advanceUntilState(workflow, queuedRecord(), "review");

        IssueRecord record = workflow.advance(ready);

        assertThat(record.reviews()).isEqualTo(Map.of("security", Verdict.APPROVE));
        assertThat(readRecordFromGitHub().reviews()).isEqualTo(Map.of("security", Verdict.APPROVE));
    }

    @Test
    void aStopAtTheFirstReviewOfARoundWritesOnlyTheStartOfTheRound() throws Exception {
        agent.thenChangeFile().thenApproveReview().thenRejectReview("Bad style")
                .thenChangeFile().thenApproveReview().thatIsStopped();
        DefinitionLookup definitions = DefinitionLookup.builtInOnly(loadBuiltInWithSecurityAndStyleReviews());
        IssueWorkflow workflow = createWorkflow("true", definitions);
        IssueRecord firstRound = advanceUntilState(workflow, queuedRecord(), "review");
        IssueRecord secondRound = advanceUntilState(workflow, workflow.advance(firstRound), "review");
        int editsBeforeTheSecondRound = gitHub.editedBodies().size();

        IssueRecord record = workflow.advance(secondRound);

        assertThat(gitHub.editedBodies()).hasSize(editsBeforeTheSecondRound + 1);
        assertThat(record.reviews()).isEmpty();
        assertThat(readRecordFromGitHub().reviews()).isEmpty();
    }

    @Test
    void startsNoMoreReviewsWhenTheGuardClosesInTheRound() throws Exception {
        Instant now = Instant.now();
        agent.thenChangeFile().thenApproveReviewAnd(request -> usage.record(List.of(
                new UsageWindow("five_hour", 0.95, now.plus(Duration.ofHours(1)), "allowed", now))));
        DefinitionLookup definitions = DefinitionLookup.builtInOnly(loadBuiltInWithSecurityAndStyleReviews());
        IssueWorkflow workflow = createWorkflow("true", definitions);
        IssueRecord ready = advanceUntilState(workflow, queuedRecord(), "review");

        IssueRecord record = workflow.advance(ready);

        assertThat(agent.requests()).hasSize(2);
        assertThat(record.state()).isEqualTo("review");
        assertThat(record.reviews()).isEqualTo(Map.of("security", Verdict.APPROVE));
    }

    @Test
    void aFailedReviewActionBlocksWithoutTheVerdictsOfTheEarlierRound() throws Exception {
        agent.thenChangeFile().thenApproveReview().thenRejectReview("Bad style")
                .thenChangeFile().then(request -> {
                    throw new IOException("The disk is full.");
                });
        DefinitionLookup definitions = DefinitionLookup.builtInOnly(loadBuiltInWithSecurityAndStyleReviews());

        IssueRecord record = advanceUntilWaiting(createWorkflow("true", definitions), queuedRecord());

        assertThat(record.state()).isEqualTo("blocked");
        assertThat(record.reviews()).isEmpty();
        assertThat(readRecordFromGitHub().reviews()).isEmpty();
    }

    /** The verdicts in the status comment on GitHub, at the start of each review session. */
    private List<Map<String, Verdict>> recordVerdictsOnGitHubAtEachReviewStart() {
        List<Map<String, Verdict>> verdicts = new ArrayList<>();
        agent.beforeEachPhase(request -> {
            if (request.prompt().contains(".factory-work/review-")) {
                verdicts.add(new LinkedHashMap<>(readRecordFromGitHub().reviews()));
            }
        });
        return verdicts;
    }

    private static FactoryDefinition loadBuiltInWithSecurityAndStyleReviews() throws DefinitionException {
        DefinitionFiles builtIn = new ClasspathDefinitionFiles();
        String mainFile = builtIn.read("factory.yaml").orElseThrow().replace(
                "          correctness: prompts/review-correctness.md\n"
                        + "          security: prompts/review-security.md\n"
                        + "          architecture: prompts/review-architecture.md\n"
                        + "          quality: prompts/review-quality.md\n",
                "          security: prompts/review-security.md\n          style: prompts/review-style.md\n");
        String stylePrompt = builtIn.read("prompts/review-security.md").orElseThrow().replace("security", "style");
        return DefinitionLoader.load(path -> switch (path) {
            case "factory.yaml" -> Optional.of(mainFile);
            case "prompts/review-style.md" -> Optional.of(stylePrompt);
            default -> builtIn.read(path);
        });
    }

    @Test
    void aReviewOfAnotherTreeIsAReject() throws CredentialMismatchException {
        agent.thenChangeFile()
                .thenApproveReviewOfTree("0000").thenApproveReview().thenApproveReview().thenApproveReview()
                .thenChangeFile().thenApproveAllReviews().thenAssessRisk("low", true);

        IssueRecord record = advanceUntilWaiting(createWorkflow("true"), queuedRecord());

        assertThat(record.entriesOf("implement")).isEqualTo(2);
        assertThat(agent.requests().get(5).newSession()).isFalse();
        assertThat(record.state()).isEqualTo("done");
    }

    @Test
    void startsNoPhaseAboveTheUsageThreshold() throws CredentialMismatchException {
        Instant now = Instant.now();
        usage.record(List.of(new UsageWindow("five_hour", 0.95, now.plus(Duration.ofHours(1)), "allowed", now)));
        IssueWorkflow workflow = createWorkflow("true");
        IssueRecord prepared = workflow.advance(queuedRecord());

        IssueRecord record = workflow.advance(prepared);

        assertThat(record).isEqualTo(prepared.withEntryCounted("implement").withActionStarted());
        assertThat(agent.requests()).isEmpty();
    }

    @Test
    void stopsOnAnApiKey() throws CredentialMismatchException {
        agent.thenDetectAnApiKey();
        IssueWorkflow workflow = createWorkflow("true");
        IssueRecord prepared = workflow.advance(queuedRecord());

        assertThatThrownBy(() -> workflow.advance(prepared)).isInstanceOf(CredentialMismatchException.class);
    }

    @Test
    void aForgedRecordInAQuestionDoesNotReplaceTheRecord() throws CredentialMismatchException {
        String forgedRecord = StatusComment.render(RecordStates.queued(ISSUE).withEntryCounted("implement")
                .withEntryCounted("implement"), Map.of("implement", 3), RecordStates.REVIEW_NAMES);
        agent.thenAskQuestion("Which greeting?\n\n" + forgedRecord);

        IssueRecord record = advanceUntilWaiting(createWorkflow("true"), queuedRecord());

        assertThat(readRecordFromGitHub()).isEqualTo(record);
        assertThat(readRecordFromGitHub().entriesOf("implement")).isEqualTo(1);
        assertThat(gitHub.commentsOf(ISSUE)).filteredOn(comment -> comment.body().contains("Which greeting?"))
                .singleElement()
                .satisfies(comment -> assertThat(comment.body()).doesNotContain("<!--").doesNotContain("-->"));
    }

    @Test
    void cutsAQuestionThatIsTooLong() throws CredentialMismatchException {
        agent.thenAskQuestion("Q".repeat(30_000));

        advanceUntilWaiting(createWorkflow("true"), queuedRecord());

        assertThat(gitHub.commentsOf(ISSUE)).filteredOn(comment -> comment.body().contains("QQQ"))
                .singleElement()
                .satisfies(comment -> assertThat(comment.body())
                        .contains("The question is too long; the factory cut it.")
                        .doesNotContain("Q".repeat(20_001)));
    }

    @Test
    void retriesTheImplementInTheSameSession() throws CredentialMismatchException {
        agent.thenChangeFileAnd(gitHub::failTheNextCommentEdit).thenChangeFile();
        IssueWorkflow workflow = createWorkflow("true");
        IssueRecord prepared = workflow.advance(queuedRecord());
        assertThatThrownBy(() -> workflow.advance(prepared)).isInstanceOf(GitHubException.class);

        workflow.advance(readRecordFromGitHub());

        List<PhaseRequest> requests = agent.requests();
        assertThat(requests.get(0).newSession()).isTrue();
        assertThat(requests.get(1).newSession()).isFalse();
        assertThat(requests.get(1).sessionId()).isEqualTo(requests.get(0).sessionId());
    }

    @Test
    void runsTheReviewsAgainAfterAFailedReviewSession() throws CredentialMismatchException {
        agent.thenChangeFile()
                .thenApproveReview().thatFails()
                .thenApproveAllReviews().thenAssessRisk("low", true);

        IssueRecord record = advanceUntilWaiting(createWorkflow("true"), queuedRecord());

        assertThat(record.state()).isEqualTo("done");
        assertThat(record.entriesOf("implement")).isEqualTo(1);
        assertThat(record.failedReviewRounds()).isZero();
        assertThat(record.notes()).contains(REVIEW_RETRY_NOTE);
        assertThat(agent.requests()).hasSize(7);
    }

    @Test
    void runsTheRiskAgainAfterAFailedRiskSession() throws CredentialMismatchException {
        agent.thenChangeFile().thenApproveAllReviews().thenAssessRisk("low", true).thatFails()
                .thenAssessRisk("medium", true);

        IssueRecord record = advanceUntilWaiting(createWorkflow("true"), queuedRecord());

        assertThat(record.risk()).contains(RiskLevel.MEDIUM);
        assertThat(record.entriesOf("implement")).isEqualTo(1);
        assertThat(record.notes()).contains(REVIEW_RETRY_NOTE);
        assertThat(record.state()).isEqualTo("waits-for-approval");
        assertThat(gitHub.squashMergeCalls()).isEmpty();
    }

    @Test
    void aStoppedImplementSessionKeepsTheIssueInProgress() throws CredentialMismatchException {
        agent.then(writeFile("half-done.txt", "half done\n")).thatIsStopped();
        IssueWorkflow workflow = createWorkflow("true");
        IssueRecord ready = advanceUntilState(workflow, queuedRecord(), "implement");

        IssueRecord record = workflow.advance(ready);

        assertThat(record.column()).isEqualTo("in-progress");
        assertThat(record.state()).isEqualTo("implement");
        assertThat(record.agentSessionStarted()).isTrue();
        assertThat(record.notes()).isEmpty();
        assertThat(worktree().resolve("half-done.txt")).exists();
    }

    @Test
    void aFirstSessionStoppedBeforeItsFileExistsStartsAgainAsANewSession() throws CredentialMismatchException {
        agent.withoutASavedSession().thenChangeNothing().thatIsStopped().thenChangeFile();
        IssueWorkflow workflow = createWorkflow("true");
        IssueRecord ready = advanceUntilState(workflow, queuedRecord(), "implement");

        IssueRecord stopped = workflow.advance(ready);
        workflow.advance(stopped);

        assertThat(stopped.agentSessionStarted()).isFalse();
        assertThat(readRecordFromGitHub().agentSessionStarted()).isTrue();
        assertThat(agent.requests().get(1).newSession()).isTrue();
    }

    @Test
    void aResumedSessionStaysStartedAfterAStop() throws CredentialMismatchException {
        agent.thenChangeNothing().thatIsStopped().withoutASavedSession().thenChangeFile();
        IssueWorkflow workflow = createWorkflow("true");
        IssueRecord ready = advanceUntilState(workflow, queuedRecord(), "implement");
        IssueRecord started = ready.withAgentSessionStarted();

        IssueRecord stopped = workflow.advance(started);

        assertThat(stopped.agentSessionStarted()).isTrue();
    }

    @Test
    void aStoppedReviewSessionIsNotAFailedReviewRound() throws CredentialMismatchException {
        agent.thenChangeFile().thenApproveReview().thatIsStopped();
        IssueWorkflow workflow = createWorkflow("true");
        IssueRecord ready = advanceUntilState(workflow, queuedRecord(), "review");

        IssueRecord record = workflow.advance(ready);

        assertThat(record.state()).isEqualTo("review");
        assertThat(record.failedReviewRounds()).isZero();
        assertThat(record.notes()).doesNotContain(REVIEW_RETRY_NOTE);
        assertThat(agent.requests()).hasSize(2);
    }

    @Test
    void aStoppedRiskSessionIsNotAFailedReviewRound() throws CredentialMismatchException {
        agent.thenChangeFile().thenApproveAllReviews().thenAssessRisk("low", true).thatIsStopped();
        IssueWorkflow workflow = createWorkflow("true");
        IssueRecord ready = advanceUntilState(workflow, queuedRecord(), "risk");

        IssueRecord record = workflow.advance(ready);

        assertThat(record.state()).isEqualTo("risk");
        assertThat(record.failedReviewRounds()).isZero();
        assertThat(record.notes()).doesNotContain(REVIEW_RETRY_NOTE);
    }

    @Test
    void blocksAfterTwoFailedReviewRounds() throws CredentialMismatchException {
        agent.thenChangeFile().thenApproveReview().thatFails().thenApproveReview().thatFails();

        IssueRecord record = advanceUntilWaiting(createWorkflow("true"), queuedRecord());

        assertThat(record.state()).isEqualTo("blocked");
        assertThat(record.notes()).contains("The review sessions failed 2 times.");
        assertThat(record.reviews()).isEmpty();
        assertThat(record.entriesOf("implement")).isEqualTo(1);
    }

    @Test
    void blocksAfterTheConfiguredReviewRetries() throws CredentialMismatchException {
        agent.thenChangeFile().thenApproveReview().thatFails().thenApproveReview().thatFails()
                .thenApproveReview().thatFails();

        IssueRecord record = advanceUntilWaiting(createWorkflow("true", new RetrySettings(3, 3)), queuedRecord());

        assertThat(record.state()).isEqualTo("blocked");
        assertThat(record.notes()).contains("The review sessions failed 3 times.");
        assertThat(agent.requests()).hasSize(4);
    }

    @Test
    void startsTheRetriesAgainAfterContinue() throws CredentialMismatchException {
        agent.thenChangeFile().thenApproveReview().thatFails().thenApproveReview().thatFails();
        IssueWorkflow workflow = createWorkflow("true");
        IssueRecord blocked = advanceUntilWaiting(workflow, queuedRecord());
        liveSessions.requestContinue(ISSUE);
        agent.thenApproveReview().thatFails();

        IssueRecord continued = workflow.advance(blocked);
        IssueRecord afterOneFailure = workflow.advance(continued);

        assertThat(blocked.state()).isEqualTo("blocked");
        assertThat(continued.failedReviewRounds()).isZero();
        assertThat(afterOneFailure.state()).isEqualTo("review");
        assertThat(afterOneFailure.failedReviewRounds()).isEqualTo(1);
    }

    @Test
    void mergesOnlyOnceWhenThePublishAfterTheMergeFails() throws CredentialMismatchException {
        gitHub.failTheCommentEditAfterTheMerge();
        agent.thenChangeFile().thenApproveAllReviews().thenAssessRisk("low", true);
        IssueWorkflow workflow = createWorkflow("true");
        IssueRecord beforeMerge = advanceUntilState(workflow, queuedRecord(), "merge");
        moveTheBase("c.txt", "base\n");
        assertThatThrownBy(() -> advanceUntilWaiting(workflow, beforeMerge)).isInstanceOf(GitHubException.class);

        IssueRecord record = advanceUntilWaiting(workflow, readRecordFromGitHub());

        String mergedTree = run(temporaryDirectory.resolve("gatekeeper.git"), "rev-parse", "factory/issue-7^{tree}");
        assertThat(record.state()).isEqualTo("done");
        assertThat(gitHub.squashMergeCalls()).hasSize(1);
        assertThat(record.builtTreeHash()).contains(mergedTree).isNotEqualTo(beforeMerge.builtTreeHash());
        assertThat(worktree()).doesNotExist();
    }

    @Test
    void notesAMergedTreeThatDiffersFromTheBuiltTree() throws CredentialMismatchException {
        gitHub.squashMergeInto(clone);
        gitHub.whenSquashMerging(() -> moveTheBase("p.txt", "a push of a person\n"));
        agent.thenChangeFile().thenApproveAllReviews().thenAssessRisk("low", true);

        IssueRecord record = advanceUntilWaiting(createWorkflow("true"), queuedRecord());

        assertThat(record.state()).isEqualTo("done");
        assertThat(record.notes()).containsExactly("The pull request is merged.",
                "The tree on main after the merge differs from the built tree.");
    }

    @Test
    void mergesAtTheNextPollAfterTheBaseMovedAtEachMergeAttempt() throws CredentialMismatchException, IOException {
        Path armed = temporaryDirectory.resolve("armed");
        Path person = temporaryDirectory.resolve("person");
        String build = "export GIT_CONFIG_GLOBAL=/dev/null GIT_CONFIG_NOSYSTEM=1; if [ -e " + armed + " ]; then cd "
                + person + " && git pull -q --ff-only origin main && git commit -q --allow-empty -m Person"
                + " && git push -q origin main; fi";
        agent.thenChangeFile().thenApproveAllReviews().thenAssessRisk("low", true);
        IssueWorkflow workflow = createWorkflow(build);
        IssueRecord beforeMerge = advanceUntilState(workflow, queuedRecord(), "merge");
        moveTheBase("c.txt", "base\n");
        createPersonClone();
        Files.createFile(armed);
        IssueRecord postponed = workflow.advance(beforeMerge);
        assertThat(postponed).isEqualTo(beforeMerge.withActionStarted());
        assertThat(run(worktree(), "rev-parse", "HEAD")).isEqualTo(beforeMerge.reviewedHeadCommit().orElseThrow());
        Files.delete(armed);

        IssueRecord record = advanceUntilWaiting(workflow, readRecordFromGitHub());

        assertThat(record.state()).isEqualTo("done");
        assertThat(gitHub.squashMergeCalls()).hasSize(1);
    }

    @Test
    void mergesAtTheNextPollAfterAnotherHeadAndAMovedBase() throws CredentialMismatchException {
        gitHub.queueMergeOutcomes(MergeOutcome.HEAD_CHANGED);
        agent.thenChangeFile().thenApproveAllReviews().thenAssessRisk("low", true);
        IssueWorkflow workflow = createWorkflow("true");
        IssueRecord beforeMerge = advanceUntilState(workflow, queuedRecord(), "merge");
        moveTheBase("c.txt", "base\n");
        assertThatThrownBy(() -> workflow.advance(beforeMerge)).isInstanceOf(GitHubException.class);
        assertThat(run(worktree(), "rev-parse", "HEAD")).isEqualTo(beforeMerge.reviewedHeadCommit().orElseThrow());

        IssueRecord record = advanceUntilWaiting(workflow, readRecordFromGitHub());

        assertThat(record.state()).isEqualTo("done");
        assertThat(gitHub.squashMergeCalls()).hasSize(2);
        assertThat(gitHub.editedBodies()).noneMatch(body -> body.contains("Blocked"));
    }

    @Test
    void mergesAtTheNextPollAfterAStoppedMergeBuild() throws Exception {
        Path armed = temporaryDirectory.resolve("armed");
        Path started = temporaryDirectory.resolve("started");
        String build = "if [ -e " + armed + " ]; then touch " + started + "; sleep 60; fi";
        agent.thenChangeFile().thenApproveAllReviews().thenAssessRisk("low", true);
        IssueWorkflow workflow = createWorkflow(build);
        IssueRecord beforeMerge = advanceUntilState(workflow, queuedRecord(), "merge");
        moveTheBase("c.txt", "base\n");
        Files.createFile(armed);
        AtomicBoolean actionEnded = new AtomicBoolean();
        Thread stopper = Thread.ofVirtual().start(() -> stopTheBuildWhenItStarts(started, actionEnded));
        IssueRecord stopped = workflow.advance(beforeMerge);
        actionEnded.set(true);
        stopper.join();
        assertThat(stopped).isEqualTo(beforeMerge.withActionStarted());
        assertThat(run(worktree(), "rev-parse", "HEAD")).isEqualTo(beforeMerge.reviewedHeadCommit().orElseThrow());
        Files.delete(armed);

        IssueRecord record = advanceUntilWaiting(workflow, readRecordFromGitHub());

        assertThat(record.state()).isEqualTo("done");
        assertThat(gitHub.squashMergeCalls()).hasSize(1);
    }

    /** Stops the build again and again until the merge-gate action ends, so that no stop comes too early. */
    private void stopTheBuildWhenItStarts(Path started, AtomicBoolean actionEnded) {
        while (!actionEnded.get()) {
            if (Files.exists(started)) {
                buildRunner.stopBuild(worktree());
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException exception) {
                return;
            }
        }
    }

    @Test
    void returnsToTheReviewedCommitWhenTheMergeStartsOnARebasedHead() throws CredentialMismatchException {
        agent.thenChangeFile().thenApproveAllReviews().thenAssessRisk("low", true);
        IssueWorkflow workflow = createWorkflow("true");
        IssueRecord beforeMerge = advanceUntilState(workflow, queuedRecord(), "merge");
        moveTheBase("c.txt", "base\n");
        // A crash in an earlier merge-gate action left the worktree rebased.
        git.fetch(worktree(), "origin", "main");
        git.rebase(worktree(), "origin/main");

        IssueRecord record = advanceUntilWaiting(workflow, beforeMerge);

        assertThat(record.state()).isEqualTo("done");
        assertThat(gitHub.squashMergeCalls()).hasSize(1);
    }

    @Test
    void aSquashMergeAnswerThatIsLostAfterTheMergeEndsDone() throws CredentialMismatchException {
        gitHub.failTheNextSquashMergeAfterTheMerge();
        agent.thenChangeFile().thenApproveAllReviews().thenAssessRisk("low", true);
        IssueWorkflow workflow = createWorkflow("true");
        IssueRecord beforeMerge = advanceUntilState(workflow, queuedRecord(), "merge");
        assertThatThrownBy(() -> workflow.advance(beforeMerge)).isInstanceOf(GitHubException.class);

        IssueRecord record = advanceUntilWaiting(workflow, readRecordFromGitHub());

        assertThat(record.state()).isEqualTo("done");
        assertThat(gitHub.squashMergeCalls()).hasSize(1);
        assertThat(gitHub.editedBodies()).noneMatch(body -> body.contains("Blocked"));
    }

    @Test
    void anotherHeadOnGitHubDoesNotBlockTheIssue() throws CredentialMismatchException {
        gitHub.queueMergeOutcomes(MergeOutcome.HEAD_CHANGED);
        agent.thenChangeFile().thenApproveAllReviews().thenAssessRisk("low", true);
        IssueWorkflow workflow = createWorkflow("true");
        IssueRecord beforeMerge = advanceUntilState(workflow, queuedRecord(), "merge");
        assertThatThrownBy(() -> workflow.advance(beforeMerge)).isInstanceOf(GitHubException.class);
        assertThat(readRecordFromGitHub()).isEqualTo(beforeMerge);

        IssueRecord record = advanceUntilWaiting(workflow, readRecordFromGitHub());

        assertThat(record.state()).isEqualTo("done");
        assertThat(gitHub.squashMergeCalls()).hasSize(2);
        assertThat(gitHub.editedBodies()).noneMatch(body -> body.contains("Blocked"));
    }

    @Test
    void buildDoesNotPushWhenAnotherAccountOwnsTheIssue() throws CredentialMismatchException {
        agent.thenChangeFile().thenApproveAllReviews().thenAssessRisk("low", true);
        OwnershipGuard anotherAccountOwnsTheIssue = issueNumber -> {
            throw new OwnershipLostException(issueNumber);
        };
        IssueWorkflow workflow = createWorkflow("true", anotherAccountOwnsTheIssue);
        IssueRecord beforeBuild = advanceUntilState(workflow, queuedRecord(), "build");

        assertThatThrownBy(() -> workflow.advance(beforeBuild)).isInstanceOf(OwnershipLostException.class);

        assertThat(run(temporaryDirectory.resolve("gatekeeper.git"), "branch", "--list")).isEmpty();
        assertThat(run(githubRepository, "branch", "--list")).isEmpty();
        assertThat(gitHub.pullRequests()).isEmpty();
    }

    @Test
    void refusedForwardOfALostIssueCountsNoFailedCheck() throws CredentialMismatchException {
        agent.thenChangeFile();
        AtomicInteger ownerChecks = new AtomicInteger();
        OwnershipGuard ownerChangesAfterThePush = issueNumber -> {
            if (ownerChecks.incrementAndGet() > 2) {
                throw new OwnershipLostException(issueNumber);
            }
        };
        IssueWorkflow workflow = createWorkflow("true", ownerChangesAfterThePush);
        IssueRecord beforeBuild = advanceUntilState(workflow, queuedRecord(), "build");
        ownerChecks.set(0);
        run(clone, "push", "-q", githubRepository.toString(), "main:refs/heads/factory/issue-7");
        String commitOfAnotherFactory = run(githubRepository, "rev-parse", "refs/heads/factory/issue-7");

        assertThatThrownBy(() -> workflow.advance(beforeBuild)).isInstanceOf(OwnershipLostException.class);

        assertThat(run(githubRepository, "rev-parse", "refs/heads/factory/issue-7")).isEqualTo(commitOfAnotherFactory);
        assertThat(readRecordFromGitHub().failedForwardChecks()).isZero();
        assertThat(readRecordFromGitHub().state()).isNotEqualTo("blocked");
    }

    @Test
    void blockAfterAForwardOverUnknownCommitsTellsWhatToDo() throws CredentialMismatchException {
        agent.thenChangeFile();
        IssueWorkflow workflow = createWorkflow(countTheBuilds());
        IssueRecord beforeBuild = advanceUntilState(workflow, queuedRecord(), "build");
        run(clone, "push", "-q", githubRepository.toString(), "main:refs/heads/factory/issue-7");
        String commitOfAnotherFactory = run(githubRepository, "rev-parse", "refs/heads/factory/issue-7");
        assertThatThrownBy(() -> workflow.advance(beforeBuild)).isInstanceOf(GitHubException.class);
        assertThatThrownBy(() -> workflow.advance(readRecordFromGitHub())).isInstanceOf(GitHubException.class);

        IssueRecord record = workflow.advance(readRecordFromGitHub());

        assertThat(record.state()).isEqualTo("blocked");
        assertThat(record.notes()).contains(UNKNOWN_COMMITS_NOTE);
        assertThat(run(githubRepository, "rev-parse", "refs/heads/factory/issue-7")).isEqualTo(commitOfAnotherFactory);
    }

    @Test
    void forwardsAgainAtTheNextPollAfterAFailedForwardInTheBuild() throws CredentialMismatchException, IOException {
        breakTheForward();
        agent.thenChangeFile().thenApproveAllReviews().thenAssessRisk("low", true);
        IssueWorkflow workflow = createWorkflow(countTheBuilds());
        IssueRecord beforeBuild = advanceUntilState(workflow, queuedRecord(), "build");
        assertThatThrownBy(() -> workflow.advance(beforeBuild))
                .isInstanceOf(GitHubException.class)
                .hasMessage("GitHub does not have the pushed commit yet.");
        assertThat(readRecordFromGitHub().failedForwardChecks()).isEqualTo(1);
        assertThat(gitHub.pullRequests()).isEmpty();
        repairTheForward();

        IssueRecord record = advanceUntilWaiting(workflow, readRecordFromGitHub());

        assertThat(record.state()).isEqualTo("done");
        assertThat(record.failedForwardChecks()).isZero();
        assertThat(buildCount()).isEqualTo(1);
        assertThat(gitHub.pullRequests()).hasSize(1);
    }

    @Test
    void anIssueWithALostForwardAndNoCountGetsItsPullRequest() throws CredentialMismatchException, IOException {
        breakTheForward();
        agent.thenChangeFile().thenApproveAllReviews().thenAssessRisk("low", true);
        IssueWorkflow workflow = createWorkflow(countTheBuilds());
        IssueRecord beforeBuild = advanceUntilState(workflow, queuedRecord(), "build");
        assertThatThrownBy(() -> workflow.advance(beforeBuild)).isInstanceOf(GitHubException.class);
        repairTheForward();

        IssueRecord record = advanceUntilWaiting(workflow, beforeBuild);

        assertThat(record.state()).isEqualTo("done");
        assertThat(gitHub.pullRequests()).hasSize(1);
    }

    @Test
    void blocksAfterThreeFailedForwardChecks() throws CredentialMismatchException, IOException {
        breakTheForward();
        agent.thenChangeFile();
        IssueWorkflow workflow = createWorkflow(countTheBuilds());
        IssueRecord beforeBuild = advanceUntilState(workflow, queuedRecord(), "build");
        String head = run(worktree(), "rev-parse", "HEAD");
        assertThatThrownBy(() -> workflow.advance(beforeBuild)).isInstanceOf(GitHubException.class);
        assertThatThrownBy(() -> workflow.advance(readRecordFromGitHub())).isInstanceOf(GitHubException.class);

        IssueRecord record = workflow.advance(readRecordFromGitHub());

        assertThat(record.state()).isEqualTo("blocked");
        assertThat(record.notes()).contains("GitHub does not show the pushed commit " + head
                + " on factory/issue-7 after 3 tries.");
        assertThat(readRecordFromGitHub().state()).isEqualTo("blocked");
        assertThat(buildCount()).isEqualTo(1);
    }

    @Test
    void forwardsAgainAtTheNextPollAfterAFailedForwardInTheMerge() throws CredentialMismatchException {
        agent.thenChangeFile().thenApproveAllReviews().thenAssessRisk("low", true);
        IssueWorkflow workflow = createWorkflow("true");
        IssueRecord beforeMerge = advanceUntilState(workflow, queuedRecord(), "merge");
        moveTheBase("c.txt", "base\n");
        breakTheForward();
        assertThatThrownBy(() -> workflow.advance(beforeMerge)).isInstanceOf(GitHubException.class);
        assertThat(readRecordFromGitHub().failedForwardChecks()).isEqualTo(1);
        assertThat(gitHub.squashMergeCalls()).isEmpty();
        repairTheForward();

        IssueRecord record = advanceUntilWaiting(workflow, readRecordFromGitHub());

        assertThat(record.state()).isEqualTo("done");
        assertThat(gitHub.squashMergeCalls()).hasSize(1);
    }

    @Test
    void blocksAtTheThirdPollWhenAForwardInTheMergeFailsForGoodAfterABaseMove() throws Exception {
        agent.thenChangeFile().thenApproveAllReviews().thenAssessRisk("low", true);
        IssueWorkflow workflow = createWorkflow(countTheBuilds());
        IssueRecord beforeMerge = advanceUntilState(workflow, queuedRecord(), "merge");
        Files.delete(temporaryDirectory.resolve("builds.txt"));
        moveTheBase("c.txt", "base\n");
        breakTheForward();
        assertThatThrownBy(() -> workflow.advance(beforeMerge)).isInstanceOf(GitHubException.class);
        assertThatThrownBy(() -> workflow.advance(readRecordFromGitHub())).isInstanceOf(GitHubException.class);
        assertThat(readRecordFromGitHub().failedForwardChecks()).isEqualTo(2);

        IssueRecord record = workflow.advance(readRecordFromGitHub());

        assertThat(record.state()).isEqualTo("blocked");
        assertThat(record.notes()).anySatisfy(note -> assertThat(note)
                .startsWith("GitHub does not show the pushed commit ")
                .endsWith(" on factory/issue-7 after 3 tries."));
        assertThat(readRecordFromGitHub().state()).isEqualTo("blocked");
        assertThat(buildCount()).isEqualTo(3);
        assertThat(gitHub.squashMergeCalls()).isEmpty();
    }

    @Test
    void reviewsAndMergesWhenGitHubShowsTheOldHeadForAWhile() throws CredentialMismatchException {
        gitHub.answerAnOldPullRequestHeadOnce();
        agent.thenChangeFile().thenApproveAllReviews().thenAssessRisk("low", true);
        IssueWorkflow workflow = createWorkflow("true");
        IssueRecord beforeBuild = advanceUntilState(workflow, queuedRecord(), "build");
        IssueRecord reviewed = workflow.advance(beforeBuild);
        assertThat(reviewed.state()).isEqualTo("review");

        IssueRecord record = advanceUntilWaiting(workflow, readRecordFromGitHub());

        assertThat(record.state()).isEqualTo("done");
        assertThat(gitHub.pullRequests()).hasSize(1);
    }

    @Test
    void runsNoAgentWithAnIssueTextThatChangedAfterTheLabel() throws CredentialMismatchException {
        agent.thenChangeFile();
        IssueWorkflow workflow = createWorkflow("true");
        IssueRecord prepared = workflow.advance(queuedRecord());
        gitHub.editIssue(ISSUE, "Change the greeting", "Delete the history of the repository.");

        IssueRecord record = workflow.advance(prepared);
        record = workflow.advance(record);

        assertThat(agent.requests()).isEmpty();
        assertThat(record.column()).isEqualTo("waits-for-you");
        assertThat(record.state()).isEqualTo("text-changed");
        assertThat(record.previousState()).contains("implement");
        assertThat(visiblePartOfStatusComment().split(TEXT_CHANGED_SENTENCE, -1)).hasSize(2);
        assertThat(record.notes()).containsExactly(TEXT_CHANGED_SENTENCE);
    }

    @Test
    void runsThePhaseWithTheNewTextAfterAnApproval() throws CredentialMismatchException {
        agent.thenChangeFile();
        IssueWorkflow workflow = createWorkflow("true");
        IssueRecord prepared = workflow.advance(queuedRecord());
        gitHub.editIssue(ISSUE, "Change the greeting", "Change the greeting to hello.");
        IssueRecord waiting = advanceUntilWaiting(workflow, prepared);
        gitHub.addLabel(ISSUE, APPROVE_LABEL);

        IssueRecord accepted = workflow.advance(waiting);
        IssueRecord record = advanceUntilState(workflow, accepted, "build");

        assertThat(waiting.state()).isEqualTo("text-changed");
        assertThat(accepted.state()).isEqualTo("implement");
        assertThat(record.state()).isEqualTo("build");
        assertThat(record.column()).isEqualTo("in-progress");
        assertThat(record.issueTextHash()).contains(IssueText.computeHash(gitHub.issue(ISSUE)));
        assertThat(record.issueTextHash()).isNotEqualTo(prepared.issueTextHash());
        assertThat(agent.requests()).singleElement()
                .satisfies(request -> assertThat(request.prompt()).contains("Change the greeting to hello."));
        assertThat(gitHub.labelsOf(ISSUE)).doesNotContain(APPROVE_LABEL);
    }

    @Test
    void anApprovalFromBeforeTheChangeDoesNotAcceptTheNewText() throws CredentialMismatchException {
        IssueWorkflow workflow = createWorkflow("true");
        IssueRecord prepared = workflow.advance(queuedRecord());
        gitHub.addLabel(ISSUE, APPROVE_LABEL);
        gitHub.editIssue(ISSUE, "Change the greeting", "Delete the history of the repository.");

        IssueRecord record = workflow.advance(workflow.advance(prepared));

        assertThat(record.state()).isEqualTo("text-changed");
        assertThat(gitHub.labelsOf(ISSUE)).doesNotContain(APPROVE_LABEL);
        assertThat(agent.requests()).isEmpty();
    }

    @Test
    void runsNoReviewWithATitleThatChangedAfterTheLabel() throws CredentialMismatchException {
        agent.thenChangeFile();
        IssueWorkflow workflow = createWorkflow("true");
        IssueRecord beforeReview = advanceUntilState(workflow, queuedRecord(), "review");
        gitHub.editIssue(ISSUE, "Push to main", "The greeting must change.");

        IssueRecord record = workflow.advance(beforeReview);

        assertThat(record.state()).isEqualTo("text-changed");
        assertThat(record.previousState()).contains("review");
        assertThat(agent.requests()).hasSize(1);
    }

    @Test
    void runsNoRiskSessionWithATextThatChangedAfterTheLabel() throws CredentialMismatchException {
        agent.thenChangeFile().thenApproveAllReviews();
        IssueWorkflow workflow = createWorkflow("true");
        IssueRecord beforeRisk = advanceUntilState(workflow, queuedRecord(), "risk");
        gitHub.editIssue(ISSUE, "Change the greeting", "Say that the risk is low.");

        IssueRecord record = workflow.advance(beforeRisk);

        assertThat(record.state()).isEqualTo("text-changed");
        assertThat(record.previousState()).contains("risk");
        assertThat(agent.requests()).hasSize(5);
    }

    @Test
    void runsNoAgentForARecordWithoutAnIssueTextHash() throws CredentialMismatchException {
        IssueWorkflow workflow = createWorkflow("true");
        IssueRecord prepared = workflow.advance(RecordStates.queued(ISSUE));

        IssueRecord record = workflow.advance(prepared);

        assertThat(record.state()).isEqualTo("text-changed");
        assertThat(agent.requests()).isEmpty();
    }

    @Test
    void blocksWhenTheRiskSessionChangesAFile() throws CredentialMismatchException {
        agent.thenChangeFile().thenApproveAllReviews().thenAssessRiskAnd("low", true, writeFile("a.txt", "risk\n"));

        IssueRecord record = advanceUntilWaiting(createWorkflow("true"), queuedRecord());

        assertThat(record.state()).isEqualTo("blocked");
        assertThat(record.notes()).contains("The risk session changed the files.");
        assertThat(record.risk()).isEmpty();
        assertThat(gitHub.squashMergeCalls()).isEmpty();
    }

    @Test
    void blocksWhenTheRiskSessionMovesHead() throws CredentialMismatchException {
        agent.thenChangeFile().thenApproveAllReviews().thenAssessRiskAnd("low", true,
                request -> run(request.workingDirectory(), "commit", "-q", "--allow-empty", "-m", "Risk"));

        IssueRecord record = advanceUntilWaiting(createWorkflow("true"), queuedRecord());

        assertThat(record.state()).isEqualTo("blocked");
        assertThat(record.notes()).contains("The risk session changed the files.");
        assertThat(gitHub.squashMergeCalls()).isEmpty();
    }

    @Test
    void solvesAConflictInAFixLoop() throws CredentialMismatchException {
        agent.then(writeFile("a.txt", "agent\n")).thenApproveAllReviews().thenAssessRisk("low", true)
                .then(writeFile("a.txt", "agent and main\n")).thenApproveAllReviews().thenAssessRisk("low", true);
        IssueWorkflow workflow = createWorkflow("true");
        IssueRecord beforeMerge = advanceUntilState(workflow, queuedRecord(), "merge");
        moveTheBase("a.txt", "main\n");

        IssueRecord record = advanceUntilWaiting(workflow, beforeMerge);

        assertThat(record.state()).isEqualTo("done");
        assertThat(record.entriesOf("implement")).isEqualTo(2);
        assertThat(agent.requests().get(6).prompt())
                .contains("The branch has a conflict with main in these files: a.txt");
        assertThat(gitHub.squashMergeCalls()).hasSize(1);
    }

    @Test
    void keepsTheBranchLinearAfterAConflictFix() throws CredentialMismatchException {
        agent.then(writeFile("a.txt", "agent\n")).thenApproveAllReviews().thenAssessRisk("low", true)
                .then(writeFile("a.txt", "agent and main\n")).thenApproveAllReviews().thenAssessRisk("low", true);
        IssueWorkflow workflow = createWorkflow("true");
        IssueRecord beforeMerge = advanceUntilState(workflow, queuedRecord(), "merge");
        moveTheBase("a.txt", "main\n");
        IssueRecord inFixLoop = workflow.advance(beforeMerge);
        IssueRecord beforeSecondMerge = advanceUntilState(workflow, inFixLoop, "merge");
        moveTheBase("b.txt", "other file\n");

        IssueRecord record = advanceUntilState(workflow, beforeSecondMerge, "done");

        assertThat(record.state()).isEqualTo("done");
        assertThat(record.entriesOf("implement")).isEqualTo(2);
        assertThat(record.conflictedPaths()).isEmpty();
        assertThat(gitHub.squashMergeCalls()).hasSize(1);
        assertThat(run(worktree(), "rev-list", "--count", "origin/main..HEAD")).isEqualTo("1");
        assertThat(run(worktree(), "rev-list", "--merges", "origin/main..HEAD")).isEmpty();
    }

    @Test
    void startsAFixLoopWhenTheConflictMarkersStay() throws CredentialMismatchException, IOException {
        agent.then(writeFile("a.txt", "agent\n")).thenApproveAllReviews().thenAssessRisk("low", true)
                .thenChangeNothing();
        IssueWorkflow workflow = createWorkflow("true");
        IssueRecord beforeMerge = advanceUntilState(workflow, queuedRecord(), "merge");
        moveTheBase("a.txt", "main\n");
        IssueRecord inFixLoop = workflow.advance(beforeMerge);
        String headBefore = run(worktree(), "rev-parse", "HEAD");

        IssueRecord record = workflow.advance(inFixLoop);

        assertThat(inFixLoop.conflictedPaths()).containsExactly("a.txt");
        assertThat(record.conflictedPaths()).containsExactly("a.txt");
        assertThat(record.entriesOf("implement")).isEqualTo(2);
        assertThat(record.state()).isEqualTo("implement");
        assertThat(record.pendingPrompt()).contains(PendingPrompt.FIX);
        assertThat(Files.readString(worktree().resolve(".factory-work/pending-findings.json")))
                .contains("The files still hold conflict markers: a.txt.");
        assertThat(run(worktree(), "rev-parse", "HEAD")).isEqualTo(headBefore);
    }

    @Test
    void buildsAndReviewsAgainWhenTheRebaseChangesTheDiff() throws CredentialMismatchException {
        moveTheBase("m.txt", "1\n2\n3\n4\n5\n6\n7\n8\n");
        agent.then(writeFile("m.txt", "one\n2\n3\n4\n5\n6\n7\n8\n"))
                .thenApproveAllReviews().thenAssessRisk("medium", true)
                .thenApproveAllReviews().thenAssessRisk("medium", true);
        IssueWorkflow workflow = createWorkflow("true");
        IssueRecord waiting = advanceUntilWaiting(workflow, queuedRecord());
        // A change at line 3 is in the context lines of the change at line 1, so the diff changes.
        moveTheBase("m.txt", "1\n2\nthree\n4\n5\n6\n7\n8\n");
        gitHub.addLabel(ISSUE, APPROVE_LABEL);

        IssueRecord record = advanceUntilWaiting(workflow, waiting);

        assertThat(agent.requests()).hasSize(11);
        assertThat(record.state()).isEqualTo("waits-for-approval");
        assertThat(gitHub.labelsOf(ISSUE)).doesNotContain(APPROVE_LABEL);
        assertThat(gitHub.squashMergeCalls()).isEmpty();
        assertThat(record.builtTreeHash()).contains(run(worktree(), "rev-parse", "HEAD^{tree}"));
        assertThat(record.reviewedTreeHash()).isEqualTo(record.builtTreeHash());
    }

    @Test
    void withdrawsTheApprovalWhenTheFixChangesTheCode() throws CredentialMismatchException {
        agent.thenChangeFile()
                .thenApproveReview().thenRejectReview("Missing input check").thenApproveReview().thenApproveReview()
                .thenChangeFile();
        IssueWorkflow workflow = createWorkflow("true");
        IssueRecord beforeReview = advanceUntilState(workflow, queuedRecord(), "review");
        gitHub.addLabel(ISSUE, APPROVE_LABEL);

        IssueRecord inFix = workflow.advance(beforeReview);
        IssueRecord record = workflow.advance(inFix);

        assertThat(inFix.state()).isEqualTo("implement");
        assertThat(inFix.pendingPrompt()).contains(PendingPrompt.FIX);
        assertThat(record.state()).isEqualTo("build");
        assertThat(record.entriesOf("implement")).isEqualTo(2);
        assertThat(gitHub.labelsOf(ISSUE)).doesNotContain(APPROVE_LABEL);
    }

    @Test
    void blocksOnAGitFailure() throws CredentialMismatchException, IOException {
        Files.move(clone, temporaryDirectory.resolve("moved-clone"));

        IssueRecord record = createWorkflow("true").advance(queuedRecord());

        assertThat(record.state()).isEqualTo("blocked");
        assertThat(record.notes()).anySatisfy(note -> assertThat(note).startsWith("An action failed: git"));
    }

    @Test
    void keepsADoneIssueDoneWhenTheWorktreeRemovalFails() throws CredentialMismatchException {
        agent.thenChangeFile().thenApproveAllReviews().thenAssessRisk("low", true);
        IssueWorkflow workflow = createWorkflow("true");
        IssueRecord merged = advanceUntilState(workflow, queuedRecord(), "done");
        run(clone, "worktree", "lock", worktree().toString());

        IssueRecord record = workflow.advance(merged);

        assertThat(record.state()).isEqualTo("done");
        assertThat(record.notes()).containsExactly("The pull request is merged.",
                "An action after the merge failed: com.meterian.factory.git.GitException. The change is merged.");
        assertThat(gitHub.columnOf(ISSUE)).contains("done");
        assertThat(gitHub.labelsOf(ISSUE)).contains("factory");
        assertThat(gitHub.editedBodies()).noneMatch(body -> body.contains("Blocked"));
        run(clone, "worktree", "unlock", worktree().toString());

        IssueRecord finished = workflow.advance(record);

        assertThat(finished.state()).isEqualTo("done");
        assertThat(gitHub.labelsOf(ISSUE)).doesNotContain("factory");
        assertThat(worktree()).doesNotExist();
    }

    @Test
    void aRefusedPhasePublishesNoBlockAndNoSessionStart() throws CredentialMismatchException {
        IssueWorkflow workflow = createWorkflow("true");
        IssueRecord prepared = workflow.advance(queuedRecord());
        agent.refuseNewPhases();

        IssueRecord record = workflow.advance(prepared);

        assertThat(record).isEqualTo(prepared);
        assertThat(record.agentSessionStarted()).isFalse();
        assertThat(readRecordFromGitHub().agentSessionStarted()).isFalse();
        assertThat(readRecordFromGitHub().column()).isEqualTo("in-progress");
        assertThat(gitHub.editedBodies()).noneMatch(body -> body.contains("Blocked"));
    }

    @Test
    void blocksWhenTheFixPromptIsMissing() throws CredentialMismatchException, IOException {
        agent.thenChangeFile();
        IssueWorkflow workflow = createWorkflow("false");
        IssueRecord inFixLoop = advanceUntilState(workflow, queuedRecord(), "build");
        inFixLoop = workflow.advance(inFixLoop);
        Files.delete(worktree().resolve(".factory-work/pending-findings.json"));

        IssueRecord record = workflow.advance(inFixLoop);

        assertThat(inFixLoop.entriesOf("implement")).isEqualTo(1);
        assertThat(inFixLoop.pendingPrompt()).contains(PendingPrompt.FIX);
        assertThat(record.state()).isEqualTo("blocked");
        assertThat(record.notes()).contains(CodingAction.MISSING_FINDINGS_NOTE);
        assertThat(agent.requests()).hasSize(1);
        JsonNode blockedCoding = events.ofType("action-end").getLast();
        assertThat(blockedCoding.path("action").asText()).isEqualTo("coding");
        assertThat(blockedCoding.path("result").asText()).isEqualTo("blocked");
        assertThat(blockedCoding.has("prompt")).isFalse();
    }

    @Test
    void continueAfterTheMissingFixPromptRunsTheImplementPrompt() throws CredentialMismatchException, IOException {
        agent.thenChangeFile();
        IssueWorkflow workflow = createWorkflow("false");
        IssueRecord inFixLoop = advanceUntilState(workflow, queuedRecord(), "build");
        inFixLoop = workflow.advance(inFixLoop);
        Files.delete(worktree().resolve(".factory-work/pending-findings.json"));
        IssueRecord blocked = workflow.advance(inFixLoop);
        liveSessions.requestContinue(ISSUE);
        agent.thenChangeFile();

        IssueRecord continued = workflow.advance(blocked);
        IssueRecord implemented = workflow.advance(continued);

        assertThat(blocked.pendingPrompt()).contains(PendingPrompt.IMPLEMENT);
        assertThat(continued.state()).isEqualTo("implement");
        assertThat(implemented.state()).isEqualTo("build");
        assertThat(agent.requests()).hasSize(2);
        assertThat(agent.requests().get(1).prompt()).startsWith(IMPLEMENT_PROMPT_START)
                .contains("The greeting must change.");
    }

    @Test
    void aFixRecordOfMainRunsTheImplementPrompt() throws CredentialMismatchException, IOException {
        agent.thenChangeFile().thenChangeFile();
        IssueWorkflow workflow = createWorkflow("true");
        IssueRecord implemented = advanceUntilState(workflow, queuedRecord(), "build");
        IssueRecord fromMain = readMainFixRecord(implemented);

        IssueRecord record = workflow.advance(fromMain);

        assertThat(fromMain.state()).isEqualTo("implement");
        assertThat(record.state()).isEqualTo("build");
        assertThat(record.notes()).doesNotContain(CodingAction.MISSING_FINDINGS_NOTE);
        assertThat(agent.requests()).hasSize(2);
        assertThat(agent.requests().get(1).prompt()).startsWith(IMPLEMENT_PROMPT_START)
                .contains("The greeting must change.");
    }

    /** A record of main in a fix loop: main keeps the fix prompt in .factory/fix-prompt.md. */
    private IssueRecord readMainFixRecord(IssueRecord implemented) throws IOException {
        String json = """
                {"issueNumber":%d,"sessionId":"%s","status":"IN_PROGRESS","phase":"IMPLEMENT","reviews":{},
                "fixLoops":1,"risk":null,"small":false,"reviewedTreeHash":null,"reviewedPatchId":null,
                "builtTreeHash":null,"waitReason":null,"pullRequestNumber":null,"statusCommentId":null,"notes":[],
                "agentSessionStarted":true,"failedReviewRounds":0,"conflictedPaths":[],"issueTextHash":"%s",
                "reviewedHeadCommit":null,"failedForwardChecks":0,"failedForwardTree":null,"live":null,
                "afterConversation":false}
                """.formatted(ISSUE, implemented.sessionId(), implemented.issueTextHash().orElseThrow());
        return MainRecordReader.read(new ObjectMapper().readTree(json)).orElseThrow();
    }

    @Test
    void theConflictFixDoesNotRevertTheChangeOfAnotherIssue() throws CredentialMismatchException {
        moveTheBase("z.txt", "original\n");
        gitHub.addIssue(8, "Change z", "Change the file z.");
        gitHub.addLabel(8, "factory");
        agent.then(writeFile("a.txt", "agent\n")).thenApproveAllReviews().thenAssessRisk("low", true)
                .then(writeFile("a.txt", "agent and main\n")).thenApproveAllReviews().thenAssessRisk("low", true);
        IssueWorkflow workflow = createWorkflow("true");
        IssueRecord beforeMerge = advanceUntilState(workflow, queuedRecord(), "merge");
        moveTheBase("a.txt", "main\n");
        String mergedUpstream = run(clone, "rev-parse", "main");
        IssueRecord inFixLoop = workflow.advance(beforeMerge);
        // Issue 8 is merged on main, and its PREPARE fetches in the same clone: origin/main moves.
        moveTheBase("z.txt", "issue 8\n");
        workflow.advance(queuedRecord(8));

        IssueRecord fixed = workflow.advance(inFixLoop);

        assertThat(fixed.state()).isEqualTo("build");
        assertThat(run(worktree(), "rev-parse", "HEAD^")).isEqualTo(mergedUpstream);
        assertThat(run(worktree(), "diff", "--name-only", mergedUpstream + "..HEAD")).isEqualTo("a.txt");
        assertThat(run(worktree(), "show", "HEAD:z.txt")).isEqualTo("original");
        IssueRecord merged = advanceUntilState(workflow, fixed, "done");
        assertThat(merged.state()).isEqualTo("done");
        assertThat(run(worktree(), "show", "HEAD:z.txt")).isEqualTo("issue 8");
        assertThat(run(worktree(), "diff", "--name-only", "origin/main..HEAD")).isEqualTo("a.txt");
    }

    @Test
    void finishesAConflictMergeCommitAfterACrash() throws CredentialMismatchException, IOException {
        agent.then(writeFile("a.txt", "agent\n")).thenApproveAllReviews().thenAssessRisk("low", true);
        IssueWorkflow workflow = createWorkflow("true");
        IssueRecord beforeMerge = advanceUntilState(workflow, queuedRecord(), "merge");
        moveTheBase("a.txt", "main\n");
        String mergedUpstream = run(clone, "rev-parse", "main");
        IssueRecord inFixLoop = workflow.advance(beforeMerge);
        // The worker made the merge commit and stopped before the reset.
        Files.writeString(worktree().resolve("a.txt"), "agent and main\n");
        git.commitAll(worktree(), "Fix #7: Change the greeting");

        IssueRecord record = workflow.advance(inFixLoop);

        assertThat(record.state()).isEqualTo("build");
        assertThat(record.conflictedPaths()).isEmpty();
        assertThat(agent.requests()).hasSize(6);
        assertThat(run(worktree(), "rev-parse", "HEAD^")).isEqualTo(mergedUpstream);
        assertThat(run(worktree(), "rev-list", "--merges", mergedUpstream + "..HEAD")).isEmpty();
        assertThat(run(worktree(), "show", "HEAD:a.txt")).isEqualTo("agent and main");
        JsonNode finishedMerge = events.ofType("action-end").getLast();
        assertThat(finishedMerge.path("action").asText()).isEqualTo("coding");
        assertThat(finishedMerge.path("result").asText()).isEqualTo("changed");
        assertThat(finishedMerge.has("prompt")).isFalse();
    }

    private static final String LIVE_LINK = "https://claude.ai/code/session_test7";
    private static final String LIVE_COMMENT = "The live conversation is open: " + LIVE_LINK
            + "\n\nType /factory:continue in the conversation when you are done.";

    private IssueRecord blockAtTheFixLoopLimitInReview(IssueWorkflow workflow) throws CredentialMismatchException {
        for (int round = 0; round < 3; round++) {
            agent.thenChangeFile().thenApproveReview().thenRejectReview("Missing input check")
                    .thenApproveReview().thenApproveReview();
        }
        return advanceUntilWaiting(workflow, queuedRecord());
    }

    private void lockTheIndexOfTheWorktree() throws IOException {
        Path gitDirectory = Path.of(run(worktree(), "rev-parse", "--absolute-git-dir"));
        Files.writeString(gitDirectory.resolve("index.lock"), "");
    }

    private void unlockTheIndexOfTheWorktree() throws IOException {
        Path gitDirectory = Path.of(run(worktree(), "rev-parse", "--absolute-git-dir"));
        Files.delete(gitDirectory.resolve("index.lock"));
    }

    /** The file .git of a worktree names its folder in the clone; that folder has the file HEAD. */
    private static void breakTheHeadOf(Path worktree) throws IOException {
        String gitFile = Files.readString(worktree.resolve(".git")).trim();
        Path gitDirectory = worktree.resolve(gitFile.substring("gitdir: ".length()));
        Files.writeString(gitDirectory.resolve("HEAD"), "not a reference\n");
    }

    @Test
    void aQuestionOpensTheLiveConversation() throws CredentialMismatchException {
        agent.thenAskQuestion("Which greeting do you want?");

        IssueRecord record = advanceUntilWaiting(createWorkflow("true"), queuedRecord());

        assertThat(record.column()).isEqualTo("waits-for-you");
        assertThat(record.state()).isEqualTo("live");
        assertThat(record.live().flatMap(LiveSession::link)).contains(LIVE_LINK);
        assertThat(record.previousState()).contains("implement");
        assertThat(record.notes()).contains("The agent has a question.");
        assertThat(liveSessions.openCalls()).containsExactly(
                new FakeLiveSessions.OpenCall(ISSUE, record.sessionId(), worktree()));
        assertThat(gitHub.commentsOf(ISSUE)).anySatisfy(comment -> assertThat(comment.body()).isEqualTo(LIVE_COMMENT));
        assertThat(readRecordFromGitHub()).isEqualTo(record);
    }

    @Test
    void blocksWhenTheConversationDoesNotOpen() throws CredentialMismatchException {
        agent.thenAskQuestion("Which greeting do you want?");
        liveSessions.failTheNextOpen();

        IssueRecord record = advanceUntilWaiting(createWorkflow("true"), queuedRecord());

        assertThat(record.column()).isEqualTo("blocked");
        assertThat(record.state()).isEqualTo("blocked");
        assertThat(record.previousState()).contains("implement");
        assertThat(record.live()).isEmpty();
        assertThat(record.newestNote()).hasValueSatisfying(note -> assertThat(note)
                .isEqualTo("The live conversation did not open: "
                        + "The live session did not show a Remote Control link in 90 seconds."));
    }

    @Test
    void continueWithoutChangesRunsTheAgentAgainWithTheContinuePrompt() throws CredentialMismatchException {
        agent.thenAskQuestion("Which greeting do you want?")
                .thenChangeFile().thenApproveAllReviews().thenAssessRisk("low", true);
        IssueWorkflow workflow = createWorkflow("true");
        IssueRecord live = advanceUntilWaiting(workflow, queuedRecord());
        liveSessions.requestContinue(ISSUE);

        IssueRecord continued = workflow.advance(live);

        assertThat(continued.live()).isEmpty();
        assertThat(continued.afterConversation()).isTrue();
        assertThat(continued.column()).isEqualTo("in-progress");
        assertThat(continued.state()).isEqualTo("implement");
        assertThat(continued.notes()).contains(ConversationAction.NO_CHANGE_NOTE);
        assertThat(worktree().resolve(".factory-work/question.md")).doesNotExist();
        assertThat(liveSessions.closeCalls()).containsExactly(ISSUE);
        assertThat(liveSessions.isContinueRequested(ISSUE)).isFalse();

        IssueRecord implemented = workflow.advance(continued);

        PhaseRequest secondRun = agent.requests().get(1);
        assertThat(secondRun.newSession()).isFalse();
        assertThat(secondRun.sessionId()).isEqualTo(live.sessionId());
        assertThat(secondRun.prompt())
                .isEqualTo(new PromptLibrary(PromptTexts.from(BuiltInDefinition.load()), "main").continueAfterConversation());
        assertThat(implemented.afterConversation()).isFalse();
        assertThat(implemented.state()).isEqualTo("build");
        assertThat(advanceUntilWaiting(workflow, implemented).state()).isEqualTo("done");
    }

    @Test
    void continueWithChangesBuildsAndReviewsAgain() throws CredentialMismatchException, IOException {
        IssueWorkflow workflow = createWorkflow("true");
        IssueRecord waiting = waitForApprovalOfAMediumRisk(workflow);
        IssueRecord live = workflow.enter(waiting);
        Files.writeString(worktree().resolve("agreed.txt"), "agreed in the conversation\n");
        liveSessions.requestContinue(ISSUE);

        IssueRecord continued = workflow.advance(live);

        assertThat(continued.state()).isEqualTo("build");
        assertThat(continued.column()).isEqualTo("in-progress");
        assertThat(continued.reviews()).isEmpty();
        assertThat(continued.risk()).isEmpty();
        assertThat(continued.builtTreeHash()).isEmpty();
        assertThat(continued.live()).isEmpty();
        assertThat(continued.entries()).isEqualTo(waiting.entries());
        assertThat(git.hasUncommittedChanges(worktree())).isFalse();
        assertThat(run(worktree(), "log", "-1", "--format=%s")).isEqualTo("Fix #7: Change the greeting");

        agent.thenApproveAllReviews().thenAssessRisk("low", true);
        IssueRecord record = advanceUntilWaiting(workflow, continued);

        assertThat(agent.requests()).hasSize(11);
        assertThat(record.state()).isEqualTo("done");
        assertThat(gitHub.squashMergeCalls()).hasSize(1);
    }

    @Test
    void continueIsNeverAMergeApproval() throws CredentialMismatchException {
        IssueWorkflow workflow = createWorkflow("true");
        IssueRecord live = workflow.enter(waitForApprovalOfAMediumRisk(workflow));
        liveSessions.requestContinue(ISSUE);

        IssueRecord continued = workflow.advance(live);
        IssueRecord record = advanceUntilWaiting(workflow, continued);

        assertThat(continued.state()).isEqualTo("merge");
        assertThat(continued.live()).isEmpty();
        assertThat(record.column()).isEqualTo("waits-for-you");
        assertThat(record.state()).isEqualTo("waits-for-approval");
        assertThat(record.newestNote()).contains("The merge needs approval.");
        assertThat(gitHub.squashMergeCalls()).isEmpty();
    }

    @Test
    void continueStartsABlockedIssueAgain() throws CredentialMismatchException {
        IssueWorkflow workflow = createWorkflow("true");
        IssueRecord blocked = blockAtTheFixLoopLimitInReview(workflow);
        assertThat(blocked.state()).isEqualTo("blocked");
        assertThat(blocked.previousState()).contains("implement");
        IssueRecord live = workflow.enter(blocked);
        liveSessions.requestContinue(ISSUE);

        IssueRecord continued = workflow.advance(live);

        assertThat(live.state()).isEqualTo("live");
        assertThat(continued.column()).isEqualTo("in-progress");
        assertThat(continued.state()).isEqualTo("implement");
        assertThat(continued.afterConversation()).isTrue();
        assertThat(continued.entries()).isEmpty();
    }

    @Test
    void enterFromTheBlockTargetResetsTheLimitCounts() throws CredentialMismatchException {
        IssueWorkflow workflow = createWorkflow("true");
        IssueRecord blocked = blockAtTheFixLoopLimitInReview(workflow);
        int requestsBeforeEnter = agent.requests().size();
        IssueRecord live = workflow.enter(blocked);
        liveSessions.requestContinue(ISSUE);
        agent.thenChangeFile();
        IssueRecord continued = workflow.advance(live);

        IssueRecord implemented = workflow.advance(continued);

        assertThat(blocked.entriesOf("implement")).isEqualTo(3);
        assertThat(live.entries()).isEmpty();
        assertThat(implemented.state()).isEqualTo("build");
        assertThat(implemented.entriesOf("implement")).isEqualTo(1);
        assertThat(agent.requests()).hasSize(requestsBeforeEnter + 1);
    }

    @Test
    void noPhaseRunsWhileTheConversationIsOpen() throws CredentialMismatchException {
        agent.thenAskQuestion("Which greeting do you want?");
        IssueWorkflow workflow = createWorkflow("true");
        IssueRecord live = advanceUntilWaiting(workflow, queuedRecord());

        IssueRecord record = workflow.advance(live);

        assertThat(record).isEqualTo(live);
        assertThat(record.live()).isPresent();
        assertThat(agent.requests()).hasSize(1);
    }

    @Test
    void enterBeforeTheFirstImplementAddsANote() throws CredentialMismatchException {
        IssueWorkflow workflow = createWorkflow("true");
        IssueRecord prepared = workflow.advance(queuedRecord());

        IssueRecord record = workflow.enter(prepared);

        assertThat(record.notes())
                .contains("The factory opens a live conversation only after the first coding action.");
        assertThat(record.live()).isEmpty();
        assertThat(liveSessions.openCalls()).isEmpty();
        assertThat(readRecordFromGitHub()).isEqualTo(record);
    }

    @Test
    void enterOnADoneIssueAddsANote() throws CredentialMismatchException {
        agent.thenChangeFile().thenApproveAllReviews().thenAssessRisk("low", true);
        IssueWorkflow workflow = createWorkflow("true");
        IssueRecord done = advanceUntilWaiting(workflow, queuedRecord());

        IssueRecord record = workflow.enter(done);

        assertThat(record.notes()).contains("The issue is done; a live conversation has no effect.");
        assertThat(record.live()).isEmpty();
        assertThat(liveSessions.openCalls()).isEmpty();
    }

    @Test
    void aFailedPublishClosesTheNewSession() throws CredentialMismatchException {
        IssueWorkflow workflow = createWorkflow("true");
        IssueRecord waiting = waitForApprovalOfAMediumRisk(workflow);
        gitHub.failTheNextCommentEdit();

        assertThatThrownBy(() -> workflow.enter(waiting)).isInstanceOf(GitHubException.class);

        assertThat(liveSessions.isOpen(ISSUE)).isFalse();
        assertThat(readRecordFromGitHub().live()).isEmpty();
    }

    @Test
    void aFailedEndOfTheConversationBlocksTheIssueWithoutTheLiveState() throws CredentialMismatchException,
            IOException {
        IssueWorkflow workflow = createWorkflow("true");
        IssueRecord live = workflow.enter(waitForApprovalOfAMediumRisk(workflow));
        Files.writeString(worktree().resolve("agreed.txt"), "agreed in the conversation\n");
        liveSessions.requestContinue(ISSUE);
        lockTheIndexOfTheWorktree();

        IssueRecord failed = workflow.advance(live);

        assertThat(failed.state()).isEqualTo("blocked");
        assertThat(failed.previousState()).contains("merge");
        assertThat(failed.live()).isEmpty();
        assertThat(failed.newestNote()).hasValueSatisfying(note -> assertThat(note).startsWith("An action failed: "));
        assertThat(readRecordFromGitHub()).isEqualTo(failed);
        assertThat(liveSessions.isOpen(ISSUE)).isFalse();
        assertThat(liveSessions.isContinueRequested(ISSUE)).isFalse();
        unlockTheIndexOfTheWorktree();
    }

    @Test
    void aFixLoopAfterAConversationGetsTheFindings() throws CredentialMismatchException {
        agent.thenAskQuestion("Which greeting do you want?")
                .thenApproveReview().thenRejectReview("Missing input check").thenApproveReview().thenApproveReview()
                .thenChangeFile();
        IssueWorkflow workflow = createWorkflow("true");
        IssueRecord live = advanceUntilWaiting(workflow, queuedRecord());
        liveSessions.requestContinue(ISSUE);
        IssueRecord continued = workflow.advance(live);
        IssueRecord liveAgain = workflow.enter(continued);
        commitFile(worktree(), "agreed.txt", "agreed in the conversation\n", "A change of the conversation");
        liveSessions.requestContinue(ISSUE);

        IssueRecord built = workflow.advance(liveAgain);
        IssueRecord record = built;
        for (int advance = 0; advance < 10 && agent.requests().size() < 6; advance++) {
            record = workflow.advance(record);
        }

        assertThat(built.state()).isEqualTo("build");
        assertThat(built.afterConversation()).isFalse();
        assertThat(agent.requests()).hasSize(6);
        assertThat(agent.requests().get(5).prompt()).contains("Missing input check")
                .isNotEqualTo(new PromptLibrary(PromptTexts.from(BuiltInDefinition.load()), "main").continueAfterConversation());
    }

    @Test
    void aStaleApprovalDoesNotMergeAChangeOfTheConversation() throws CredentialMismatchException, IOException {
        IssueWorkflow workflow = createWorkflow("true");
        IssueRecord waiting = waitForApprovalOfAMediumRisk(workflow);
        gitHub.addLabel(ISSUE, APPROVE_LABEL);
        IssueRecord live = workflow.enter(waiting);
        Files.writeString(worktree().resolve("agreed.txt"), "agreed in the conversation\n");
        liveSessions.requestContinue(ISSUE);
        agent.thenApproveAllReviews().thenAssessRisk("medium", true);

        IssueRecord record = advanceUntilWaiting(workflow, workflow.advance(live));

        assertThat(record.column()).isEqualTo("waits-for-you");
        assertThat(record.state()).isEqualTo("waits-for-approval");
        assertThat(gitHub.labelsOf(ISSUE)).doesNotContain(APPROVE_LABEL);
        assertThat(gitHub.squashMergeCalls()).isEmpty();
    }

    @Test
    void aGitFailureAtTheOpenAfterAQuestionBlocksTheIssue() throws CredentialMismatchException {
        agent.then(request -> {
            Files.createDirectories(request.workingDirectory().resolve(".factory-work"));
            Files.writeString(request.workingDirectory().resolve(".factory-work/question.md"), "Which greeting?");
            breakTheHeadOf(request.workingDirectory());
        });

        IssueRecord record = advanceUntilWaiting(createWorkflow("true"), queuedRecord());

        assertThat(record.column()).isEqualTo("blocked");
        assertThat(record.state()).isEqualTo("blocked");
        assertThat(record.live()).isEmpty();
        assertThat(record.newestNote()).hasValueSatisfying(note -> assertThat(note)
                .startsWith("The live conversation did not open: "));
        assertThat(liveSessions.openCalls()).isEmpty();
    }

    @Test
    void aCloseFailureDoesNotHideTheFailureOfThePublish() throws CredentialMismatchException {
        IssueWorkflow workflow = createWorkflow("true");
        IssueRecord waiting = waitForApprovalOfAMediumRisk(workflow);
        gitHub.failTheNextCommentEdit();
        liveSessions.failTheNextClose();

        assertThatThrownBy(() -> workflow.enter(waiting))
                .isInstanceOf(GitHubException.class)
                .satisfies(failure -> assertThat(failure.getSuppressed()).singleElement()
                        .isInstanceOf(IllegalStateException.class));
    }

    private static final String UNKNOWN_SESSION_NOTE =
            "The factory closed a live session that the record did not know.";

    @Test
    void anActionClosesALiveSessionThatTheRecordDoesNotKnow() throws Exception {
        AtomicBoolean sessionOpenInThePhase = new AtomicBoolean(true);
        agent.then(request -> {
            sessionOpenInThePhase.set(liveSessions.isOpen(ISSUE));
            Files.writeString(request.workingDirectory().resolve("work.txt"), "change\n");
        });
        IssueWorkflow workflow = createWorkflow("true");
        IssueRecord prepared = workflow.advance(queuedRecord());
        liveSessions.open(ISSUE, prepared.sessionId(), worktree(), List.of());

        IssueRecord implemented = workflow.advance(prepared);
        IssueRecord built = workflow.advance(implemented);

        assertThat(liveSessions.closeCalls()).containsExactly(ISSUE);
        assertThat(liveSessions.isOpen(ISSUE)).isFalse();
        assertThat(sessionOpenInThePhase).isFalse();
        assertThat(agent.requests()).hasSize(1);
        assertThat(implemented.state()).isEqualTo("build");
        assertThat(built.notes()).filteredOn(UNKNOWN_SESSION_NOTE::equals).hasSize(1);
        assertThat(readRecordFromGitHub().notes()).filteredOn(UNKNOWN_SESSION_NOTE::equals).hasSize(1);
    }

    @Test
    void aFailedCloseOfAnUnknownSessionStartsNoPhase() throws Exception {
        agent.thenChangeFile();
        IssueWorkflow workflow = createWorkflow("true");
        IssueRecord prepared = workflow.advance(queuedRecord());
        liveSessions.open(ISSUE, prepared.sessionId(), worktree(), List.of());
        liveSessions.failTheNextClose();

        IssueRecord refused = workflow.advance(prepared);

        assertThat(refused).isEqualTo(prepared);
        assertThat(agent.requests()).isEmpty();
        assertThat(liveSessions.isOpen(ISSUE)).isTrue();

        IssueRecord implemented = workflow.advance(refused);

        assertThat(liveSessions.isOpen(ISSUE)).isFalse();
        assertThat(agent.requests()).hasSize(1);
        assertThat(implemented.state()).isEqualTo("build");
    }

    @Test
    void aConversationDuringAFixLoopKeepsTheFindings() throws CredentialMismatchException {
        agent.thenChangeFile()
                .thenApproveReview().thenRejectReview("Missing input check").thenApproveReview().thenApproveReview()
                .thenChangeFile();
        IssueWorkflow workflow = createWorkflow("true");
        IssueRecord record = queuedRecord();
        for (int advance = 0; advance < 10 && !record.pendingPrompt().equals(Optional.of(PendingPrompt.FIX)); advance++) {
            record = workflow.advance(record);
        }
        assertThat(record.state()).isEqualTo("implement");
        IssueRecord live = workflow.enter(record);
        liveSessions.requestContinue(ISSUE);
        IssueRecord continued = workflow.advance(live);
        assertThat(continued.afterConversation()).isTrue();

        workflow.advance(continued);

        String continuePrompt = new PromptLibrary(PromptTexts.from(BuiltInDefinition.load()), "main").continueAfterConversation();
        assertThat(agent.requests()).hasSize(6);
        assertThat(agent.requests().get(5).prompt()).startsWith(continuePrompt).contains("Missing input check");
    }

    @Test
    void countsTheEntryWhenTheActionStarts() throws CredentialMismatchException {
        agent.thenChangeFile();
        IssueWorkflow workflow = createWorkflow("true");
        IssueRecord inImplement = workflow.advance(queuedRecord());

        IssueRecord implemented = workflow.advance(inImplement);

        assertThat(inImplement.state()).isEqualTo("implement");
        assertThat(inImplement.entriesOf("implement")).isZero();
        assertThat(implemented.state()).isEqualTo("build");
        assertThat(implemented.entriesOf("implement")).isEqualTo(1);
        assertThat(implemented.entries()).doesNotContainKey("build");
    }

    @Test
    void goesToThenAboveTheLimitAndKeepsPrevious() throws CredentialMismatchException {
        IssueWorkflow workflow = createWorkflow("true");
        IssueRecord blocked = blockAtTheFixLoopLimitInReview(workflow);
        agent.thenChangeFile();
        liveSessions.requestContinue(ISSUE);

        IssueRecord continued = workflow.advance(blocked);
        IssueRecord fixed = workflow.advance(continued);

        assertThat(blocked.state()).isEqualTo("blocked");
        assertThat(blocked.previousState()).contains("implement");
        assertThat(blocked.entriesOf("implement")).isEqualTo(3);
        assertThat(blocked.newestNote()).contains("The state implement reached its limit of 3 entries.");
        assertThat(blocked.pendingPrompt()).contains(PendingPrompt.FIX);
        assertThat(continued.state()).isEqualTo("implement");
        assertThat(continued.entries()).isEmpty();
        assertThat(liveSessions.isContinueRequested(ISSUE)).isFalse();
        assertThat(fixed.state()).isEqualTo("build");
        assertThat(fixed.entriesOf("implement")).isEqualTo(1);
        assertThat(agent.requests()).hasSize(16);
        assertThat(agent.requests().get(15).prompt()).contains("Missing input check")
                .startsWith("Your earlier work has findings from the reviews.");
    }

    @Test
    void doesNotCountTheEntryWhenTheTextChanged() throws CredentialMismatchException {
        agent.thenChangeFile();
        IssueWorkflow workflow = createWorkflow("true");
        IssueRecord inImplement = workflow.advance(queuedRecord());
        gitHub.editIssue(ISSUE, "Change the greeting", "Change the greeting to hello.");
        IssueRecord waiting = advanceUntilWaiting(workflow, inImplement);
        gitHub.addLabel(ISSUE, APPROVE_LABEL);

        IssueRecord approved = workflow.advance(waiting);
        IssueRecord implemented = workflow.advance(approved);

        assertThat(waiting.state()).isEqualTo("text-changed");
        assertThat(waiting.entriesOf("implement")).isZero();
        assertThat(approved.state()).isEqualTo("implement");
        assertThat(approved.entriesOf("implement")).isZero();
        assertThat(implemented.entriesOf("implement")).isEqualTo(1);
    }

    @Test
    void waitsInTheApprovalStateUntilTheLabel() throws CredentialMismatchException {
        IssueWorkflow workflow = createWorkflow("true");
        IssueRecord waiting = waitForApprovalOfAMediumRisk(workflow);

        IssueRecord stillWaiting = workflow.advance(waiting);
        gitHub.addLabel(ISSUE, APPROVE_LABEL);
        IssueRecord approved = workflow.advance(stillWaiting);
        IssueRecord record = advanceUntilWaiting(workflow, approved);

        assertThat(waiting.state()).isEqualTo("waits-for-approval");
        assertThat(waiting.column()).isEqualTo("waits-for-you");
        assertThat(waiting.actionStarted()).isTrue();
        assertThat(waiting.newestNote()).contains("The merge needs approval.");
        assertThat(stillWaiting).isEqualTo(waiting);
        assertThat(approved.state()).isEqualTo("merge");
        assertThat(record.state()).isEqualTo("done");
        assertThat(gitHub.squashMergeCalls()).hasSize(1);
    }

    @Test
    void keepsTheApprovalLabelForTheMergeGate() throws CredentialMismatchException {
        IssueWorkflow workflow = createWorkflow("true");
        IssueRecord waiting = waitForApprovalOfAMediumRisk(workflow);
        gitHub.addLabel(ISSUE, APPROVE_LABEL);

        IssueRecord approved = workflow.advance(waiting);

        assertThat(approved.state()).isEqualTo("merge");
        assertThat(gitHub.labelsOf(ISSUE)).contains(APPROVE_LABEL);
    }

    @Test
    void acceptsTheNewTextAndRemovesTheLabel() throws CredentialMismatchException {
        IssueWorkflow workflow = createWorkflow("true");
        IssueRecord inImplement = workflow.advance(queuedRecord());
        gitHub.editIssue(ISSUE, "Change the greeting", "Change the greeting to hello.");
        IssueRecord waiting = advanceUntilWaiting(workflow, inImplement);
        gitHub.addLabel(ISSUE, APPROVE_LABEL);

        IssueRecord approved = workflow.advance(waiting);

        assertThat(approved.state()).isEqualTo("implement");
        assertThat(approved.issueTextHash()).contains(IssueText.computeHash(gitHub.issue(ISSUE)));
        assertThat(approved.pendingPrompt()).contains(PendingPrompt.IMPLEMENT);
        assertThat(gitHub.labelsOf(ISSUE)).doesNotContain(APPROVE_LABEL);
    }

    @Test
    void withdrawsAnOlderApprovalWhenTheTextChanged() throws CredentialMismatchException {
        IssueWorkflow workflow = createWorkflow("true");
        IssueRecord inImplement = workflow.advance(queuedRecord());
        gitHub.addLabel(ISSUE, APPROVE_LABEL);
        gitHub.editIssue(ISSUE, "Change the greeting", "Delete the history of the repository.");

        IssueRecord moved = workflow.advance(inImplement);
        IssueRecord started = workflow.advance(moved);
        IssueRecord stillWaiting = workflow.advance(started);

        assertThat(moved.state()).isEqualTo("text-changed");
        assertThat(moved.actionStarted()).isFalse();
        assertThat(moved.newestNote()).contains(TEXT_CHANGED_SENTENCE);
        assertThat(started.actionStarted()).isTrue();
        assertThat(gitHub.labelsOf(ISSUE)).doesNotContain(APPROVE_LABEL);
        assertThat(stillWaiting).isEqualTo(started);
        assertThat(agent.requests()).isEmpty();
    }

    @Test
    void opensTheConversationOnAQuestion() throws CredentialMismatchException {
        agent.thenAskQuestion("Which greeting do you want?");
        IssueWorkflow workflow = createWorkflow("true");
        IssueRecord inImplement = workflow.advance(queuedRecord());

        IssueRecord asked = workflow.advance(inImplement);
        IssueRecord live = workflow.advance(asked);

        assertThat(asked.state()).isEqualTo("live");
        assertThat(asked.live()).isEmpty();
        assertThat(asked.newestNote()).contains("The agent has a question.");
        assertThat(gitHub.commentsOf(ISSUE)).anySatisfy(comment -> assertThat(comment.body())
                .startsWith("The agent asks this question:").contains("Which greeting do you want?"));
        assertThat(live.live().flatMap(LiveSession::link)).contains(LIVE_LINK);
        assertThat(IssueWorkflow.isWaiting(live)).isTrue();
    }

    @Test
    void sendsChangedAfterACommitInTheConversation() throws CredentialMismatchException {
        agent.thenAskQuestion("Which greeting do you want?");
        IssueWorkflow workflow = createWorkflow("true");
        IssueRecord live = advanceUntilWaiting(workflow, queuedRecord());
        commitFile(worktree(), "agreed.txt", "agreed in the conversation\n", "A change of the conversation");
        gitHub.addLabel(ISSUE, APPROVE_LABEL);
        liveSessions.requestContinue(ISSUE);

        IssueRecord changed = workflow.advance(live);

        assertThat(changed.state()).isEqualTo("build");
        assertThat(changed.live()).isEmpty();
        assertThat(changed.afterConversation()).isFalse();
        assertThat(changed.reviews()).isEmpty();
        assertThat(changed.builtTreeHash()).isEmpty();
        assertThat(gitHub.labelsOf(ISSUE)).doesNotContain(APPROVE_LABEL);
        assertThat(liveSessions.closeCalls()).containsExactly(ISSUE);
        assertThat(liveSessions.isContinueRequested(ISSUE)).isFalse();
    }

    @Test
    void sendsContinueWithoutAChange() throws CredentialMismatchException {
        agent.thenAskQuestion("Which greeting do you want?");
        IssueWorkflow workflow = createWorkflow("true");
        IssueRecord live = advanceUntilWaiting(workflow, queuedRecord());
        liveSessions.requestContinue(ISSUE);

        IssueRecord continued = workflow.advance(live);

        assertThat(continued.state()).isEqualTo("implement");
        assertThat(continued.afterConversation()).isTrue();
        assertThat(continued.newestNote()).contains(ConversationAction.NO_CHANGE_NOTE);
        assertThat(continued.entriesOf("implement")).isEqualTo(1);
    }

    @Test
    void continuesABlockedIssueWithTheContinueButton() throws CredentialMismatchException {
        agent.thenChangeNothing().thenChangeFile();
        IssueWorkflow workflow = createWorkflow("true");
        IssueRecord blocked = advanceUntilWaiting(workflow, queuedRecord());
        IssueRecord stillBlocked = workflow.advance(blocked);
        liveSessions.requestContinue(ISSUE);

        IssueRecord continued = workflow.advance(stillBlocked);
        IssueRecord implemented = workflow.advance(continued);

        assertThat(blocked.state()).isEqualTo("blocked");
        assertThat(stillBlocked).isEqualTo(blocked.withActionStarted());
        assertThat(continued.state()).isEqualTo("implement");
        assertThat(continued.entries()).isEmpty();
        assertThat(liveSessions.isContinueRequested(ISSUE)).isFalse();
        assertThat(implemented.state()).isEqualTo("build");
    }

    @Test
    void anEnterBeforeTheFirstCodingActionKeepsTheChosenPlace() throws CredentialMismatchException {
        IssueWorkflow workflow = createWorkflow("true");
        IssueRecord inImplement = workflow.advance(queuedRecord());
        pageRequests.rememberEnterPlace(ISSUE, LivePlace.TERMINAL);

        IssueRecord record = workflow.enter(inImplement);

        assertThat(record.state()).isEqualTo("implement");
        assertThat(pageRequests.takeEnterPlace(ISSUE)).isEqualTo(LivePlace.TERMINAL);
    }

    @Test
    void anEnterOnAnOpenConversationAddsANote() throws CredentialMismatchException {
        agent.thenAskQuestion("Which greeting do you want?");
        IssueWorkflow workflow = createWorkflow("true");
        IssueRecord live = advanceUntilWaiting(workflow, queuedRecord());

        IssueRecord again = workflow.enter(live);

        assertThat(again.notes()).contains("The live conversation is already open: " + LIVE_LINK);
        assertThat(again.live()).isEqualTo(live.live());
        assertThat(liveSessions.openCalls()).hasSize(1);
    }

    @Test
    void anEnterAfterAnEndedSessionOpensItAgain() throws CredentialMismatchException {
        agent.thenAskQuestion("Which greeting do you want?");
        IssueWorkflow workflow = createWorkflow("true");
        IssueRecord live = advanceUntilWaiting(workflow, queuedRecord());
        liveSessions.endSession(ISSUE);

        IssueRecord reopened = workflow.enter(live.withoutLiveLink());

        assertThat(reopened.live().flatMap(LiveSession::link)).contains(LIVE_LINK);
        assertThat(reopened.live().map(LiveSession::startHeadCommit))
                .isEqualTo(live.live().map(LiveSession::startHeadCommit));
        assertThat(liveSessions.openCalls()).hasSize(2);
    }
}

