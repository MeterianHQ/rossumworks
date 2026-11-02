package com.meterian.factory.workflow;

import com.meterian.factory.agent.LiveSessionException;
import com.meterian.factory.agent.LiveSessions;
import com.meterian.factory.git.Git;
import com.meterian.factory.git.GitException;
import com.meterian.factory.github.GitHubClient;
import com.meterian.factory.github.Issue;
import com.meterian.factory.state.IssueRecord;
import com.meterian.factory.state.IssueStatus;
import com.meterian.factory.state.LivePlace;
import com.meterian.factory.state.LiveSession;
import com.meterian.factory.state.Phase;
import com.meterian.factory.state.StatusComment;
import com.meterian.factory.state.WaitReason;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Opens the live conversation of an issue, and chooses the next step after /factory:continue. */
final class LiveMode {

    private static final String TOO_EARLY_NOTE =
            "The factory opens a live conversation only after the first implement step.";
    private static final String DONE_NOTE = "The issue is done; a live conversation has no effect.";
    private static final String ALREADY_OPEN_NOTE = "The live conversation is already open: ";
    private static final String NOT_OPEN_NOTE = "The live conversation did not open: ";
    /** Without this note, the card after the conversation looks the same as before Enter. */
    static final String NO_CHANGE_NOTE =
            "The live conversation ended with no change to the code. The issue waits for you again.";
    private static final Set<WaitReason> APPROVAL_WAITS =
            EnumSet.of(WaitReason.MERGE_NEEDS_APPROVAL, WaitReason.ISSUE_TEXT_CHANGED);

    private final Git git;
    private final GitHubClient gitHub;
    private final LiveSessions liveSessions;
    private final OwnerApproval ownerApproval;
    private final List<String> allowedAccounts;

    LiveMode(Git git, GitHubClient gitHub, LiveSessions liveSessions, OwnerApproval ownerApproval,
             List<String> allowedAccounts) {
        this.git = git;
        this.gitHub = gitHub;
        this.liveSessions = liveSessions;
        this.ownerApproval = ownerApproval;
        this.allowedAccounts = List.copyOf(allowedAccounts);
    }

    /** A record that already has a live state keeps its state before: a new session never replaces it. */
    IssueRecord enter(IssueRecord record, Path worktree) {
        if (record.phase() == Phase.DONE) {
            return record.withNote(DONE_NOTE);
        }
        if (!record.agentSessionStarted() || !Files.isDirectory(worktree)) {
            return record.withNote(TOO_EARLY_NOTE);
        }
        Optional<String> openLink = findOpenLink(record);
        if (openLink.isPresent()) {
            return record.withNote(ALREADY_OPEN_NOTE + openLink.get());
        }
        try {
            // The head commit is read before the open: a failure of git never leaves a session that is open.
            LiveSession stateBefore = record.live().orElseGet(() -> captureStateBefore(record, worktree));
            String link = liveSessions.open(record.issueNumber(), record.sessionId(), worktree);
            postOpenComment(record.issueNumber(), link);
            return record.withLive(stateBefore.withLink(link))
                    .withStatus(IssueStatus.WAITS_FOR_YOU).withWaitReason(WaitReason.LIVE_CONVERSATION);
        } catch (LiveSessionException | GitException exception) {
            return record.withNote(NOT_OPEN_NOTE + exception.getMessage());
        }
    }

    boolean isContinueRequested(IssueRecord record) {
        return liveSessions.isContinueRequested(record.issueNumber());
    }

    /** The publisher clears the continue request after it writes the record: a failure here runs this again. */
    IssueRecord continueWork(Issue issue, IssueRecord record, Path worktree) {
        LiveSession live = record.live().orElseThrow();
        liveSessions.close(record.issueNumber());
        new FactoryFolder(worktree).deleteQuestion();
        return chooseNextStep(issue, record, live, worktree).withoutLive();
    }

    private Optional<String> findOpenLink(IssueRecord record) {
        return record.live().flatMap(LiveSession::link).filter(link -> liveSessions.isOpen(record.issueNumber()));
    }

    private LiveSession captureStateBefore(IssueRecord record, Path worktree) {
        return new LiveSession(git.headCommit(worktree), record.status(), record.waitReason(), Optional.empty(),
                LivePlace.PHONE);
    }

    /** The record must never know less than tmux: when the comment fails, the session closes. */
    private void postOpenComment(int issueNumber, String link) {
        try {
            gitHub.createComment(issueNumber, renderOpenComment(link));
        } catch (RuntimeException exception) {
            LiveSessionClosing.closeAfterAFailure(liveSessions, issueNumber, exception);
            throw exception;
        }
    }

    private String renderOpenComment(String link) {
        String mention = allowedAccounts.stream().findFirst().map(login -> "@" + login).orElse("");
        return "The live conversation is open: " + StatusComment.sanitizeLine(link)
                + "\n\nType /factory:continue in the conversation when you are done.\n\n" + mention;
    }

    private IssueRecord chooseNextStep(Issue issue, IssueRecord record, LiveSession live, Path worktree) {
        if (hasChangedInLiveMode(record, live, worktree)) {
            return buildAgain(issue, record, worktree);
        }
        if (live.waitBefore().filter(APPROVAL_WAITS::contains).isPresent()) {
            return restoreTheWait(record, live);
        }
        if (record.phase() == Phase.IMPLEMENT) {
            return record.withAfterConversation().withStatus(IssueStatus.IN_PROGRESS).withoutWaitReason();
        }
        return record.withStatus(statusOfThePhase(record.phase())).withoutWaitReason();
    }

    /**
     * The agent of an implement step leaves its work not committed, also before a question. So in that phase only
     * a new commit is a change of the conversation; the implement step commits the rest after the next run.
     */
    private boolean hasChangedInLiveMode(IssueRecord record, LiveSession live, Path worktree) {
        if (!git.headCommit(worktree).equals(live.startHeadCommit())) {
            return true;
        }
        return record.phase() != Phase.IMPLEMENT && git.hasUncommittedChanges(worktree);
    }

    /** An approval covers one diff, so a change of the conversation withdraws it. */
    private IssueRecord buildAgain(Issue issue, IssueRecord record, Path worktree) {
        if (git.hasUncommittedChanges(worktree)) {
            git.commitAll(worktree, ChangeTitle.describe(issue));
        }
        ownerApproval.withdraw(record.issueNumber());
        return record.withoutReviews().withoutRisk().withoutBuiltTree().withoutWaitReason()
                .withPhase(Phase.BUILD).withStatus(IssueStatus.IN_PROGRESS);
    }

    /** /factory:continue is never an approval, so the issue waits for the label again. */
    private static IssueRecord restoreTheWait(IssueRecord record, LiveSession live) {
        IssueRecord restored = record.withStatus(live.statusBefore()).withNewestNote(NO_CHANGE_NOTE);
        return live.waitBefore().map(restored::withWaitReason).orElseGet(restored::withoutWaitReason);
    }

    /** A blocked issue starts again in the phase where it stopped. */
    private static IssueStatus statusOfThePhase(Phase phase) {
        return switch (phase) {
            case PREPARE, IMPLEMENT, BUILD -> IssueStatus.IN_PROGRESS;
            case REVIEW, RISK, MERGE -> IssueStatus.IN_REVIEW;
            case DONE -> IssueStatus.DONE;
        };
    }
}
