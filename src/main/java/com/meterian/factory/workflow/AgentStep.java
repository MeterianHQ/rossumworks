package com.meterian.factory.workflow;

import com.meterian.factory.agent.AgentException;
import com.meterian.factory.agent.ApiKeyDetectedException;
import com.meterian.factory.agent.PhaseRefusedException;
import com.meterian.factory.agent.PhaseRequest;
import com.meterian.factory.agent.SessionReport;
import com.meterian.factory.github.Issue;
import com.meterian.factory.prompt.Finding;
import com.meterian.factory.state.IssueRecord;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

final class AgentStep implements Step {

    private final StepServices services;

    AgentStep(StepServices services) {
        this.services = services;
    }

    @Override
    public StepResult run(Issue issue, IssueRecord record, Path worktree)
            throws AgentException, ApiKeyDetectedException {
        if (services.conflictResolution().hasUnfinishedMergeCommit(worktree)) {
            services.conflictResolution().finishMergeCommit(worktree, ChangeTitle.describe(issue));
            return StepResult.endWith(record.withoutConflictedPaths(), "changed");
        }
        if (!services.phaseRunner().mayStartPhase()) {
            return StepResult.stay(record);
        }
        FactoryFolder factoryFolder = new FactoryFolder(worktree);
        Optional<String> prompt = ImplementPrompt.choose(services.prompts(), issue, record, factoryFolder);
        if (prompt.isEmpty()) {
            return StepResult.block(record, "The findings of the fix loop are missing.");
        }
        IssueRecord started = markSessionStarted(issue, record);
        SessionReport report;
        try {
            report = services.phaseRunner().runPhase(new PhaseRequest(record.issueNumber(), started.sessionId(),
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
        if (services.git().hasOpenMerge(worktree)) {
            return commitSolvedConflict(issue, ran, worktree);
        }
        if (!services.git().hasUncommittedChanges(worktree)) {
            return hasUnbuiltCommit(record, worktree) ? StepResult.endWith(ran, "changed")
                    : StepResult.endWithReason(ran, "no-change", "The agent made no change.");
        }
        services.git().commitAll(worktree, ChangeTitle.describe(issue));
        return StepResult.endWith(ran, "changed");
    }

    /**
     * A run of the phase can commit its change and then lose its record, for example on a GitHub rate limit. The
     * commit is the change of the phase when it is on the branch only and the factory did not build its tree.
     */
    private boolean hasUnbuiltCommit(IssueRecord record, Path worktree) {
        String headCommit = services.git().headCommit(worktree);
        boolean isOnTheBranchOnly = !services.git().isAncestor(worktree, headCommit,
                services.mergeTargetReference());
        boolean isBuilt = record.builtTreeHash()
                .equals(Optional.of(services.git().treeHashOf(worktree, headCommit)));
        return isOnTheBranchOnly && !isBuilt;
    }

    private StepResult commitSolvedConflict(Issue issue, IssueRecord record, Path worktree) {
        Optional<Finding> remainingMarkers =
                services.conflictResolution().findRemainingMarkers(worktree, record.conflictedPaths());
        if (remainingMarkers.isPresent()) {
            return StepResult.endWithFindings(record, "conflict-left", List.of(remainingMarkers.get()));
        }
        services.conflictResolution().commitAsOneCommit(worktree, ChangeTitle.describe(issue));
        return StepResult.endWith(record.withoutConflictedPaths(), "changed");
    }

    /** The refused session never started, so the record on GitHub must not say that it started. */
    private IssueRecord undoSessionStart(Issue issue, IssueRecord started, IssueRecord record) {
        if (!started.equals(record)) {
            services.statusPublisher().publish(issue, started, record);
        }
        return record;
    }

    /** Publishes the start before the run, so that a retry resumes the session and does not start it again. */
    private IssueRecord markSessionStarted(Issue issue, IssueRecord record) {
        if (record.agentSessionStarted()) {
            return record;
        }
        return services.statusPublisher().publish(issue, record, record.withAgentSessionStarted());
    }
}
