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
import com.meterian.factory.git.Git;
import com.meterian.factory.git.GitException;
import com.meterian.factory.github.GitHubClient;
import com.meterian.factory.github.GitHubException;
import com.meterian.factory.github.GitHubRateLimitException;
import com.meterian.factory.github.Issue;
import com.meterian.factory.state.IssueRecord;
import com.meterian.factory.state.IssueStatus;
import com.meterian.factory.state.StepType;
import com.meterian.factory.state.WaitReason;
import com.meterian.factory.usage.ContextTracker;
import com.meterian.factory.usage.UsageTracker;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Moves one issue through the states of its workflow. Each call of advance runs one step and publishes the new
 * record. The step gives an outcome, and the workflow definition of the issue gives the next state.
 */
public final class IssueWorkflow {

    private static final System.Logger LOGGER = System.getLogger(IssueWorkflow.class.getName());
    private static final Set<IssueStatus> WAITING_STATUSES =
            Set.of(IssueStatus.WAITS_FOR_YOU, IssueStatus.BLOCKED, IssueStatus.DONE);
    /** The key of the built-in version: a record without a commit uses it. A commit is never this text. */
    private static final String BUILT_IN_VERSION = "built-in";

    private final GitHubClient gitHub;
    private final DefinitionLookup definitions;
    private final WorkspaceLayout layout;
    private final IssueTextCheck issueTextCheck;
    private final ApprovalCheck approvalCheck;
    private final LiveMode liveMode;
    private final LiveRecordPublisher livePublisher;
    private final UnknownSessionCloser unknownSessionCloser;
    private final StepServices services;
    /** The notes of the review round of the current step, by issue number. Each issue thread uses its own entry. */
    private final Map<Integer, List<ReviewNote>> reviewNotesToPublish = new ConcurrentHashMap<>();
    /** The steps of each definition version, by commit. Several issue threads read and fill this map. */
    private final Map<String, StepSet> stepSets = new ConcurrentHashMap<>();

    /**
     * @param definitions     the definition version of each record
     * @param mergeRetryDelay the wait between two attempts of a squash merge
     */
    public IssueWorkflow(FactoryConfiguration configuration, GitHubClient gitHub, Git git, AgentRunner agentRunner,
                         DefinitionLookup definitions,
                         BuildRunner buildRunner, Duration mergeRetryDelay, UsageTracker usage,
                         WorkspaceLayout layout, String workerLogin,
                         LiveSessions liveSessions, BoardUpdates board, ContextTracker contexts,
                         PageRequests pageRequests) {
        this.gitHub = gitHub;
        this.definitions = definitions;
        this.layout = layout;
        OwnerApproval ownerApproval = new OwnerApproval(gitHub);
        StatusPublisher statusPublisher = new StatusPublisher(gitHub, workerLogin, definitions, board);
        this.issueTextCheck = new IssueTextCheck(ownerApproval, definitions);
        this.approvalCheck = new ApprovalCheck(ownerApproval, issueTextCheck, definitions);
        PhaseRunner phaseRunner = new PhaseRunner(agentRunner, usage, contexts);
        this.liveMode = new LiveMode(git, gitHub, liveSessions, ownerApproval, pageRequests,
                definitions);
        this.livePublisher = new LiveRecordPublisher(statusPublisher, liveSessions);
        this.unknownSessionCloser = new UnknownSessionCloser(liveSessions);
        this.services = new StepServices(configuration, gitHub, git, buildRunner, mergeRetryDelay, ownerApproval,
                statusPublisher, new ForwardCheckFailure(statusPublisher), phaseRunner, liveMode,
                reviewNotesToPublish);
    }

    /** The review notes go to the pull request only after the record of their round is on GitHub. */
    public IssueRecord advance(IssueRecord record) throws ApiKeyDetectedException {
        reviewNotesToPublish.remove(record.issueNumber());
        Issue issue = gitHub.issue(record.issueNumber());
        IssueRecord next = runStep(issue, record);
        IssueRecord published = livePublisher.publish(issue, record, next);
        publishReviewNotes(issue, record);
        return published;
    }

    /** A failure loses only the notes: the review round stays, and the next round writes its notes again. */
    private void publishReviewNotes(Issue issue, IssueRecord record) {
        List<ReviewNote> notes = reviewNotesToPublish.remove(issue.number());
        if (notes == null) {
            return;
        }
        try {
            stepSetOf(record).pullRequestPublisher().publishReviewNotes(issue, notes);
        } catch (GitHubRateLimitException exception) {
            throw exception;
        } catch (GitHubException exception) {
            LOGGER.log(System.Logger.Level.WARNING, "The review notes of the issue #{0} are not in the pull "
                    + "request: {1}", issue.number(), exception.getMessage());
        }
    }

