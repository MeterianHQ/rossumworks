package com.meterian.factory.workflow;

import com.meterian.factory.agent.AgentException;
import com.meterian.factory.agent.AgentRunner;
import com.meterian.factory.agent.ApiKeyDetectedException;
import com.meterian.factory.agent.LiveSessions;
import com.meterian.factory.agent.PhaseRefusedException;
import com.meterian.factory.agent.PhaseRequest;
import com.meterian.factory.agent.SessionReport;
import com.meterian.factory.board.BoardUpdates;
import com.meterian.factory.build.BuildOutcome;
import com.meterian.factory.build.BuildRunner;
import com.meterian.factory.configuration.FactoryConfiguration;
import com.meterian.factory.definition.DefinitionLookup;
import com.meterian.factory.git.Git;
import com.meterian.factory.git.GitException;
import com.meterian.factory.github.GitHubClient;
import com.meterian.factory.github.GitHubException;
import com.meterian.factory.github.GitHubRateLimitException;
import com.meterian.factory.github.Issue;
import com.meterian.factory.merge.MergeDecision;
import com.meterian.factory.merge.MergeGate;
import com.meterian.factory.merge.MergeResult;
import com.meterian.factory.merge.Merger;
import com.meterian.factory.merge.ReviewedCommit;
import com.meterian.factory.prompt.Finding;
import com.meterian.factory.prompt.PromptLibrary;
import com.meterian.factory.prompt.RiskResult;
import com.meterian.factory.prompt.RiskResultReader;
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
    private static final String FACTORY_LABEL = "factory";
    private static final int MAXIMUM_FAILED_REVIEW_ROUNDS = 2;
    private static final Set<IssueStatus> WAITING_STATUSES =
            Set.of(IssueStatus.WAITS_FOR_YOU, IssueStatus.BLOCKED, IssueStatus.DONE);

    private final FactoryConfiguration configuration;
    private final GitHubClient gitHub;
    private final Git git;
    private final PromptLibrary prompts;
    private final DefinitionLookup definitions;
    private final BuildRunner buildRunner;
    private final Merger merger;
    private final WorkspaceLayout layout;
    private final PhaseRunner phaseRunner;
    private final ReviewRound reviewRound;
    private final StatusPublisher statusPublisher;
    private final OwnerApproval ownerApproval;
    private final ConflictResolution conflictResolution;
    private final IssueWorkspace workspace;
    private final PullRequestPublisher pullRequestPublisher;
    private final IssueTextCheck issueTextCheck;
    private final ApprovalCheck approvalCheck;
    private final ForwardCheckFailure forwardCheckFailure;
    private final ReviewedCommit reviewedCommit;
    private final TransitionRunner transitionRunner;
    private final LiveMode liveMode;
    private final LiveRecordPublisher livePublisher;
    private final UnknownSessionCloser unknownSessionCloser;
    /** The notes of the review round of the current step, by issue number. Each issue thread uses its own entry. */
    private final Map<Integer, List<ReviewNote>> reviewNotesToPublish = new ConcurrentHashMap<>();

    public IssueWorkflow(FactoryConfiguration configuration, GitHubClient gitHub, Git git, AgentRunner agentRunner,
                         PromptLibrary prompts, DefinitionLookup definitions, BuildRunner buildRunner,
                         Merger merger, UsageTracker usage, RequestReader requests, WorkspaceLayout layout,
                         String workerLogin, LiveSessions liveSessions, BoardUpdates board, ContextTracker contexts,
                         PageRequests pageRequests) {
        this.configuration = configuration;
        this.gitHub = gitHub;
        this.git = git;
        this.prompts = prompts;
        this.definitions = definitions;
        this.buildRunner = buildRunner;
        this.merger = merger;
        this.ownerApproval = new OwnerApproval(gitHub, requests);
        this.conflictResolution = new ConflictResolution(git, configuration.mergeTargetBranch());
        this.workspace = new IssueWorkspace(configuration, git);
        this.pullRequestPublisher = new PullRequestPublisher(gitHub, git, configuration.mergeTargetBranch(),
                configuration.gatekeeperDirectory().toAbsolutePath());
        this.statusPublisher = new StatusPublisher(gitHub, workerLogin, configuration.limits().fixLoops(), board);
        this.forwardCheckFailure = new ForwardCheckFailure(statusPublisher);
        this.reviewedCommit = new ReviewedCommit(git, configuration.target().cloneDirectory(),
                configuration.mergeTargetBranch());
        this.issueTextCheck = new IssueTextCheck(ownerApproval, definitions);
        this.approvalCheck = new ApprovalCheck(ownerApproval, issueTextCheck, definitions);
        this.layout = layout;
        this.phaseRunner = new PhaseRunner(agentRunner, usage, contexts);
        this.reviewRound = new ReviewRound(phaseRunner, prompts);
        this.liveMode = new LiveMode(git, gitHub, liveSessions, ownerApproval, configuration.allowedAccounts(),
                pageRequests, definitions);
        this.transitionRunner = new TransitionRunner(new FixLoop(prompts, ownerApproval), gitHub,
                configuration.allowedAccounts(), liveMode);
        this.livePublisher = new LiveRecordPublisher(statusPublisher, liveSessions);
        this.unknownSessionCloser = new UnknownSessionCloser(liveSessions);
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
        return switch (record.step()) {
            case PREPARE -> prepare(record, worktree);
            case AGENT -> implement(issue, record, worktree);
            case BUILD -> build(issue, record, worktree);
            case REVIEWS -> review(issue, record, worktree);
            case RISK -> assessRisk(issue, record, worktree);
            case MERGE_GATE -> merge(issue, record, worktree);
            case FINISH -> finish(issue, record, worktree);
        };
    }

    private IssueRecord applyResult(Issue issue, StepResult result, Path worktree) {
        return switch (result) {
            case StepResult.Outcome outcome ->
                    transitionRunner.apply(definitions.definitionOf(outcome.record()), issue, outcome, worktree);
            case StepResult.Stay stay -> stay.record();
            case StepResult.Blocked blocked -> block(blocked.record(), blocked.reason());
        };
    }

    private StepResult prepare(IssueRecord record, Path worktree) {
        workspace.create(record.issueNumber(), worktree);
        return StepResult.endWith(record, "done");
    }

    private StepResult implement(Issue issue, IssueRecord record, Path worktree)
            throws AgentException, ApiKeyDetectedException {
        if (conflictResolution.hasUnfinishedMergeCommit(worktree)) {
            conflictResolution.finishMergeCommit(worktree, ChangeTitle.describe(issue));
            return StepResult.endWith(record.withoutConflictedPaths(), "changed");
        }
        if (!phaseRunner.mayStartPhase()) {
            return StepResult.stay(record);
        }
        FactoryFolder factoryFolder = new FactoryFolder(worktree);
        Optional<String> prompt = ImplementPrompt.choose(prompts, issue, record, factoryFolder);
        if (prompt.isEmpty()) {
            return StepResult.block(record, "The findings of the fix loop are missing.");
        }
        IssueRecord started = markSessionStarted(issue, record);
        SessionReport report;
        try {
            report = phaseRunner.runPhase(new PhaseRequest(record.issueNumber(), started.sessionId(),
                    !record.agentSessionStarted(), worktree, prompt.get()));
        } catch (PhaseRefusedException refused) {
            return StepResult.stay(undoSessionStart(issue, started, record));
        }
        IssueRecord ran = started.withoutAfterConversation();
        if (factoryFolder.hasQuestion()) {
            return StepResult.endWithReason(ran, "question", factoryFolder.readQuestion());
        }
        if (!report.success()) {
            return StepResult.endWithReason(ran, "failed", "The agent session failed: " + report.resultText());
        }
        if (git.hasOpenMerge(worktree)) {
            return commitSolvedConflict(issue, ran, worktree);
        }
        if (!git.hasUncommittedChanges(worktree)) {
            return hasUnbuiltCommit(record, worktree) ? StepResult.endWith(ran, "changed")
                    : StepResult.endWithReason(ran, "no-change", "The agent made no change.");
        }
        git.commitAll(worktree, ChangeTitle.describe(issue));
        return StepResult.endWith(ran, "changed");
    }

    /**
     * A run of the phase can commit its change and then lose its record, for example on a GitHub rate limit. The
     * commit is the change of the phase when it is on the branch only and the factory did not build its tree.
     */
    private boolean hasUnbuiltCommit(IssueRecord record, Path worktree) {
        String headCommit = git.headCommit(worktree);
        boolean isOnTheBranchOnly = !git.isAncestor(worktree, headCommit, mergeTargetReference());
        boolean isBuilt = record.builtTreeHash().equals(Optional.of(git.treeHashOf(worktree, headCommit)));
        return isOnTheBranchOnly && !isBuilt;
    }

    private StepResult commitSolvedConflict(Issue issue, IssueRecord record, Path worktree) {
        Optional<Finding> remainingMarkers =
                conflictResolution.findRemainingMarkers(worktree, record.conflictedPaths());
        if (remainingMarkers.isPresent()) {
            return StepResult.endWithFindings(record, "conflict-left", List.of(remainingMarkers.get()));
        }
        conflictResolution.commitAsOneCommit(worktree, ChangeTitle.describe(issue));
        return StepResult.endWith(record.withoutConflictedPaths(), "changed");
    }

    private StepResult build(Issue issue, IssueRecord record, Path worktree) {
        if (git.hasUncommittedChanges(worktree)) {
            return StepResult.block(record, "The worktree has changes that are not committed.");
        }
        String headCommit = git.headCommit(worktree);
        String treeHash = git.treeHashOf(worktree, headCommit);
        if (!isBuiltWithALostForward(record, treeHash)) {
            BuildOutcome outcome = buildRunner.run(worktree, configuration.target());
            if (outcome.stopped()) {
                return StepResult.stay(record);
            }
            if (git.hasUncommittedChanges(worktree) || !git.headCommit(worktree).equals(headCommit)) {
                return StepResult.block(record, "The build changed the files.");
            }
            if (!outcome.passed()) {
                Finding failure = new Finding("The build failed", outcome.outputTail());
                return StepResult.endWithFindings(record, "failed", List.of(failure));
            }
        }
        ownerApproval.withdraw(record.issueNumber());
        PullRequestPublisher.PublishedBranch published = pullRequestPublisher.publish(issue, worktree, headCommit);
        IssueRecord built = record.withBuiltTree(treeHash).withPullRequest(published.pullRequest().number());
        if (!published.isOnGitHub()) {
            return StepResult.stay(forwardCheckFailure.count(issue, record, built, headCommit, treeHash));
        }
        return StepResult.endWith(built.withoutFailedForwardChecks(), "passed");
    }

    /** The build passed for this tree and only the forward failed, so the build does not run again. */
    private static boolean isBuiltWithALostForward(IssueRecord record, String treeHash) {
        return record.failedForwardTree().equals(Optional.of(treeHash))
                && record.builtTreeHash().equals(Optional.of(treeHash));
    }

    private StepResult review(Issue issue, IssueRecord record, Path worktree)
            throws AgentException, ApiKeyDetectedException {
        if (!phaseRunner.mayStartPhase()) {
            return StepResult.stay(record);
        }
        String headCommit = git.headCommit(worktree);
        String treeHash = git.treeHashOf(worktree, headCommit);
        ReviewRound.Outcome outcome = reviewRound.review(issue, record, worktree, treeHash);
        if (git.hasUncommittedChanges(worktree) || !git.headCommit(worktree).equals(headCommit)) {
            return StepResult.block(outcome.record(), "A review changed the files.");
        }
        if (outcome.sessionFailed()) {
            return retryReviewRound(record);
        }
        reviewNotesToPublish.put(issue.number(), outcome.notes());
        IssueRecord reviewed = outcome.record().withoutFailedReviewRounds();
        if (!outcome.allApprove()) {
            return StepResult.endWithFindings(reviewed, "rejected", outcome.findings());
        }
        String patchId = git.patchId(worktree, mergeTargetReference());
        return StepResult.endWith(reviewed.withReviewedTree(treeHash, patchId).withReviewedHeadCommit(headCommit),
                "approved");
    }

    private StepResult assessRisk(Issue issue, IssueRecord record, Path worktree)
            throws AgentException, ApiKeyDetectedException {
        if (!phaseRunner.mayStartPhase()) {
            return StepResult.stay(record);
        }
        new FactoryFolder(worktree).deleteRiskResult();
        String headCommit = git.headCommit(worktree);
        SessionReport report = phaseRunner.runInNewSession(record.issueNumber(), worktree, prompts.risk(issue));
        if (git.hasUncommittedChanges(worktree) || !git.headCommit(worktree).equals(headCommit)) {
            return StepResult.block(record, "The risk session changed the files.");
        }
        if (!report.success()) {
            return retryReviewRound(record);
        }
        RiskResult risk = RiskResultReader.read(worktree);
        return StepResult.endWith(record.withoutFailedReviewRounds().withRisk(risk.risk(), risk.small()), "assessed");
    }

    private StepResult merge(Issue issue, IssueRecord record, Path worktree) {
        if (isMergedOnGitHub(record)) {
            return StepResult.endWith(record.withBuiltTree(git.treeHash(worktree)), "merged");
        }
        reviewedCommit.restoreRebasedHead(worktree, record);
        boolean isApproved = ownerApproval.isGiven(issue);
        IssueRecord noted = isApproved ? record : ownerApproval.noteIgnoredLabel(issue, record);
        boolean questionOpen = new FactoryFolder(worktree).hasQuestion();
        MergeDecision decision = MergeGate.decide(noted, git.treeHash(worktree), questionOpen, isApproved);
        return switch (decision) {
            case MergeDecision.NeedsOwner needsOwner -> StepResult.endWith(noted, "needs-approval");
            case MergeDecision.Refused refused ->
                    StepResult.block(noted, "The merge gate refuses: " + refused.reason() + ".");
            case MergeDecision.Automatic automatic -> mergePullRequest(issue, noted, worktree);
            case MergeDecision.Approved approved -> mergePullRequest(issue, noted, worktree);
        };
    }

    private StepResult mergePullRequest(Issue issue, IssueRecord record, Path worktree) {
        MergeResult result = merger.merge(worktree, record);
        return switch (result) {
            case MergeResult.Merged merged -> StepResult.endWith(merged.note().map(record::withNote).orElse(record)
                    .withoutFailedForwardChecks().withBuiltTree(merged.builtTreeHash()), "merged");
            case MergeResult.NeedsNewReviews needsNewReviews ->
                    StepResult.endWith(record.withoutReviews(), "needs-new-reviews");
            case MergeResult.NeedsFix needsFix -> openMergeForTheFix(record, worktree, needsFix);
            case MergeResult.Failed failed -> StepResult.block(record, "The merge failed: " + failed.reason());
            case MergeResult.Postponed postponed -> StepResult.stay(record);
            case MergeResult.NotForwarded notForwarded -> StepResult.stay(forwardCheckFailure.count(issue, record,
                    record, notForwarded.pushedCommit(), notForwarded.pushedTree()));
        };
    }

    /** Opens a merge with the target in the worktree, so that the agent solves a conflict in the files. */
    private StepResult openMergeForTheFix(IssueRecord record, Path worktree, MergeResult.NeedsFix needsFix) {
        ConflictResolution.OpenedMerge openedMerge = conflictResolution.openMerge(worktree, needsFix);
        IssueRecord withConflict = record.withConflictedPaths(openedMerge.conflictedPaths());
        return StepResult.endWithFindings(withConflict, "conflict", List.of(openedMerge.finding()));
    }

    /**
     * Publishes DONE first, then cleans up. The label factory goes away last: after a failure the issue keeps
     * it, so the next poll runs this step again.
     */
    private StepResult finish(Issue issue, IssueRecord record, Path worktree) {
        IssueRecord done = statusPublisher.publish(issue, record, record.withStatus(IssueStatus.DONE));
        workspace.remove(worktree);
        ownerApproval.withdraw(record.issueNumber());
        gitHub.removeLabel(record.issueNumber(), FACTORY_LABEL);
        return StepResult.stay(done);
    }

    /** A failed review session is not a defect of the code, so the round runs again and no fix loop starts. */
    private StepResult retryReviewRound(IssueRecord record) {
        IssueRecord counted = record.withFailedReviewRound();
        if (counted.failedReviewRounds() >= MAXIMUM_FAILED_REVIEW_ROUNDS) {
            return StepResult.block(counted, "The review sessions failed two times.");
        }
        return StepResult.stay(counted.withNote("A review session failed; the factory runs the reviews again."));
    }

    private IssueRecord block(IssueRecord record, String reason) {
        return record.withStatus(IssueStatus.BLOCKED).withWaitReason(WaitReason.FAILURE).withNote(reason);
    }

    /** The refused session never started, so the record on GitHub must not say that it started. */
    private IssueRecord undoSessionStart(Issue issue, IssueRecord started, IssueRecord record) {
        if (!started.equals(record)) {
            statusPublisher.publish(issue, started, record);
        }
        return record;
    }

    /** Publishes the start before the run, so that a retry resumes the session and does not start it again. */
    private IssueRecord markSessionStarted(Issue issue, IssueRecord record) {
        if (record.agentSessionStarted()) {
            return record;
        }
        return statusPublisher.publish(issue, record, record.withAgentSessionStarted());
    }

    private boolean isMergedOnGitHub(IssueRecord record) {
        return record.pullRequestNumber().map(gitHub::isMerged).orElse(false);
    }

    private String mergeTargetReference() {
        return "origin/" + configuration.mergeTargetBranch();
    }
}
