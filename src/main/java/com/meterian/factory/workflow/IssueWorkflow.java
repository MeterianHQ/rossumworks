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
import com.meterian.factory.git.Git;
import com.meterian.factory.git.GitException;
import com.meterian.factory.github.GitHubClient;
import com.meterian.factory.github.GitHubException;
import com.meterian.factory.github.GitHubRateLimitException;
import com.meterian.factory.github.Issue;
import com.meterian.factory.merge.Merger;
import com.meterian.factory.merge.ReviewedCommit;
import com.meterian.factory.prompt.PromptLibrary;
import com.meterian.factory.state.IssueRecord;
import com.meterian.factory.state.IssueStatus;
import com.meterian.factory.state.StepType;
import com.meterian.factory.state.WaitReason;
import com.meterian.factory.usage.ContextTracker;
import com.meterian.factory.usage.UsageTracker;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Moves one issue through the states of its workflow. Each call of advance runs one step and publishes the new
 * record. The step gives an outcome, and the workflow definition gives the next state.
 */
public final class IssueWorkflow {

    private static final System.Logger LOGGER = System.getLogger(IssueWorkflow.class.getName());
    private static final Set<IssueStatus> WAITING_STATUSES =
            Set.of(IssueStatus.WAITS_FOR_YOU, IssueStatus.BLOCKED, IssueStatus.DONE);

    private final GitHubClient gitHub;
    private final DefinitionLookup definitions;
    private final WorkspaceLayout layout;
    private final PullRequestPublisher pullRequestPublisher;
    private final IssueTextCheck issueTextCheck;
    private final ApprovalCheck approvalCheck;
    private final TransitionRunner transitionRunner;
    private final LiveMode liveMode;
    private final LiveRecordPublisher livePublisher;
    private final UnknownSessionCloser unknownSessionCloser;
    private final StepServices services;
    /** The notes of the review round of the current step, by issue number. Each issue thread uses its own entry. */
    private final Map<Integer, List<ReviewNote>> reviewNotesToPublish = new ConcurrentHashMap<>();

    public IssueWorkflow(FactoryConfiguration configuration, GitHubClient gitHub, Git git, AgentRunner agentRunner,
                         PromptLibrary prompts, DefinitionLookup definitions, BuildRunner buildRunner,
                         Merger merger, UsageTracker usage, RequestReader requests, WorkspaceLayout layout,
                         String workerLogin, LiveSessions liveSessions, BoardUpdates board, ContextTracker contexts,
                         PageRequests pageRequests) {
        this.gitHub = gitHub;
        this.definitions = definitions;
        this.layout = layout;
        OwnerApproval ownerApproval = new OwnerApproval(gitHub, requests);
        this.pullRequestPublisher = new PullRequestPublisher(gitHub, git, configuration.mergeTargetBranch(),
                configuration.gatekeeperDirectory().toAbsolutePath());
        StatusPublisher statusPublisher = new StatusPublisher(gitHub, workerLogin,
                configuration.limits().fixLoops(), board);
        this.issueTextCheck = new IssueTextCheck(ownerApproval, definitions);
        this.approvalCheck = new ApprovalCheck(ownerApproval, issueTextCheck, definitions);
        PhaseRunner phaseRunner = new PhaseRunner(agentRunner, usage, contexts);
        this.liveMode = new LiveMode(git, gitHub, liveSessions, ownerApproval, configuration.allowedAccounts(),
                pageRequests, definitions);
        this.transitionRunner = new TransitionRunner(new FixLoop(prompts, ownerApproval), gitHub,
                configuration.allowedAccounts(), liveMode);
        this.livePublisher = new LiveRecordPublisher(statusPublisher, liveSessions);
        this.unknownSessionCloser = new UnknownSessionCloser(liveSessions);
        this.services = new StepServices(configuration, gitHub, git, prompts, buildRunner, merger, ownerApproval,
                new ConflictResolution(git, configuration.mergeTargetBranch()),
                new IssueWorkspace(configuration, git), pullRequestPublisher, statusPublisher,
                new ForwardCheckFailure(statusPublisher),
                new ReviewedCommit(git, configuration.target().cloneDirectory(), configuration.mergeTargetBranch()),
                phaseRunner, new ReviewRound(phaseRunner, prompts), reviewNotesToPublish);
    }

    /** The review notes go to the pull request only after the record of their round is on GitHub. */
    public IssueRecord advance(IssueRecord record) throws ApiKeyDetectedException {
        reviewNotesToPublish.remove(record.issueNumber());
        Issue issue = gitHub.issue(record.issueNumber());
        IssueRecord next = runStep(issue, record);
        IssueRecord published = livePublisher.publish(issue, record, next);
        publishReviewNotes(issue);
        return published;
    }

    /** A failure loses only the notes: the review round stays, and the next round writes its notes again. */
    private void publishReviewNotes(Issue issue) {
        List<ReviewNote> notes = reviewNotesToPublish.remove(issue.number());
        if (notes == null) {
            return;
        }
        try {
            pullRequestPublisher.publishReviewNotes(issue, notes);
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

    /** Fail closed: while a session that the record does not know can run, no phase starts. */
    private IssueRecord runStep(Issue issue, IssueRecord record) throws ApiKeyDetectedException {
        Optional<IssueRecord> withoutUnknownSession = unknownSessionCloser.closeUnknownSession(record);
        return withoutUnknownSession.isPresent() ? runKnownStep(issue, withoutUnknownSession.get()) : record;
    }

    /**
     * A record in phase DONE always runs the finish step: its cleanup can be left from an earlier run.
     * While the live conversation is open, no phase runs.
     */
    private IssueRecord runKnownStep(Issue issue, IssueRecord record) throws ApiKeyDetectedException {
        try {
            if (isLive(record)) {
                return liveMode.isContinueRequested(record)
                        ? liveMode.continueWork(issue, record, layout.worktree(record.issueNumber())) : record;
            }
            boolean checksApproval = isWaiting(record) && record.step() != StepType.FINISH;
            return checksApproval ? approvalCheck.continueAfterApproval(issue, record) : runPhase(issue, record);
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

    private IssueRecord runPhase(Issue issue, IssueRecord record) throws AgentException, ApiKeyDetectedException {
        if (issueTextCheck.hasChanged(issue, record)) {
            return issueTextCheck.waitForTheOwner(record);
        }
        Path worktree = layout.worktree(record.issueNumber());
        StepResult result = runStepOfTheState(issue, record, worktree);
        return applyResult(issue, result, worktree);
    }

    private StepResult runStepOfTheState(Issue issue, IssueRecord record, Path worktree)
            throws AgentException, ApiKeyDetectedException {
        StepSet stepSet = StepSet.create(definitions.definitionOf(record), services);
        return stepSet.stepOf(record.step()).run(issue, record, worktree);
    }

    private IssueRecord applyResult(Issue issue, StepResult result, Path worktree) {
        return switch (result) {
            case StepResult.Outcome outcome ->
                    transitionRunner.apply(definitions.definitionOf(outcome.record()), issue, outcome, worktree);
            case StepResult.Stay stay -> stay.record();
            case StepResult.Blocked blocked -> block(blocked.record(), blocked.reason());
        };
    }

    private IssueRecord block(IssueRecord record, String reason) {
        return record.withStatus(IssueStatus.BLOCKED).withWaitReason(WaitReason.FAILURE).withNote(reason);
    }
}