    public IssueRecord enterLiveMode(IssueRecord record) {
        Issue issue = gitHub.issue(record.issueNumber());
        IssueRecord next = liveMode.enter(record, layout.worktree(record.issueNumber()));
        return livePublisher.publish(issue, record, next);
    }

    public static boolean isLive(IssueRecord record) {
        return record.live().isPresent();
    }

    public boolean isWaiting(IssueRecord record) {
        return WAITING_STATUSES.contains(record.status());
    }

    /**
     * Fail closed: while a session that the record does not know can run, no phase starts. An issue whose
     * definition version does not load runs no step.
     */
    private IssueRecord runStep(Issue issue, IssueRecord record) throws ApiKeyDetectedException {
        Optional<IssueRecord> withoutUnknownSession = unknownSessionCloser.closeUnknownSession(record);
        if (withoutUnknownSession.isEmpty()) {
            return record;
        }
        IssueRecord known = withoutUnknownSession.get();
        StepSet stepSet;
        try {
            stepSet = stepSetOf(known);
        } catch (IllegalStateException exception) {
            return stopOnAnUnloadableDefinition(known, exception);
        }
        return runKnownStep(issue, known, stepSet);
    }

    /** The definition is the same for all records with the same commit, so each version builds its steps once. */
    private StepSet stepSetOf(IssueRecord record) {
        String version = record.definitionCommit().orElse(BUILT_IN_VERSION);
        return stepSets.computeIfAbsent(version, key -> StepSet.create(definitions.definitionOf(record), services));
    }

    /** A merged change is final: the record keeps its state and gets the note. Any other issue blocks. */
    private IssueRecord stopOnAnUnloadableDefinition(IssueRecord record, IllegalStateException exception) {
        String commit = DefinitionVersions.shorten(record.definitionCommit().orElse(BUILT_IN_VERSION));
        String note = "The definition " + commit + " of the issue does not load: " + exception.getMessage() + ".";
        if (record.step() == StepType.FINISH) {
            return MergedIssue.noteFailureAfterTheMerge(record, record.state(), exception).withNote(note);
        }
        return block(record, note);
    }

    /**
     * A record in phase DONE always runs the finish step: its cleanup can be left from an earlier run.
     * While the live conversation is open, no phase runs.
     */
    private IssueRecord runKnownStep(Issue issue, IssueRecord record, StepSet stepSet)
            throws ApiKeyDetectedException {
        try {
            if (isLive(record)) {
                return liveMode.isContinueRequested(record)
                        ? liveMode.continueWork(issue, record, layout.worktree(record.issueNumber())) : record;
            }
            boolean checksApproval = isWaiting(record) && record.step() != StepType.FINISH;
            return checksApproval ? approvalCheck.continueAfterApproval(issue, record)
                    : runPhase(issue, record, stepSet);
        } catch (PhaseRefusedException refused) {
            return record;
        } catch (GitException | UncheckedIOException exception) {
            return failStep(record, "A step failed: " + exception.getMessage(), exception);
        } catch (AgentException exception) {
            return failStep(record, "The agent session failed: " + exception.getMessage(), exception);
        }
    }

    /** A merged change is final, so a failure after the merge never blocks the issue. */
    private IssueRecord failStep(IssueRecord record, String reason, Exception exception) {
        if (record.step() == StepType.FINISH) {
            String finishState = definitions.definitionOf(record).workflow().stateWithStep(StepType.FINISH).name();
            return MergedIssue.noteFailureAfterTheMerge(record, finishState, exception);
        }
        return block(record, reason);
    }

    private IssueRecord runPhase(Issue issue, IssueRecord record, StepSet stepSet)
            throws AgentException, ApiKeyDetectedException {
        if (issueTextCheck.hasChanged(issue, record)) {
            return issueTextCheck.waitForTheOwner(record);
        }
        Path worktree = layout.worktree(record.issueNumber());
        StepResult result = stepSet.stepOf(record.step()).run(issue, record, worktree);
        return applyResult(issue, result, worktree, stepSet);
    }

    private IssueRecord applyResult(Issue issue, StepResult result, Path worktree, StepSet stepSet) {
        return switch (result) {
            case StepResult.Outcome outcome ->
                    stepSet.transitionRunner().apply(stepSet.definition(), issue, outcome, worktree);
            case StepResult.Stay stay -> stay.record();
            case StepResult.Blocked blocked -> block(blocked.record(), blocked.reason());
        };
    }

    private IssueRecord block(IssueRecord record, String reason) {
        return record.withStatus(IssueStatus.BLOCKED).withWaitReason(WaitReason.FAILURE).withNote(reason);
    }
}
