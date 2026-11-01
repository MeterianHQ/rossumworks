package com.meterian.factory.workflow;

import com.meterian.factory.agent.AgentException;
import com.meterian.factory.agent.AgentRunner;
import com.meterian.factory.agent.ApiKeyDetectedException;
import com.meterian.factory.agent.PhaseRefusedException;
import com.meterian.factory.agent.PhaseRequest;
import com.meterian.factory.agent.SessionReport;
import com.meterian.factory.build.BuildOutcome;
import com.meterian.factory.build.BuildRunner;
import com.meterian.factory.configuration.FactoryConfiguration;
import com.meterian.factory.git.Git;
import com.meterian.factory.git.GitException;
import com.meterian.factory.github.GitHubClient;
import com.meterian.factory.github.Issue;
import com.meterian.factory.github.PullRequest;
import com.meterian.factory.merge.MergeDecision;
import com.meterian.factory.merge.MergeGate;
import com.meterian.factory.merge.MergeResult;
import com.meterian.factory.merge.Merger;
import com.meterian.factory.prompt.Finding;
import com.meterian.factory.prompt.PromptLibrary;
import com.meterian.factory.prompt.RiskResult;
import com.meterian.factory.prompt.RiskResultReader;
import com.meterian.factory.state.IssueRecord;
import com.meterian.factory.state.IssueStatus;
import com.meterian.factory.state.Phase;
import com.meterian.factory.state.WaitReason;
import com.meterian.factory.usage.UsageTracker;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Moves one issue through the phases. Each call of advance runs one step and publishes the new record. */
public final class IssueWorkflow {

    private static final String FACTORY_LABEL = "factory";
    private static final int MAXIMUM_FAILED_REVIEW_ROUNDS = 2;
    private static final Set<IssueStatus> WAITING_STATUSES =
            Set.of(IssueStatus.WAITS_FOR_YOU, IssueStatus.BLOCKED, IssueStatus.DONE);

    private final FactoryConfiguration configuration;
    private final GitHubClient gitHub;
    private final Git git;
    private final PromptLibrary prompts;
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

    public IssueWorkflow(FactoryConfiguration configuration, GitHubClient gitHub, Git git, AgentRunner agentRunner,
                         PromptLibrary prompts, BuildRunner buildRunner, Merger merger, UsageTracker usage,
                         RequestReader requests, WorkspaceLayout layout, String workerLogin) {
        this.configuration = configuration;
        this.gitHub = gitHub;
        this.git = git;
        this.prompts = prompts;
        this.buildRunner = buildRunner;
        this.merger = merger;
        this.ownerApproval = new OwnerApproval(gitHub, requests);
        this.conflictResolution = new ConflictResolution(git, configuration.mergeTargetBranch());
        this.workspace = new IssueWorkspace(configuration, git);
        this.pullRequestPublisher = new PullRequestPublisher(gitHub, git, configuration.mergeTargetBranch());
        this.issueTextCheck = new IssueTextCheck(ownerApproval);
        this.approvalCheck = new ApprovalCheck(ownerApproval, issueTextCheck);
        this.layout = layout;
        this.phaseRunner = new PhaseRunner(agentRunner, usage);
        this.reviewRound = new ReviewRound(phaseRunner, prompts);
        this.statusPublisher = new StatusPublisher(gitHub, workerLogin, configuration.limits().fixLoops());
    }

    public IssueRecord advance(IssueRecord record) throws ApiKeyDetectedException {
        Issue issue = gitHub.issue(record.issueNumber());
        IssueRecord next = runStep(issue, record);
        return statusPublisher.publish(issue, record, next);
    }

    public boolean isWaiting(IssueRecord record) {
        return WAITING_STATUSES.contains(record.status());
    }

