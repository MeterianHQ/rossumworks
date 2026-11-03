package com.meterian.factory.workflow;

import com.meterian.factory.agent.AgentException;
import com.meterian.factory.agent.ApiKeyDetectedException;
import com.meterian.factory.agent.SessionReport;
import com.meterian.factory.github.Issue;
import com.meterian.factory.prompt.RiskResult;
import com.meterian.factory.prompt.RiskResultReader;
import com.meterian.factory.state.IssueRecord;
import java.nio.file.Path;

final class RiskStep implements Step {

    private final StepServices services;
    private final VersionServices version;
    private final ReviewSessionRetry reviewSessionRetry;

    RiskStep(StepServices services, VersionServices version, ReviewSessionRetry reviewSessionRetry) {
        this.services = services;
        this.version = version;
        this.reviewSessionRetry = reviewSessionRetry;
    }

    @Override
    public StepResult run(Issue issue, IssueRecord record, Path worktree)
            throws AgentException, ApiKeyDetectedException {
        if (!services.phaseRunner().mayStartPhase()) {
            return StepResult.stay(record);
        }
        new FactoryFolder(worktree).deleteRiskResult();
        String headCommit = services.git().headCommit(worktree);
        SessionReport report = services.phaseRunner()
                .runInNewSession(record.issueNumber(), worktree, version.prompts().risk(issue), version.allowedTools());
        if (services.git().hasUncommittedChanges(worktree)
                || !services.git().headCommit(worktree).equals(headCommit)) {
            return StepResult.block(record, "The risk session changed the files.");
        }
        if (!report.success()) {
            return reviewSessionRetry.retryReviewRound(record);
        }
        RiskResult risk = RiskResultReader.read(worktree);
        return StepResult.endWith(record.withoutFailedReviewRounds().withRisk(risk.risk(), risk.small()), "assessed");
    }
}
