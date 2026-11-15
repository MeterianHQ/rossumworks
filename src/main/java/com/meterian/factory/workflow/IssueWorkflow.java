package com.meterian.factory.workflow;

import com.meterian.factory.agent.AgentException;
import com.meterian.factory.agent.AgentRunner;
import com.meterian.factory.agent.ApiKeyDetectedException;
import com.meterian.factory.agent.LiveSessions;
import com.meterian.factory.agent.PhaseRefusedException;
import com.meterian.factory.board.BoardUpdates;
import com.meterian.factory.build.BuildRunner;
import com.meterian.factory.configuration.FactoryConfiguration;
import com.meterian.factory.definition.DefinitionLookup;
import com.meterian.factory.definition.DefinitionVersions;
import com.meterian.factory.definition.FactoryDefinition;
import com.meterian.factory.definition.Target;
import com.meterian.factory.git.Git;
import com.meterian.factory.git.GitException;
import com.meterian.factory.github.GitHubClient;
import com.meterian.factory.github.GitHubException;
import com.meterian.factory.github.GitHubRateLimitException;
import com.meterian.factory.github.Issue;
import com.meterian.factory.ownership.LostIssues;
import com.meterian.factory.ownership.OwnershipGuard;
import com.meterian.factory.state.ActionType;
import com.meterian.factory.state.IssueRecord;
import com.meterian.factory.usage.ContextTracker;
import com.meterian.factory.usage.UsageTracker;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Moves one issue through the states of its workflow. Each call of advance runs one action and publishes the new
 * record. The action gives an event, and the workflow definition of the issue gives the next state.
 */
public final class IssueWorkflow {

    private static final System.Logger LOGGER = System.getLogger(IssueWorkflow.class.getName());
    /** The key of the built-in version: a record without a commit uses it. A commit is never this text. */
    private static final String BUILT_IN_VERSION = "built-in";
    private static final String TOO_EARLY_NOTE =
            "The factory opens a live conversation only after the first coding action.";
    private static final String DONE_NOTE = "The issue is done; a live conversation has no effect.";
    private static final String ALREADY_OPEN_NOTE = "The live conversation is already open: ";

    private final GitHubClient gitHub;
    private final DefinitionLookup definitions;
    private final WorkspaceLayout layout;
    private final LiveSessions liveSessions;
    private final OwnerApproval ownerApproval;
    private final IssueTextCheck issueTextCheck;
    private final LiveMode liveMode;
    private final LiveRecordPublisher livePublisher;
    private final UnknownSessionCloser unknownSessionCloser;
    private final OnAnyStateMoves onAnyStateMoves;
    private final MoveRunner moveRunner = new MoveRunner();
    private final LimitCheck limitCheck = new LimitCheck(moveRunner);
    private final ActionServices services;
    /** The notes of the review round of the current action, by issue number. Each issue thread uses its own entry. */
    private final Map<Integer, List<ReviewNote>> reviewNotesToPublish = new ConcurrentHashMap<>();
    /** The actions of each definition version, by commit. Several issue threads read and fill this map. */
    private final Map<String, ActionSet> actionSets = new ConcurrentHashMap<>();

    /**
     * @param definitions     the definition version of each record
     * @param mergeRetryDelay the wait between two attempts of a squash merge
     * @param ownershipGuard  the check that this factory still owns the issue before a change on GitHub
     * @param lostIssues      the issues that another account owns now
     */
    public IssueWorkflow(FactoryConfiguration configuration, GitHubClient gitHub, Git git, AgentRunner agentRunner,
                         DefinitionLookup definitions,
                         BuildRunner buildRunner, Duration mergeRetryDelay, UsageTracker usage,
                         WorkspaceLayout layout, String workerLogin,
                         LiveSessions liveSessions, BoardUpdates board, ContextTracker contexts,
                         PageRequests pageRequests, RetrySettings retrySettings, OwnershipGuard ownershipGuard,
                         LostIssues lostIssues) {
        this.gitHub = gitHub;
        this.definitions = definitions;
        this.layout = layout;
        this.liveSessions = liveSessions;
        this.ownerApproval = new OwnerApproval(gitHub);
        StatusPublisher statusPublisher = new StatusPublisher(gitHub, workerLogin, definitions, board, lostIssues);
        this.issueTextCheck = new IssueTextCheck(ownerApproval);
        PhaseRunner phaseRunner = new PhaseRunner(agentRunner, usage, contexts);
        this.liveMode = new LiveMode(git, gitHub, liveSessions, pageRequests);
        this.livePublisher = new LiveRecordPublisher(statusPublisher, liveSessions);
        this.unknownSessionCloser = new UnknownSessionCloser(liveSessions);
        this.onAnyStateMoves = new OnAnyStateMoves(definitions, layout);
        ForwardCheckFailure forwardCheckFailure =
                new ForwardCheckFailure(statusPublisher, retrySettings.forwardChecks(), ownershipGuard);
        this.services = new ActionServices(configuration, gitHub, git, buildRunner, mergeRetryDelay, ownerApproval,
                statusPublisher, forwardCheckFailure, phaseRunner, liveMode, liveSessions, reviewNotesToPublish,
                retrySettings, ownershipGuard);
    }

