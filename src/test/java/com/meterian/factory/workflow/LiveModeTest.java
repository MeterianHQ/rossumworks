package com.meterian.factory.workflow;

import static com.meterian.factory.testing.TemporaryRepository.commitFile;
import static com.meterian.factory.testing.TemporaryRepository.createBare;
import static com.meterian.factory.testing.TemporaryRepository.createClone;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.meterian.factory.agent.LiveSessionException;
import com.meterian.factory.agent.LiveSessions;
import com.meterian.factory.git.Git;
import com.meterian.factory.git.GitException;
import com.meterian.factory.git.ProcessGit;
import com.meterian.factory.github.GitHubException;
import com.meterian.factory.state.IssueRecord;
import com.meterian.factory.state.LivePlace;
import com.meterian.factory.state.LiveSession;
import com.meterian.factory.testing.FakeGitHubClient;
import com.meterian.factory.testing.FakeLiveSessions;
import com.meterian.factory.testing.RecordStates;
import com.meterian.factory.testing.TestTools;
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

    @TempDir
    Path temporaryDirectory;

    private final Git git = new ProcessGit();
    private final FakeGitHubClient gitHub = new FakeGitHubClient();
    private final FakeLiveSessions liveSessions = new FakeLiveSessions();
    private Path worktree;

    @BeforeEach
    void createWorktreeAndIssue() {
        Path origin = createBare(temporaryDirectory.resolve("origin.git"));
        worktree = createClone(origin, temporaryDirectory.resolve("worktree"));
        gitHub.setAuthenticatedLogin("factory-bot");
        gitHub.addIssue(ISSUE, "Change the greeting", "The greeting must change.");
    }

    private final PageRequests pageRequests = new PageRequests(Clock.systemUTC());

    private LiveMode createLiveMode(LiveSessions sessions) {
        return new LiveMode(git, gitHub, sessions, pageRequests);
    }

    private LiveMode createLiveMode() {
        return createLiveMode(liveSessions);
    }

    private static IssueRecord recordInState(String stateName) {
        return RecordStates.move(RecordStates.queued(ISSUE).withAgentSessionStarted(), stateName);
    }

    private IssueRecord open(LiveMode liveMode, IssueRecord record) throws LiveSessionException {
        return liveMode.open(record, worktree, TestTools.ALLOWED_TOOLS);
    }

    private IssueRecord open(IssueRecord record) throws LiveSessionException {
        return open(createLiveMode(), record);
    }

    private void writeUncommittedFile() throws IOException {
        Files.writeString(worktree.resolve("agreed.txt"), "agreed in the conversation\n");
    }

    @Test
    void openKeepsTheHeadCommitAndPostsTheComment() throws Exception {
        IssueRecord live = open(recordInState("live"));

        assertThat(live.live()).contains(new LiveSession(git.headCommit(worktree), Optional.of(LINK), LivePlace.PHONE));
        assertThat(live.state()).isEqualTo("live");
        assertThat(gitHub.commentsOf(ISSUE)).singleElement().satisfies(comment -> assertThat(comment.body())
                .isEqualTo("The live conversation is open: " + LINK
                        + "\n\nType /factory:continue in the conversation when you are done."));
    }

    @Test
    void anOpenAfterAFailedOpenAddsTheNewestNote() throws Exception {
        IssueRecord failed = recordInState("live").withNote("The live conversation did not open: no link");

        IssueRecord live = open(failed);

        assertThat(live.notes()).last().isEqualTo("The live conversation is open.");
    }

    @Test
    void aTerminalThatDoesNotOpenGivesTheNewestNote() throws Exception {
        pageRequests.rememberEnterPlace(ISSUE, LivePlace.TERMINAL);
        liveSessions.failOpenTerminalWith(new LiveSessionException("no display"));

        IssueRecord live = open(recordInState("live"));

        assertThat(live.notes()).containsExactly("The live conversation is open.",
                "The terminal window did not open: no display Use the Remote Control link.");
    }

    @Test
    void anOpenWithoutAPageRequestIsPhoneWithNoMention() throws Exception {
        IssueRecord live = open(recordInState("live"));

        assertThat(live.live().orElseThrow().place()).isEqualTo(LivePlace.PHONE);
        assertThat(gitHub.commentsOf(ISSUE)).singleElement()
                .satisfies(comment -> assertThat(comment.body()).doesNotContain("@"));
        assertThat(liveSessions.openedTerminals()).isEmpty();
    }

    @Test
    void anOpenInTheTerminalOpensTheTerminalWithoutTheMention() throws Exception {
        pageRequests.rememberEnterPlace(ISSUE, LivePlace.TERMINAL);

        IssueRecord live = open(recordInState("live"));

        assertThat(live.live().orElseThrow().place()).isEqualTo(LivePlace.TERMINAL);
        assertThat(gitHub.commentsOf(ISSUE)).singleElement().satisfies(comment -> assertThat(comment.body())
                .doesNotContain("@").endsWith("when you are done."));
        assertThat(liveSessions.openedTerminals()).containsExactly(ISSUE);
    }

    @Test
    void anOpenInTheBrowserHasNoMention() throws Exception {
        pageRequests.rememberEnterPlace(ISSUE, LivePlace.BROWSER);

        IssueRecord live = open(recordInState("live"));

        assertThat(live.live().orElseThrow().place()).isEqualTo(LivePlace.BROWSER);
        assertThat(gitHub.commentsOf(ISSUE)).singleElement()
                .satisfies(comment -> assertThat(comment.body()).doesNotContain("@"));
        assertThat(liveSessions.openedTerminals()).isEmpty();
    }

    @Test
    void aTerminalThatDoesNotOpenKeepsTheSession() throws Exception {
        pageRequests.rememberEnterPlace(ISSUE, LivePlace.TERMINAL);
        liveSessions.failOpenTerminalWith(new LiveSessionException("no display"));

        IssueRecord live = open(recordInState("live"));

        assertThat(live.live().flatMap(LiveSession::link)).contains(LINK);
        assertThat(live.notes())
                .contains("The terminal window did not open: no display Use the Remote Control link.");
    }

    @Test
    void aTerminalThatDoesNotOpenWritesAWarning() throws Exception {
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
            open(recordInState("live"));
        } finally {
            logger.removeHandler(handler);
        }

        assertThat(messages).contains("The terminal window of the issue #" + ISSUE + " did not open: no display");
    }

    @Test
    void findsTheOpenLinkOnlyWhileTheSessionIsOpen() throws Exception {
        LiveMode liveMode = createLiveMode();
        IssueRecord live = open(liveMode, recordInState("live"));

        assertThat(liveMode.findOpenLink(live)).contains(LINK);
        liveSessions.endSession(ISSUE);
        assertThat(liveMode.findOpenLink(live)).isEmpty();
        assertThat(liveMode.findOpenLink(recordInState("live"))).isEmpty();
    }

    @Test
    void anOpenAfterAnEndedSessionKeepsTheHeadCommitOfTheStart() throws Exception {
        LiveMode liveMode = createLiveMode();
        IssueRecord live = open(liveMode, recordInState("live"));
        liveSessions.endSession(ISSUE);
        commitFile(worktree, "b.txt", "changed in the first conversation\n", "A change");

        IssueRecord reopened = open(liveMode, live.withoutLiveLink());

        assertThat(reopened.live()).isEqualTo(live.live());
        assertThat(liveSessions.openCalls()).hasSize(2);
        assertThat(liveSessions.isOpen(ISSUE)).isTrue();
    }

    @Test
    void aFailedOpenThrowsAndPostsNoComment() {
        liveSessions.failTheNextOpen();

        assertThatThrownBy(() -> open(recordInState("live")))
                .isInstanceOf(LiveSessionException.class)
                .hasMessage("The live session did not show a Remote Control link in 90 seconds.");
        assertThat(gitHub.commentsOf(ISSUE)).isEmpty();
    }

    @Test
    void theLinkInTheCommentIsOneSafeLine() throws Exception {
        LiveMode liveMode = createLiveMode(new ForgedLinkSessions(liveSessions, "https://example.com\n<!-- x -->"));

        open(liveMode, recordInState("live"));

        assertThat(gitHub.commentsOf(ISSUE)).singleElement().satisfies(comment -> assertThat(comment.body())
                .startsWith("The live conversation is open: https://example.com <! -- x -- >\n\n")
                .doesNotContain("<!--"));
    }

    @Test
    void aCommitAfterACodingActionIsAChange() throws Exception {
        LiveMode liveMode = createLiveMode();
        IssueRecord live = open(liveMode, recordInState("live"));
        commitFile(worktree, "b.txt", "changed in the conversation\n", "A change");

        assertThat(liveMode.hasChangedInLiveMode(live.live().orElseThrow(), true, worktree)).isTrue();
    }

    /** The agent of the coding action left work that is not committed: the coding action commits it later. */
    @Test
    void workThatIsNotCommittedAfterACodingActionIsNoChange() throws Exception {
        LiveMode liveMode = createLiveMode();
        writeUncommittedFile();
        IssueRecord live = open(liveMode, recordInState("live"));

        assertThat(liveMode.hasChangedInLiveMode(live.live().orElseThrow(), true, worktree)).isFalse();
        assertThat(git.hasUncommittedChanges(worktree)).isTrue();
    }

    @Test
    void workThatIsNotCommittedAfterAnotherActionIsAChange() throws Exception {
        LiveMode liveMode = createLiveMode();
        IssueRecord live = open(liveMode, recordInState("live"));
        writeUncommittedFile();

        assertThat(liveMode.hasChangedInLiveMode(live.live().orElseThrow(), false, worktree)).isTrue();
    }

    @Test
    void noWorkIsNoChange() throws Exception {
        LiveMode liveMode = createLiveMode();
        IssueRecord live = open(liveMode, recordInState("live"));

        assertThat(liveMode.hasChangedInLiveMode(live.live().orElseThrow(), false, worktree)).isFalse();
    }

    @Test
    void closeEndsTheSessionAndDeletesTheQuestion() throws Exception {
        LiveMode liveMode = createLiveMode();
        IssueRecord live = open(liveMode, recordInState("live"));
        Path question = worktree.resolve(ScratchFolder.NAME).resolve("question.md");
        Files.createDirectories(question.getParent());
        Files.writeString(question, "Which greeting?");

        liveMode.close(live, worktree);

        assertThat(liveSessions.closeCalls()).containsExactly(ISSUE);
        assertThat(question).doesNotExist();
    }

    @Test
    void aGitFailureBeforeTheOpenOpensNoSession() throws IOException {
        Path folderWithoutGit = Files.createDirectories(temporaryDirectory.resolve("no-git"));

        assertThatThrownBy(() -> createLiveMode().open(recordInState("live"), folderWithoutGit,
                TestTools.ALLOWED_TOOLS)).isInstanceOf(GitException.class);
        assertThat(liveSessions.openCalls()).isEmpty();
    }

    @Test
    void aCloseFailureDoesNotHideTheFailureOfTheComment() {
        gitHub.failTheNextCommentCreate();
        liveSessions.failTheNextClose();

        assertThatThrownBy(() -> open(recordInState("live")))
                .isInstanceOf(GitHubException.class)
                .satisfies(failure -> assertThat(failure.getSuppressed()).singleElement()
                        .isInstanceOf(IllegalStateException.class));
        assertThat(liveSessions.closeCalls()).containsExactly(ISSUE);
    }

    /** Live sessions that show a forged link, as a confused screen of tmux can do. */
    private record ForgedLinkSessions(FakeLiveSessions delegate, String link) implements LiveSessions {

        @Override
        public String open(int issueNumber, UUID sessionId, Path worktree, List<String> allowedTools)
                throws LiveSessionException {
            delegate.open(issueNumber, sessionId, worktree, allowedTools);
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