    /** A record in phase DONE always runs the finish step: its cleanup can be left from an earlier run. */
    private IssueRecord runStep(Issue issue, IssueRecord record) throws ApiKeyDetectedException {
        try {
            boolean checksApproval = isWaiting(record) && record.phase() != Phase.DONE;
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
        if (record.phase() == Phase.DONE) {
            return MergedIssue.noteFailureAfterTheMerge(record, exception);
        }
        return block(record, reason);
    }

    private IssueRecord runPhase(Issue issue, IssueRecord record) throws AgentException, ApiKeyDetectedException {
        if (issueTextCheck.hasChanged(issue, record)) {
            return issueTextCheck.waitForTheOwner(record);
        }
        Path worktree = layout.worktree(record.issueNumber());
        return switch (record.phase()) {
            case PREPARE -> prepare(record, worktree);
            case IMPLEMENT -> implement(issue, record, worktree);
            case BUILD -> build(issue, record, worktree);
            case REVIEW -> review(issue, record, worktree);
            case RISK -> assessRisk(issue, record, worktree);
            case MERGE -> merge(issue, record, worktree);
            case DONE -> finish(issue, record, worktree);
        };
    }

    private IssueRecord prepare(IssueRecord record, Path worktree) {
        workspace.create(record.issueNumber(), worktree);
        return record.withStatus(IssueStatus.IN_PROGRESS).withPhase(Phase.IMPLEMENT);
    }

    private IssueRecord implement(Issue issue, IssueRecord record, Path worktree)
            throws AgentException, ApiKeyDetectedException {
        if (conflictResolution.hasUnfinishedMergeCommit(worktree)) {
            conflictResolution.finishMergeCommit(worktree, ChangeTitle.describe(issue));
            return record.withoutConflictedPaths().withPhase(Phase.BUILD);
        }
        if (!phaseRunner.mayStartPhase()) {
            return record;
        }
        FactoryFolder factoryFolder = new FactoryFolder(worktree);
        // A new session (also after a restart without the worktree) gets the full task, not the findings.
        boolean isFirstImplement = record.fixLoops() == 0 || !record.agentSessionStarted();
        Optional<String> prompt = isFirstImplement
                ? Optional.of(prompts.implement(issue))
                : factoryFolder.readFixPrompt();
        if (prompt.isEmpty()) {
            return block(record, "The findings of the fix loop are missing.");
        }
        IssueRecord started = markSessionStarted(issue, record);
        SessionReport report;
        try {
            report = phaseRunner.runPhase(new PhaseRequest(record.issueNumber(), started.sessionId(),
                    !record.agentSessionStarted(), worktree, prompt.get()));
        } catch (PhaseRefusedException refused) {
            return undoSessionStart(issue, started, record);
        }
        if (factoryFolder.hasQuestion()) {
            return askOwner(started, factoryFolder.readQuestion());
        }
        if (!report.success()) {
            return block(started, "The agent session failed: " + report.resultText());
        }
        if (git.hasOpenMerge(worktree)) {
            return commitSolvedConflict(issue, started, worktree);
        }
        if (!git.hasUncommittedChanges(worktree)) {
            return block(started, "The agent made no change.");
        }
        git.commitAll(worktree, ChangeTitle.describe(issue));
        return started.withPhase(Phase.BUILD);
    }

    private IssueRecord commitSolvedConflict(Issue issue, IssueRecord record, Path worktree) {
        Optional<Finding> remainingMarkers =
                conflictResolution.findRemainingMarkers(worktree, record.conflictedPaths());
        if (remainingMarkers.isPresent()) {
            return startFixLoop(record, worktree, List.of(remainingMarkers.get()));
        }
        conflictResolution.commitAsOneCommit(worktree, ChangeTitle.describe(issue));
        return record.withoutConflictedPaths().withPhase(Phase.BUILD);
    }

    private IssueRecord build(Issue issue, IssueRecord record, Path worktree) {
        if (git.hasUncommittedChanges(worktree)) {
            return block(record, "The worktree has changes that are not committed.");
        }
        String headCommit = git.headCommit(worktree);
        BuildOutcome outcome = buildRunner.run(worktree, configuration.target());
        if (outcome.stopped()) {
            return record;
        }
        if (git.hasUncommittedChanges(worktree) || !git.headCommit(worktree).equals(headCommit)) {
            return block(record, "The build changed the files.");
        }
        if (!outcome.passed()) {
            return startFixLoop(record, worktree, List.of(new Finding("The build failed", outcome.outputTail())));
        }
        ownerApproval.withdraw(record.issueNumber());
        PullRequest pullRequest = pullRequestPublisher.publish(issue, worktree, headCommit);
        return record.withBuiltTree(git.treeHashOf(worktree, headCommit))
                .withPullRequest(pullRequest.number())
                .withStatus(IssueStatus.IN_REVIEW)
                .withPhase(Phase.REVIEW);
    }

    private IssueRecord review(Issue issue, IssueRecord record, Path worktree)
            throws AgentException, ApiKeyDetectedException {
        if (!phaseRunner.mayStartPhase()) {
            return record;
        }
        String headCommit = git.headCommit(worktree);
        String treeHash = git.treeHashOf(worktree, headCommit);
        ReviewRound.Outcome outcome = reviewRound.review(issue, record, worktree, treeHash);
        if (git.hasUncommittedChanges(worktree) || !git.headCommit(worktree).equals(headCommit)) {
            return block(outcome.record(), "A review changed the files.");
        }
        if (outcome.sessionFailed()) {
            return retryReviewRound(record);
        }
        IssueRecord reviewed = outcome.record().withoutFailedReviewRounds();
        if (!outcome.allApprove()) {
            return startFixLoop(reviewed, worktree, outcome.findings());
        }
        String patchId = git.patchId(worktree, mergeTargetReference());
        return reviewed.withReviewedTree(treeHash, patchId).withPhase(Phase.RISK);
    }

    private IssueRecord assessRisk(Issue issue, IssueRecord record, Path worktree)
            throws AgentException, ApiKeyDetectedException {
        if (!phaseRunner.mayStartPhase()) {
            return record;
        }
        new FactoryFolder(worktree).deleteRiskResult();
        String headCommit = git.headCommit(worktree);
        SessionReport report = phaseRunner.runInNewSession(record.issueNumber(), worktree, prompts.risk(issue));
        if (git.hasUncommittedChanges(worktree) || !git.headCommit(worktree).equals(headCommit)) {
            return block(record, "The risk session changed the files.");
        }
        if (!report.success()) {
            return retryReviewRound(record);
        }
        RiskResult risk = RiskResultReader.read(worktree);
        return record.withoutFailedReviewRounds().withRisk(risk.risk(), risk.small()).withPhase(Phase.MERGE);
    }

    private IssueRecord merge(Issue issue, IssueRecord record, Path worktree) {
        if (isMergedOnGitHub(record)) {
            return record.withBuiltTree(git.treeHash(worktree)).withPhase(Phase.DONE);
        }
        boolean isApproved = ownerApproval.isGiven(issue);
        IssueRecord noted = isApproved ? record : ownerApproval.noteIgnoredLabel(issue, record);
        boolean questionOpen = new FactoryFolder(worktree).hasQuestion();
        MergeDecision decision = MergeGate.decide(noted, git.treeHash(worktree), questionOpen, isApproved);
        return switch (decision) {
            case MergeDecision.NeedsOwner needsOwner ->
                    noted.withStatus(IssueStatus.WAITS_FOR_YOU).withWaitReason(WaitReason.MERGE_NEEDS_APPROVAL);
            case MergeDecision.Refused refused -> block(noted, "The merge gate refuses: " + refused.reason() + ".");
            case MergeDecision.Automatic automatic -> mergePullRequest(noted, worktree);
            case MergeDecision.Approved approved -> mergePullRequest(noted, worktree);
        };
    }

    private IssueRecord mergePullRequest(IssueRecord record, Path worktree) {
        MergeResult result = merger.merge(worktree, record);
        return switch (result) {
            case MergeResult.Merged merged -> merged.note().map(record::withNote).orElse(record)
                    .withBuiltTree(merged.builtTreeHash()).withPhase(Phase.DONE);
            case MergeResult.NeedsNewReviews needsNewReviews -> record.withoutReviews().withPhase(Phase.BUILD);
            case MergeResult.NeedsFix needsFix -> startMergeFix(record, worktree, needsFix);
            case MergeResult.Failed failed -> block(record, "The merge failed: " + failed.reason());
            case MergeResult.Postponed postponed -> record;
        };
    }

    /**
     * Publishes DONE first, then cleans up. The label factory goes away last: after a failure the issue keeps
     * it, so the next poll runs this step again.
     */
    private IssueRecord finish(Issue issue, IssueRecord record, Path worktree) {
        IssueRecord done = statusPublisher.publish(issue, record, record.withStatus(IssueStatus.DONE));
        workspace.remove(worktree);
        ownerApproval.withdraw(record.issueNumber());
        gitHub.removeLabel(record.issueNumber(), FACTORY_LABEL);
        return done;
    }

    private IssueRecord startFixLoop(IssueRecord record, Path worktree, List<Finding> findings) {
        IssueRecord counted = record.withFixLoop();
        if (counted.fixLoops() > configuration.limits().fixLoops()) {
            return counted.withStatus(IssueStatus.BLOCKED).withWaitReason(WaitReason.FIX_LOOP_LIMIT);
        }
        new FactoryFolder(worktree).writeFixPrompt(prompts.fix(findings));
        ownerApproval.withdraw(counted.issueNumber());
        return counted.withStatus(IssueStatus.IN_PROGRESS).withPhase(Phase.IMPLEMENT);
    }

    private IssueRecord startMergeFix(IssueRecord record, Path worktree, MergeResult.NeedsFix needsFix) {
        ConflictResolution.OpenedMerge openedMerge = conflictResolution.openMerge(worktree, needsFix);
        IssueRecord withConflict = record.withConflictedPaths(openedMerge.conflictedPaths());
        return startFixLoop(withConflict, worktree, List.of(openedMerge.finding()));
    }

    /** A failed review session is not a defect of the code, so the round runs again and no fix loop starts. */
    private IssueRecord retryReviewRound(IssueRecord record) {
        IssueRecord counted = record.withFailedReviewRound();
        if (counted.failedReviewRounds() >= MAXIMUM_FAILED_REVIEW_ROUNDS) {
            return block(counted, "The review sessions failed two times.");
        }
        return counted.withNote("A review session failed; the factory runs the reviews again.");
    }

    private IssueRecord askOwner(IssueRecord record, String question) {
        gitHub.createComment(record.issueNumber(), QuestionComment.render(question, configuration.allowedAccounts()));
        return record.withStatus(IssueStatus.WAITS_FOR_YOU).withWaitReason(WaitReason.QUESTION);
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