    /** The review notes go to the pull request only after the record of their round is on GitHub. */
    public IssueRecord advance(IssueRecord record) throws ApiKeyDetectedException {
        reviewNotesToPublish.remove(record.issueNumber());
        Issue issue = gitHub.issue(record.issueNumber());
        IssueRecord next = runCurrentAction(issue, record);
        IssueRecord published = livePublisher.publish(issue, record, next);
        publishReviewNotes(issue, record);
        return published;
    }

    /**
     * Moves the issue to the enter target, and opens the conversation at once. A finished issue and an issue
     * without a started agent session do not move: they get a note.
     */
    public IssueRecord enter(IssueRecord record) {
        Issue issue = gitHub.issue(record.issueNumber());
        try {
            IssueRecord next = enterTheConversation(issue, record);
            return livePublisher.publish(issue, record, next);
        } catch (ApiKeyDetectedException exception) {
            throw new IllegalStateException("The conversation action runs no agent session.", exception);
        }
    }

    /**
     * True when the issue waits for the owner: the finish state, an approval, a state without an action, or an
     * open live conversation.
     */
    public static boolean isWaiting(IssueRecord record) {
        Optional<ActionType> action = record.action();
        if (action.isEmpty()) {
            return true;
        }
        return switch (action.get()) {
            case FINISH, APPROVAL -> true;
            case CONVERSATION -> record.live().isPresent();
            case PREPARE, CODING, BUILD, REVIEWS, RISK, MERGE_GATE -> false;
        };
    }

    /**
     * True when the loop of the issue stops: the issue waits, and the start of its action is done. The finish
     * action and the start of an approval run in the same loop as the move.
     */
    public static boolean isAtRest(IssueRecord record) {
        boolean runsAtTheEntry = record.action().filter(type -> type == ActionType.FINISH
                || type == ActionType.APPROVAL).isPresent();
        return isWaiting(record) && !(runsAtTheEntry && !record.actionStarted());
    }

    /**
     * Tells if the worker starts the issue now. An issue that waits for you starts only with the answer it waits
     * for: the label factory:approve, or a continue request. The start of an approval always runs.
     */
    public boolean isStartable(Issue issue, IssueRecord record) {
        Optional<ActionType> action = record.action();
        if (action.isEmpty()) {
            return isContinueRequested(record);
        }
        return switch (action.get()) {
            case APPROVAL -> !record.actionStarted() || ownerApproval.isGiven(issue);
            case CONVERSATION -> record.live().isEmpty() || isContinueRequested(record);
            case PREPARE, CODING, BUILD, REVIEWS, RISK, MERGE_GATE, FINISH -> true;
        };
    }

    private boolean isContinueRequested(IssueRecord record) {
        return liveSessions.isContinueRequested(record.issueNumber());
    }

    /** A failure loses only the notes: the review round stays, and the next round writes its notes again. */
    private void publishReviewNotes(Issue issue, IssueRecord record) {
        List<ReviewNote> notes = reviewNotesToPublish.remove(issue.number());
        if (notes == null) {
            return;
        }
        try {
            actionSetOf(record).pullRequestPublisher().publishReviewNotes(issue, notes);
        } catch (GitHubRateLimitException exception) {
            throw exception;
        } catch (GitHubException exception) {
            LOGGER.log(System.Logger.Level.WARNING, "The review notes of the issue #{0} are not in the pull "
                    + "request: {1}", issue.number(), exception.getMessage());
        }
    }

    private IssueRecord enterTheConversation(Issue issue, IssueRecord record) throws ApiKeyDetectedException {
        if (record.action().equals(Optional.of(ActionType.FINISH))) {
            return record.withNote(DONE_NOTE);
        }
        if (!record.agentSessionStarted() || !Files.isDirectory(layout.worktree(record.issueNumber()))) {
            return record.withNote(TOO_EARLY_NOTE);
        }
        Optional<String> openLink = liveMode.findOpenLink(record);
        if (openLink.isPresent()) {
            return record.withNote(ALREADY_OPEN_NOTE + openLink.get());
        }
        IssueRecord moved;
        try {
            moved = onAnyStateMoves.moveToEnterTarget(record);
        } catch (IllegalStateException exception) {
            return stopOnAnUnloadableDefinition(record, exception);
        }
        return runCurrentAction(issue, moved);
    }

    /**
     * Fail closed: while a session that the record does not know can run, no action starts. An issue whose
     * definition version does not load runs no action.
     */
    private IssueRecord runCurrentAction(Issue issue, IssueRecord record) throws ApiKeyDetectedException {
        Optional<IssueRecord> withoutUnknownSession = unknownSessionCloser.closeUnknownSession(record);
        if (withoutUnknownSession.isEmpty()) {
            return record;
        }
        IssueRecord known = withoutUnknownSession.get();
        ActionSet actionSet;
        try {
            actionSet = actionSetOf(known);
        } catch (IllegalStateException exception) {
            return stopOnAnUnloadableDefinition(known, exception);
        }
        return runActionAndCatchFailures(issue, known, actionSet);
    }

