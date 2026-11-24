package com.meterian.factory.workflow;

import com.meterian.factory.agent.AgentException;
import com.meterian.factory.agent.CredentialMismatchException;
import com.meterian.factory.agent.SessionReport;
import com.meterian.factory.github.Issue;
import com.meterian.factory.prompt.Finding;
import com.meterian.factory.prompt.RiskResult;
import com.meterian.factory.prompt.RiskResultReader;
import com.meterian.factory.state.IssueRecord;
import java.nio.file.Path;
import java.util.List;

final class RiskAction implements Action {

    private final ActionServices services;
    private final VersionServices version;
    private final ReviewSessionRetry reviewSessionRetry;

    RiskAction(ActionServices services, VersionServices version, ReviewSessionRetry reviewSessionRetry) {
        this.services = services;
        this.version = version;
        this.reviewSessionRetry = reviewSessionRetry;
    }

    @Override
    public ActionResult run(Issue issue, IssueRecord record, Path worktree)
            throws AgentException, CredentialMismatchException {
        if (!services.phaseRunner().mayStartPhase()) {
            return ActionResult.stay(record);
        }
        new FactoryFolder(worktree).deleteRiskResult();
        String headCommit = services.git().headCommit(worktree);
        SessionReport report = services.phaseRunner()
                .runInNewSession(record.issueNumber(), worktree, version.prompts().risk(issue), version.allowedTools());
        if (services.git().hasUncommittedChanges(worktree)
                || !services.git().headCommit(worktree).equals(headCommit)) {
            return ActionResult.block(record, "The risk session changed the files.");
        }
        if (report.stopped()) {
            return ActionResult.stay(record);
        }
        if (!report.success()) {
            return reviewSessionRetry.retryReviewRound(record);
        }
        RiskResult risk = RiskResultReader.read(worktree);
        IssueRecord assessed = record.withoutFailedReviewRounds().withRisk(risk.risk(), risk.small(), risk.reasons());
        return ActionResult.endWithFindings(assessed, "assessed", List.of(describeRisk(risk)));
    }

    /** The finding of the event assessed: the title names the risk, the detail has one line for each reason. */
    static Finding describeRisk(RiskResult risk) {
        String size = risk.small() ? "small" : "not small";
        String title = "The risk: " + risk.risk().name().toLowerCase() + ", " + size;
        return new Finding(title, String.join("\n", risk.reasons()));
    }
}
