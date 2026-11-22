package com.meterian.factory.worker;

import static org.assertj.core.api.Assertions.assertThat;

import com.meterian.factory.board.BoardUpdates;
import com.meterian.factory.definition.BuiltInDefinition;
import com.meterian.factory.definition.ClasspathDefinitionFiles;
import com.meterian.factory.definition.DefinitionException;
import com.meterian.factory.definition.DefinitionLoader;
import com.meterian.factory.definition.FactoryDefinition;
import com.meterian.factory.definition.DefinitionLookup;
import com.meterian.factory.github.Issue;
import com.meterian.factory.ownership.LostIssues;
import com.meterian.factory.state.ActionType;
import com.meterian.factory.state.HandOver;
import com.meterian.factory.state.IssueRecord;
import com.meterian.factory.state.LivePlace;
import com.meterian.factory.state.LiveSession;
import com.meterian.factory.state.StatusComment;
import com.meterian.factory.testing.CollectedEvents;
import com.meterian.factory.testing.FakeGitHubClient;
import com.meterian.factory.testing.RecordStates;
import com.meterian.factory.workflow.MoveReport;
import com.meterian.factory.workflow.OnAnyStateMoves;
import com.meterian.factory.workflow.StatusPublisher;
import com.meterian.factory.workflow.WorkspaceLayout;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OwnershipTakeoverTest {

    private static final String WORKER_LOGIN = "factory-bot";
    private static final int ISSUE_NUMBER = 7;
    private static final String BRANCH = WorkspaceLayout.branch(ISSUE_NUMBER);
    private static final String OPEN_QUESTION_NOTE =
            "The earlier owner had an open question. The question stays on the machine of the earlier owner.";
    private static final String TAKE_OVER_NOTE = "The factory of factory-bot continues the work of an earlier owner.";

    @TempDir
    Path temporaryDirectory;

    private FakeGitHubClient gitHub;
    private OwnershipTakeover takeover;
    private Issue issue;

    @BeforeEach
    void createTakeover() {
        gitHub = new FakeGitHubClient();
        gitHub.setAuthenticatedLogin(WORKER_LOGIN);
        gitHub.addIssue(ISSUE_NUMBER, "Title", "Body");
        issue = gitHub.issue(ISSUE_NUMBER);
        DefinitionLookup definitions = DefinitionLookup.builtInOnly(BuiltInDefinition.load());
        StatusPublisher statusPublisher = new StatusPublisher(gitHub, WORKER_LOGIN, definitions, BoardUpdates.IGNORED,
                new LostIssues());
        OnAnyStateMoves onAnyStateMoves = new OnAnyStateMoves(definitions, new WorkspaceLayout(temporaryDirectory),
                CollectedEvents.discarded());
        takeover = new OwnershipTakeover(gitHub, statusPublisher, WORKER_LOGIN, definitions, onAnyStateMoves,
                new WorkerMove(new MoveReport(CollectedEvents.discarded())));
    }

    @Test
    void takeOverContinuesThePushedBranch() {
        gitHub.addBranchHead(BRANCH, "abc123");
        int pullRequestNumber = gitHub.createPullRequest(BRANCH, "main", "Title", "Body").number();

        IssueRecord record = takeOverWithoutEarlierRecord();

        assertThat(record.handOver()).contains(HandOver.CONTINUES_PUSHED_BRANCH);
        assertThat(record.pullRequestNumber()).contains(pullRequestNumber);
        assertThat(record.column()).isEqualTo("queued");
        assertThat(record.state()).isEqualTo("prepare");
        assertThat(record.notes()).contains(TAKE_OVER_NOTE);
    }

    @Test
    void takeOverWithoutBranchStartsAgain() {
        IssueRecord record = takeOverWithoutEarlierRecord();

        assertThat(record.handOver()).isEmpty();
        assertThat(record.pullRequestNumber()).isEmpty();
        assertThat(record.state()).isEqualTo("prepare");
    }

    @Test
    void takeOverKeepsABlock() {
        IssueRecord record = takeOverFrom(RecordStates.move(RecordStates.queued(ISSUE_NUMBER), "blocked"));

        assertThat(record.state()).isEqualTo("blocked");
        assertThat(record.column()).isEqualTo("blocked");
        assertThat(record.previousState()).contains("prepare");
        assertThat(record.newestNote()).contains(TAKE_OVER_NOTE);
    }

    @Test
    void takeOverKeepsAWaitForApprovalWithThePushedBranch() {
        gitHub.addBranchHead(BRANCH, "abc123");

        IssueRecord record = takeOverFrom(RecordStates.move(RecordStates.queued(ISSUE_NUMBER), "waits-for-approval"));

        assertThat(record.handOver()).contains(HandOver.CONTINUES_PUSHED_BRANCH);
        assertThat(record.state()).isEqualTo("waits-for-approval");
        assertThat(record.column()).isEqualTo("waits-for-you");
    }

    @Test
    void takeOverKeepsAWaitForTheApprovalOfAChangeOfTheFactory() {
        gitHub.addBranchHead(BRANCH, "abc123");

        IssueRecord record = takeOverFrom(
                RecordStates.move(RecordStates.queued(ISSUE_NUMBER), "factory-change-approval"));

        assertThat(record.state()).isEqualTo("factory-change-approval");
    }

    @Test
    void takeOverDropsAWaitForApprovalWithoutBranch() {
        IssueRecord record = takeOverFrom(RecordStates.move(RecordStates.queued(ISSUE_NUMBER), "waits-for-approval"));

        assertThat(record.column()).isEqualTo("queued");
        assertThat(record.state()).isEqualTo("prepare");
    }

    @Test
    void takeOverKeepsAWaitForAChangedIssueText() {
        IssueRecord record = takeOverFrom(RecordStates.move(RecordStates.queued(ISSUE_NUMBER), "text-changed"));

        assertThat(record.state()).isEqualTo("text-changed");
        assertThat(record.column()).isEqualTo("waits-for-you");
    }

    @Test
    void takeOverDoesNotKeepAQuestion() {
        gitHub.addBranchHead(BRANCH, "abc123");

        IssueRecord record = takeOverFrom(RecordStates.move(RecordStates.queued(ISSUE_NUMBER), "live"));

        assertThat(record.state()).isEqualTo("prepare");
        assertThat(record.handOver()).contains(HandOver.CONTINUES_PUSHED_BRANCH);
        assertThat(record.notes()).contains(OPEN_QUESTION_NOTE);
    }

    @Test
    void takeOverDoesNotKeepALiveConversation() {
        IssueRecord live = RecordStates.move(RecordStates.queued(ISSUE_NUMBER), "live")
                .withLive(new LiveSession("abc", Optional.of("https://claude.ai/code/session_x"), LivePlace.BROWSER));

        IssueRecord record = takeOverFrom(live);

        assertThat(record.column()).isEqualTo("queued");
        assertThat(record.state()).isEqualTo("prepare");
        assertThat(record.live()).isEmpty();
        assertThat(record.notes()).contains(OPEN_QUESTION_NOTE);
    }

    @Test
    void takeOverKeepsTheBlockOfAnOwnReleasedRecord() {
        IssueRecord record = takeOverFrom(RecordStates.move(RecordStates.queued(ISSUE_NUMBER), "blocked")
                .withHandOver(HandOver.RELEASED));

        assertThat(record.state()).isEqualTo("blocked");
        assertThat(record.handOver()).isEmpty();
    }

    @Test
    void takeOverIgnoresThePullRequestNumberOfTheEarlierRecord() {
        IssueRecord record = takeOverFrom(RecordStates.queued(ISSUE_NUMBER).withPullRequest(99));

        assertThat(record.pullRequestNumber()).isEmpty();
    }

    @Test
    void takeOverWritesANewStatusComment() {
        IssueRecord own = RecordStates.queued(ISSUE_NUMBER);
        long ownCommentId = gitHub.createComment(ISSUE_NUMBER, StatusComment.render(own, Map.of(), List.of()));

        IssueRecord record = takeOverFrom(own.withStatusComment(ownCommentId));

        assertThat(record.statusCommentId()).isPresent().get().isNotEqualTo(ownCommentId);
        assertThat(gitHub.commentsOf(ISSUE_NUMBER)).hasSize(2);
        assertThat(StatusComment.findRecord(gitHub.commentsOf(ISSUE_NUMBER), WORKER_LOGIN)).contains(record);
    }

    @Test
    void takeOverCopiesTheIssueTextHashOfTheEarlierRecord() {
        IssueRecord record = takeOverFrom(RecordStates.queued(ISSUE_NUMBER).withIssueTextHash("accepted-hash"));

        assertThat(record.issueTextHash()).contains("accepted-hash");
    }

    @Test
    void takeOverFromARecordWithoutTextHashWritesNoHash() {
        IssueRecord record = takeOverFrom(RecordStates.queued(ISSUE_NUMBER));

        assertThat(record.issueTextHash()).isEmpty();
    }

    @Test
    void takeOverDoesNotTurnAnApprovalOutsideTheMergeIntoAMergeApproval() throws DefinitionException {
        useDefinition(loadDefinitionWithADesignApproval());
        gitHub.addBranchHead(BRANCH, "abc123");
        IssueRecord earlier = RecordStates.queued(ISSUE_NUMBER)
                .withMove("design-approval", Optional.of(ActionType.APPROVAL), "waits-for-you");

        IssueRecord record = takeOverFrom(earlier);

        assertThat(record.handOver()).contains(HandOver.CONTINUES_PUSHED_BRANCH);
        assertThat(record.state()).isEqualTo("prepare");
    }

    /** The default workflow with one more approval state between prepare and build. */
    private static FactoryDefinition loadDefinitionWithADesignApproval() throws DefinitionException {
        ClasspathDefinitionFiles defaultFiles = new ClasspathDefinitionFiles();
        String mainFile = defaultFiles.read("factory.yaml").orElseThrow()
                .replace("      on: { done: implement }\n",
                        "      on: { done: design-approval }\n"
                                + "    design-approval:\n"
                                + "      column: waits-for-you\n"
                                + "      action: { type: approval }\n"
                                + "      on: { approved: build }\n");
        return DefinitionLoader.load(path -> path.equals("factory.yaml") ? Optional.of(mainFile)
                : defaultFiles.read(path));
    }

    private void useDefinition(FactoryDefinition definition) {
        DefinitionLookup definitions = DefinitionLookup.builtInOnly(definition);
        StatusPublisher statusPublisher = new StatusPublisher(gitHub, WORKER_LOGIN, definitions, BoardUpdates.IGNORED,
                new LostIssues());
        OnAnyStateMoves onAnyStateMoves = new OnAnyStateMoves(definitions, new WorkspaceLayout(temporaryDirectory),
                CollectedEvents.discarded());
        takeover = new OwnershipTakeover(gitHub, statusPublisher, WORKER_LOGIN, definitions, onAnyStateMoves,
                new WorkerMove(new MoveReport(CollectedEvents.discarded())));
    }

    private IssueRecord takeOverFrom(IssueRecord earlierRecord) {
        return takeover.takeOver(issue, RecordStates.queued(ISSUE_NUMBER), Optional.of(earlierRecord));
    }

    private IssueRecord takeOverWithoutEarlierRecord() {
        return takeover.takeOver(issue, RecordStates.queued(ISSUE_NUMBER), Optional.empty());
    }
}