    /** The definition is the same for all records with the same commit, so each version builds its actions once. */
    private ActionSet actionSetOf(IssueRecord record) {
        String version = record.definitionCommit().orElse(BUILT_IN_VERSION);
        return actionSets.computeIfAbsent(version, key -> ActionSet.create(definitions.definitionOf(record), services));
    }

    /** A merged change is final: the record keeps its state and gets the note. Any other issue blocks. */
    private IssueRecord stopOnAnUnloadableDefinition(IssueRecord record, IllegalStateException exception) {
        String commit = DefinitionVersions.shorten(record.definitionCommit().orElse(BUILT_IN_VERSION));
        String note = "The definition " + commit + " of the issue does not load: " + exception.getMessage() + ".";
        if (isInTheFinishState(record)) {
            return MergedIssue.noteFailureInTheFinishState(record, exception).withNote(note);
        }
        return onAnyStateMoves.moveToBlockTarget(record, note);
    }

    /** A refused agent session keeps the record from before the action: the worker shuts down. */
    private IssueRecord runActionAndCatchFailures(Issue issue, IssueRecord record, ActionSet actionSet)
            throws ApiKeyDetectedException {
        try {
            return runAction(issue, record, actionSet, layout.worktree(record.issueNumber()));
        } catch (PhaseRefusedException refused) {
            return record;
        } catch (GitException | UncheckedIOException exception) {
            return failAction(actionSet.definition(), record, "An action failed: " + exception.getMessage(),
                    exception);
        } catch (AgentException exception) {
            return failAction(actionSet.definition(), record, "The agent session failed: " + exception.getMessage(),
                    exception);
        }
    }

    /**
     * The finish action always runs: its cleanup can be left from an earlier run. An action that uses the issue
     * text does not start when the text changed.
     */
    private IssueRecord runAction(Issue issue, IssueRecord record, ActionSet actionSet, Path worktree)
            throws AgentException, ApiKeyDetectedException {
        FactoryDefinition definition = actionSet.definition();
        Action action = actionSet.actionOf(record.action());
        if (isInTheFinishState(record)) {
            return applyResult(definition, action.run(issue, record, worktree), worktree);
        }
        if (issueTextCheck.hasChanged(issue, record)) {
            return onAnyStateMoves.moveToTextChangedTarget(record);
        }
        if (!record.actionStarted()) {
            return startAction(issue, record, actionSet, worktree);
        }
        return applyResult(definition, action.run(issue, record, worktree), worktree);
    }

    /** An entry counts only when the action starts, so a limit that refuses the entry does not count it. */
    private IssueRecord startAction(Issue issue, IssueRecord record, ActionSet actionSet, Path worktree)
            throws AgentException, ApiKeyDetectedException {
        FactoryDefinition definition = actionSet.definition();
        Optional<IssueRecord> refused = limitCheck.refuseEntry(definition, record, worktree);
        if (refused.isPresent()) {
            return refused.get();
        }
        IssueRecord started = LimitCheck.countEntry(definition, record).withActionStarted();
        Action action = actionSet.actionOf(record.action());
        Optional<ActionResult> startResult = action.start(issue, started);
        ActionResult result = startResult.isPresent() ? startResult.get() : action.run(issue, started, worktree);
        return applyResult(definition, result, worktree);
    }

    private IssueRecord applyResult(FactoryDefinition definition, ActionResult result, Path worktree) {
        return switch (result) {
            case ActionResult.Event event -> moveRunner.move(definition, event, worktree);
            case ActionResult.MoveTo moveTo ->
                    moveRunner.moveTo(definition, moveTo.record(), new Target.ToState(moveTo.stateName()), worktree);
            case ActionResult.Stay stay -> stay.record();
            case ActionResult.Blocked blocked -> onAnyStateMoves.moveToBlockTarget(blocked.record(), blocked.reason());
        };
    }

    /** A merged change is final, so a failure after the merge never blocks the issue. */
    private IssueRecord failAction(FactoryDefinition definition, IssueRecord record, String reason,
            Exception exception) {
        if (isInTheFinishState(record)) {
            return MergedIssue.noteFailureAfterTheMerge(record, definition.workflow().finishState(), exception);
        }
        return onAnyStateMoves.moveToBlockTarget(closeTheLiveSession(record), reason);
    }

    /** The block target has no conversation: the record must never know less than tmux. */
    private IssueRecord closeTheLiveSession(IssueRecord record) {
        if (record.live().isEmpty()) {
            return record;
        }
        liveSessions.close(record.issueNumber());
        return record.withoutLive();
    }

    private static boolean isInTheFinishState(IssueRecord record) {
        return record.action().equals(Optional.of(ActionType.FINISH));
    }
}
