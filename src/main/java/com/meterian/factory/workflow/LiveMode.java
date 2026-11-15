package com.meterian.factory.workflow;

import com.meterian.factory.agent.LiveSessionException;
import com.meterian.factory.agent.LiveSessions;
import com.meterian.factory.git.Git;
import com.meterian.factory.git.GitException;
import com.meterian.factory.github.GitHubClient;
import com.meterian.factory.state.IssueRecord;
import com.meterian.factory.state.LivePlace;
import com.meterian.factory.state.LiveSession;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/** Opens and closes the live conversation of an issue: the tmux session, its comment, and its place. */
final class LiveMode {

    private static final System.Logger LOGGER = System.getLogger(LiveMode.class.getName());
    static final String OPEN_NOTE = "The live conversation is open.";
    private static final String TERMINAL_NOT_OPEN_NOTE = "The terminal window did not open: ";

    private final Git git;
    private final GitHubClient gitHub;
    private final LiveSessions liveSessions;
    private final PageRequests pageRequests;

    LiveMode(Git git, GitHubClient gitHub, LiveSessions liveSessions, PageRequests pageRequests) {
        this.git = git;
        this.gitHub = gitHub;
        this.liveSessions = liveSessions;
        this.pageRequests = pageRequests;
    }

    /**
     * A record that already has a live state keeps its start head commit: a new session never replaces it. The
     * note of the open is the newest note, so that the card does not show the note of an earlier failed open.
     *
     * @param allowedTools the allowed tools of the definition of the issue
     */
    IssueRecord open(IssueRecord record, Path worktree, List<String> allowedTools)
            throws LiveSessionException, GitException {
        // The head commit is read before the open: a failure of git never leaves a session that is open.
        LiveSession before = record.live().orElseGet(
                () -> new LiveSession(git.headCommit(worktree), Optional.empty(), LivePlace.GITHUB_COMMENT));
        LivePlace place = pageRequests.takeEnterPlace(record.issueNumber());
        String link = liveSessions.open(record.issueNumber(), record.sessionId(), worktree, allowedTools);
        postOpenComment(record.issueNumber(), link);
        IssueRecord opened = record.withLive(before.withLink(link).withPlace(place)).withNote(OPEN_NOTE);
        return place == LivePlace.TERMINAL ? openTerminal(opened) : opened;
    }

    Optional<String> findOpenLink(IssueRecord record) {
        return record.live().flatMap(LiveSession::link).filter(link -> liveSessions.isOpen(record.issueNumber()));
    }

    boolean isContinueRequested(IssueRecord record) {
        return liveSessions.isContinueRequested(record.issueNumber());
    }

    /**
     * The live conversation answers the question, so the merge gate does not see an open question. The publisher
     * clears the continue request after it writes the record: a failure after this call runs it again.
     */
    void close(IssueRecord record, Path worktree) {
        liveSessions.close(record.issueNumber());
        new FactoryFolder(worktree).deleteQuestion();
    }

    /**
     * The agent of a coding action leaves its work not committed, also before a question. So after a coding action
     * only a new commit is a change of the conversation; the coding action commits the rest after the next run.
     *
     * @param afterCodingAction true when the action of previous is coding
     */
    boolean hasChangedInLiveMode(LiveSession live, boolean afterCodingAction, Path worktree) {
        if (!git.headCommit(worktree).equals(live.startHeadCommit())) {
            return true;
        }
        return !afterCodingAction && git.hasUncommittedChanges(worktree);
    }

    /** The session stays open when the window does not open: the owner uses the link. */
    private IssueRecord openTerminal(IssueRecord opened) {
        try {
            liveSessions.openTerminal(opened.issueNumber());
            return opened;
        } catch (LiveSessionException exception) {
            LOGGER.log(System.Logger.Level.WARNING, "The terminal window of the issue #{0} did not open: {1}",
                    opened.issueNumber(), exception.getMessage());
            return opened.withNote(TERMINAL_NOT_OPEN_NOTE + exception.getMessage() + " Use the Remote Control link.");
        }
    }

    /** The record must never know less than tmux: when the comment fails, the session closes. */
    private void postOpenComment(int issueNumber, String link) {
        try {
            gitHub.createComment(issueNumber, LiveComment.render(link));
        } catch (RuntimeException exception) {
            LiveSessionClosing.closeAfterAFailure(liveSessions, issueNumber, exception);
            throw exception;
        }
    }
}
