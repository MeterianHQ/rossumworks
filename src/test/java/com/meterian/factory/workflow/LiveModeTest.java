package com.meterian.factory.workflow;

import static com.meterian.factory.testing.TemporaryRepository.commitFile;
import static com.meterian.factory.testing.TemporaryRepository.createBare;
import static com.meterian.factory.testing.TemporaryRepository.createClone;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.meterian.factory.agent.LiveSessionException;
import com.meterian.factory.agent.LiveSessions;
import com.meterian.factory.git.Git;
import com.meterian.factory.git.ProcessGit;
import com.meterian.factory.github.GitHubException;
import com.meterian.factory.github.Issue;
import com.meterian.factory.state.IssueRecord;
import com.meterian.factory.state.IssueStatus;
import com.meterian.factory.state.LivePlace;
import com.meterian.factory.state.LiveSession;
import com.meterian.factory.state.WaitReason;
import com.meterian.factory.testing.FakeGitHubClient;
import com.meterian.factory.testing.FakeLiveSessions;
import com.meterian.factory.testing.RecordStates;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LiveModeTest {

    private static final int ISSUE = 7;
    private static final String LINK = "https://claude.ai/code/session_test7";
    private static final String APPROVE_LABEL = "factory:approve";

    @TempDir
    Path temporaryDirectory;

    private final Git git = new ProcessGit();
    private final FakeGitHubClient gitHub = new FakeGitHubClient();
    private final FakeLiveSessions liveSessions = new FakeLiveSessions();
    private Path worktree;
    private Issue issue;

    @BeforeEach
    void createWorktreeAndIssue() {
        Path origin = createBare(temporaryDirectory.resolve("origin.git"));
        worktree = createClone(origin, temporaryDirectory.resolve("worktree"));
        gitHub.setAuthenticatedLogin("factory-bot");
        gitHub.addIssue(ISSUE, "Change the greeting", "The greeting must change.");
        issue = gitHub.issue(ISSUE);
    }

    private final PageRequests pageRequests = new PageRequests(Clock.systemUTC());

    private LiveMode createLiveMode(LiveSessions sessions) {
        OwnerApproval ownerApproval = new OwnerApproval(gitHub, new RequestReader(gitHub, List.of("bbossola")));
        return new LiveMode(git, gitHub, sessions, ownerApproval, List.of("bbossola"), pageRequests);
    }

    private LiveMode createLiveMode() {
        return createLiveMode(liveSessions);
    }

    private static IssueRecord recordInState(String stateName) {
        return RecordStates.move(IssueRecord.queued(ISSUE).withAgentSessionStarted(), stateName)
                .withStatus(IssueStatus.IN_PROGRESS);
    }

    private static IssueRecord waitingRecord(WaitReason waitReason) {
        return recordInState("merge").withStatus(IssueStatus.WAITS_FOR_YOU).withWaitReason(waitReason);
    }

    private void writeUncommittedFile() throws IOException {
        Files.writeString(worktree.resolve("agreed.txt"), "agreed in the conversation\n");
    }

    @Test
    void enterKeepsTheStateBeforeAndPostsTheComment() {
        IssueRecord waiting = waitingRecord(WaitReason.MERGE_NEEDS_APPROVAL);

        IssueRecord live = createLiveMode().enter(waiting, worktree);

        assertThat(live.live()).contains(new LiveSession(git.headCommit(worktree), IssueStatus.WAITS_FOR_YOU,
                Optional.of(WaitReason.MERGE_NEEDS_APPROVAL), Optional.of(LINK), LivePlace.PHONE));
        assertThat(live.status()).isEqualTo(IssueStatus.WAITS_FOR_YOU);
        assertThat(live.waitReason()).contains(WaitReason.LIVE_CONVERSATION);
        assertThat(gitHub.commentsOf(ISSUE)).singleElement().satisfies(comment -> assertThat(comment.body())
                .isEqualTo("The live conversation is open: " + LINK
                        + "\n\nType /factory:continue in the conversation when you are done.\n\n@bbossola"));
    }

    @Test
    void anEnterWithoutAPageRequestIsPhoneWithTheMention() {
        IssueRecord live = createLiveMode().enter(recordInState("review"), worktree);

        assertThat(live.live().orElseThrow().place()).isEqualTo(LivePlace.PHONE);
        assertThat(gitHub.commentsOf(ISSUE)).singleElement()
                .satisfies(comment -> assertThat(comment.body()).contains("@bbossola"));
        assertThat(liveSessions.openedTerminals()).isEmpty();
    }

    @Test
    void anEnterInTheTerminalOpensTheTerminalWithoutTheMention() {
        pageRequests.rememberEnterPlace(ISSUE, LivePlace.TERMINAL);

        IssueRecord live = createLiveMode().enter(recordInState("review"), worktree);

        assertThat(live.live().orElseThrow().place()).isEqualTo(LivePlace.TERMINAL);
        assertThat(gitHub.commentsOf(ISSUE)).singleElement().satisfies(comment -> assertThat(comment.body())
                .doesNotContain("@").endsWith("when you are done."));
        assertThat(liveSessions.openedTerminals()).containsExactly(ISSUE);
    }

    @Test
    void anEnterInTheBrowserHasNoMention() {
        pageRequests.rememberEnterPlace(ISSUE, LivePlace.BROWSER);

        IssueRecord live = createLiveMode().enter(recordInState("review"), worktree);

        assertThat(live.live().orElseThrow().place()).isEqualTo(LivePlace.BROWSER);
        assertThat(gitHub.commentsOf(ISSUE)).singleElement()
                .satisfies(comment -> assertThat(comment.body()).doesNotContain("@"));
        assertThat(liveSessions.openedTerminals()).isEmpty();
    }

    @Test
    void aTerminalThatDoesNotOpenKeepsTheSession() {
        pageRequests.rememberEnterPlace(ISSUE, LivePlace.TERMINAL);
        liveSessions.failOpenTerminalWith(new LiveSessionException("no display"));

        IssueRecord live = createLiveMode().enter(recordInState("review"), worktree);

        assertThat(live.live().flatMap(LiveSession::link)).contains(LINK);
        assertThat(live.waitReason()).contains(WaitReason.LIVE_CONVERSATION);
        assertThat(live.notes())
                .contains("The terminal window did not open: no display Use the Remote Control link.");
    }

    @Test
    void aTerminalThatDoesNotOpenWritesAWarning() {
        pageRequests.rememberEnterPlace(ISSUE, LivePlace.TERMINAL);
        liveSessions.failOpenTerminalWith(new LiveSessionException("no display"));
        List<String> messages = new ArrayList<>();
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord logRecord) {
                messages.add(new SimpleFormatter().formatMessage(logRecord));
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        Logger logger = Logger.getLogger(LiveMode.class.getName());
        logger.addHandler(handler);
        try {
            createLiveMode().enter(recordInState("review"), worktree);
        } finally {
            logger.removeHandler(handler);
        }

        assertThat(messages).contains("The terminal window of the issue #" + ISSUE + " did not open: no display");
    }

    @Test
    void aRefusedEnterKeepsTheChosenPlace() {
        pageRequests.rememberEnterPlace(ISSUE, LivePlace.TERMINAL);

        createLiveMode().enter(IssueRecord.queued(ISSUE), worktree);

        assertThat(pageRequests.takeEnterPlace(ISSUE)).isEqualTo(LivePlace.TERMINAL);
    }

    @Test
    void enterOnAnOpenSessionAddsANote() {
        LiveMode liveMode = createLiveMode();
        IssueRecord live = liveMode.enter(recordInState("review"), worktree);

        IssueRecord again = liveMode.enter(live, worktree);

        assertThat(again.notes()).contains("The live conversation is already open: " + LINK);
        assertThat(again.live()).isEqualTo(live.live());
        assertThat(liveSessions.openCalls()).hasSize(1);
        assertThat(gitHub.commentsOf(ISSUE)).hasSize(1);
    }

    @Test
    void enterAfterAnEndedSessionKeepsTheStateBefore() {
        LiveMode liveMode = createLiveMode();
        IssueRecord live = liveMode.enter(waitingRecord(WaitReason.MERGE_NEEDS_APPROVAL), worktree);
        liveSessions.endSession(ISSUE);
        commitFile(worktree, "b.txt", "changed in the first conversation\n", "A change");

        IssueRecord reopened = liveMode.enter(live.withoutLiveLink(), worktree);

        assertThat(reopened.live()).isEqualTo(live.live());
        assertThat(liveSessions.openCalls()).hasSize(2);
        assertThat(liveSessions.isOpen(ISSUE)).isTrue();
    }

    @Test
    void aFailedOpenAddsANoteAndKeepsTheRecord() {
        liveSessions.failTheNextOpen();
        IssueRecord waiting = waitingRecord(WaitReason.QUESTION);

        IssueRecord record = createLiveMode().enter(waiting, worktree);

        assertThat(record).isEqualTo(waiting.withNote("The live conversation did not open:"
                + " The live session did not show a Remote Control link in 90 seconds."));
        assertThat(gitHub.commentsOf(ISSUE)).isEmpty();
    }

    @Test
    void theLinkInTheCommentIsOneSafeLine() {
        LiveMode liveMode = createLiveMode(new ForgedLinkSessions(liveSessions, "https://example.com\n<!-- x -->"));

        liveMode.enter(recordInState("review"), worktree);

        assertThat(gitHub.commentsOf(ISSUE)).singleElement().satisfies(comment -> assertThat(comment.body())
                .startsWith("The live conversation is open: https://example.com <! -- x -- >\n\n")
                .doesNotContain("<!--"));
    }

    @Test
    void continueAfterACommitInImplementBuildsAgain() {
        LiveMode liveMode = createLiveMode();
        IssueRecord live = liveMode.enter(recordInState("implement"), worktree);
        commitFile(worktree, "b.txt", "changed in the conversation\n", "A change");

        IssueRecord continued = liveMode.continueWork(issue, live, worktree);

        assertThat(continued.state()).isEqualTo("build");
        assertThat(continued.status()).isEqualTo(IssueStatus.IN_PROGRESS);
        assertThat(continued.afterConversation()).isFalse();
        assertThat(continued.live()).isEmpty();
    }

    /** The agent of the implement step left work that is not committed: the implement step commits it later. */
    @Test
    void continueInImplementWithWorkThatIsNotCommittedRunsTheAgentAgain() throws IOException {
        LiveMode liveMode = createLiveMode();
        writeUncommittedFile();
        IssueRecord live = liveMode.enter(RecordStates.move(waitingRecord(WaitReason.QUESTION), "implement"),
                worktree);

        IssueRecord continued = liveMode.continueWork(issue, live, worktree);

        assertThat(continued.state()).isEqualTo("implement");
        assertThat(continued.afterConversation()).isTrue();
        assertThat(continued.status()).isEqualTo(IssueStatus.IN_PROGRESS);
        assertThat(continued.waitReason()).isEmpty();
        assertThat(git.hasUncommittedChanges(worktree)).isTrue();
    }

    @Test
    void continueWithChangesWithdrawsTheApproval() throws IOException {
        gitHub.addLabelBy(ISSUE, APPROVE_LABEL, "bbossola");
        LiveMode liveMode = createLiveMode();
        IssueRecord live = liveMode.enter(waitingRecord(WaitReason.MERGE_NEEDS_APPROVAL), worktree);
        writeUncommittedFile();

        IssueRecord continued = liveMode.continueWork(issue, live, worktree);

        assertThat(continued.state()).isEqualTo("build");
        assertThat(gitHub.removedLabels()).contains(APPROVE_LABEL);
        assertThat(git.hasUncommittedChanges(worktree)).isFalse();
    }

    @Test
    void continueRestoresTheWaitForAChangedIssueText() {
        LiveMode liveMode = createLiveMode();
        IssueRecord live = liveMode.enter(waitingRecord(WaitReason.ISSUE_TEXT_CHANGED), worktree);

        IssueRecord continued = liveMode.continueWork(issue, live, worktree);

        assertThat(continued.status()).isEqualTo(IssueStatus.WAITS_FOR_YOU);
        assertThat(continued.waitReason()).contains(WaitReason.ISSUE_TEXT_CHANGED);
        assertThat(continued.live()).isEmpty();
        assertThat(continued.notes()).containsExactly(LiveMode.NO_CHANGE_NOTE);
    }

    @Test
    void continueWithoutChangesTellsThatTheMergeStillNeedsTheApproval() {
        LiveMode liveMode = createLiveMode();
        IssueRecord live = liveMode.enter(waitingRecord(WaitReason.MERGE_NEEDS_APPROVAL), worktree);

        IssueRecord continued = liveMode.continueWork(issue, live, worktree);

        assertThat(continued.status()).isEqualTo(IssueStatus.WAITS_FOR_YOU);
        assertThat(continued.waitReason()).contains(WaitReason.MERGE_NEEDS_APPROVAL);
        assertThat(continued.notes()).containsExactly(
                "The live conversation ended with no change to the code. The issue waits for you again.");
    }

    @Test
    void aSecondConversationWithoutChangesMakesTheNoteTheNewestNoteAgain() {
        LiveMode liveMode = createLiveMode();
        IssueRecord firstLive = liveMode.enter(waitingRecord(WaitReason.MERGE_NEEDS_APPROVAL), worktree);
        IssueRecord afterFirst = liveMode.continueWork(issue, firstLive, worktree).withNote("Another note.");
        IssueRecord secondLive = liveMode.enter(afterFirst, worktree);

        IssueRecord continued = liveMode.continueWork(issue, secondLive, worktree);

        assertThat(continued.notes()).containsExactly("Another note.", LiveMode.NO_CHANGE_NOTE);
    }

    @Test
    void continueInBuildWithoutChangesGoesBackToTheBuild() {
        LiveMode liveMode = createLiveMode();
        IssueRecord live = liveMode.enter(recordInState("build"), worktree);

        IssueRecord continued = liveMode.continueWork(issue, live, worktree);

        assertThat(continued.state()).isEqualTo("build");
        assertThat(continued.status()).isEqualTo(IssueStatus.IN_PROGRESS);
        assertThat(continued.waitReason()).isEmpty();
        assertThat(continued.afterConversation()).isFalse();
    }

    @Test
    void aGitFailureBeforeTheOpenKeepsTheQuestionWaiting() throws IOException {
        Path folderWithoutGit = Files.createDirectories(temporaryDirectory.resolve("no-git"));
        IssueRecord waiting = RecordStates.move(waitingRecord(WaitReason.QUESTION), "implement");

        IssueRecord record = createLiveMode().enter(waiting, folderWithoutGit);

        assertThat(record.status()).isEqualTo(IssueStatus.WAITS_FOR_YOU);
        assertThat(record.waitReason()).contains(WaitReason.QUESTION);
        assertThat(record.live()).isEmpty();
        assertThat(record.notes()).singleElement()
                .satisfies(note -> assertThat(note).startsWith("The live conversation did not open: "));
        assertThat(liveSessions.openCalls()).isEmpty();
    }

    @Test
    void aCloseFailureDoesNotHideTheFailureOfTheComment() {
        gitHub.failTheNextCommentCreate();
        liveSessions.failTheNextClose();

        assertThatThrownBy(() -> createLiveMode().enter(recordInState("review"), worktree))
                .isInstanceOf(GitHubException.class)
                .satisfies(failure -> assertThat(failure.getSuppressed()).singleElement()
                        .isInstanceOf(IllegalStateException.class));
        assertThat(liveSessions.closeCalls()).containsExactly(ISSUE);
    }

    /** Live sessions that show a forged link, as a confused screen of tmux can do. */
    private record ForgedLinkSessions(FakeLiveSessions delegate, String link) implements LiveSessions {

        @Override
        public String open(int issueNumber, UUID sessionId, Path worktree) throws LiveSessionException {
            delegate.open(issueNumber, sessionId, worktree);
            return link;
        }

        @Override
        public boolean isOpen(int issueNumber) {
            return delegate.isOpen(issueNumber);
        }

        @Override
        public void close(int issueNumber) {
            delegate.close(issueNumber);
        }

        @Override
        public void openTerminal(int issueNumber) throws LiveSessionException {
            delegate.openTerminal(issueNumber);
        }

        @Override
        public boolean isContinueRequested(int issueNumber) {
            return delegate.isContinueRequested(issueNumber);
        }

        @Override
        public void clearContinueRequest(int issueNumber) {
            delegate.clearContinueRequest(issueNumber);
        }

        @Override
        public void requestContinue(int issueNumber) {
            delegate.requestContinue(issueNumber);
        }
    }
}
