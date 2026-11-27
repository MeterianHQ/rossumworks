package com.meterian.factory.workflow;

import com.meterian.factory.agent.AgentException;
import com.meterian.factory.agent.CredentialMismatchException;
import com.meterian.factory.agent.PhaseRefusedException;
import com.meterian.factory.agent.PhaseRequest;
import com.meterian.factory.agent.SessionReport;
import com.meterian.factory.github.Issue;
import com.meterian.factory.prompt.Finding;
import com.meterian.factory.state.IssueRecord;
import com.meterian.factory.state.PendingPrompt;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

final class CodingAction implements Action {

    static final String QUESTION_NOTE = "The agent has a question.";
    /** The issue must not stay trapped: after the block, continue runs the implement prompt. */
    static final String MISSING_FINDINGS_NOTE = "The findings of the pending prompt are missing."
            + " The next continue runs the implement prompt.";

    private final ActionServices services;
    private final VersionServices version;

    CodingAction(ActionServices services, VersionServices version) {
        this.services = services;
        this.version = version;
    }

    @Override
    public ActionResult run(Issue issue, IssueRecord record, Path worktree)
            throws AgentException, CredentialMismatchException {
        if (version.conflictResolution().hasUnfinishedMergeCommit(worktree)) {
            version.conflictResolution().finishMergeCommit(worktree, ChangeTitle.describe(issue));
            return sendChanged(record.withoutConflictedPaths());
        }
        if (!services.phaseRunner().mayStartPhase()) {
            return ActionResult.stay(record);
        }
        FactoryFolder factoryFolder = new FactoryFolder(worktree);
        Optional<ImplementPrompt.Choice> prompt =
                ImplementPrompt.choose(version.prompts(), issue, record, factoryFolder);
        if (prompt.isEmpty()) {
            return ActionResult.block(record.withPendingPrompt(PendingPrompt.IMPLEMENT), MISSING_FINDINGS_NOTE);
        }
        ActionResult result = runAgentSession(issue, record, worktree, factoryFolder, prompt.get().text());
        return addPromptName(result, prompt.get().name());
    }

    /** Only an action that runs an agent session gives the name of its prompt to the event action-end. */
    private static ActionResult addPromptName(ActionResult result, String promptName) {
        if (!(result instanceof ActionResult.Event event)) {
            return result;
        }
        Map<String, Object> actionFields = new LinkedHashMap<>(event.actionFields());
        actionFields.put("prompt", promptName);
        return event.withActionFields(actionFields);
    }

    private ActionResult runAgentSession(Issue issue, IssueRecord record, Path worktree, FactoryFolder factoryFolder,
            String prompt) throws AgentException, CredentialMismatchException {
        IssueRecord started = markSessionStarted(issue, record);
        SessionReport report;
        try {
            report = services.phaseRunner().runPhase(new PhaseRequest(record.issueNumber(), started.sessionId(),
                    !record.agentSessionStarted(), worktree, prompt, version.allowedTools()));
        } catch (PhaseRefusedException refused) {
            undoSessionStart(issue, started, record);
            throw refused;
        }
        IssueRecord ran = started.withoutAfterConversation();
        if (factoryFolder.hasQuestion()) {
            return askTheOwner(issue, ran, factoryFolder.readQuestion());
        }
        if (report.stopped()) {
            return ActionResult.stay(keepSessionThatCanResume(issue, record, started, worktree));
        }
        if (!report.success()) {
            return ActionResult.endWithReason(ran, "failed", "The agent session failed: " + report.resultText());
        }
        if (services.git().hasOpenMerge(worktree)) {
            return commitSolvedConflict(issue, ran, worktree);
        }
        if (!services.git().hasUncommittedChanges(worktree)) {
            return hasUnbuiltCommit(record, worktree) ? sendChanged(ran)
                    : ActionResult.endWithReason(ran, "no-change", "The agent made no change.");
        }
        services.git().commitAll(worktree, ChangeTitle.describe(issue));
        return sendChanged(ran);
    }

    /** The owner answers in the live conversation. The question is also a comment on the issue. */
    private ActionResult askTheOwner(Issue issue, IssueRecord record, String question) {
        services.gitHub().createComment(issue.number(), QuestionComment.render(question));
        services.events().writeQuestion(issue.number(), question);
        return ActionResult.endWithReason(record, "question", QUESTION_NOTE);
    }

    /** An approval and a risk result are for one change, so a new change removes them. */
    private ActionResult sendChanged(IssueRecord record) {
        services.ownerApproval().withdraw(record.issueNumber());
        return ActionResult.endWith(record.withoutApprovedTree().withoutRisk(), "changed");
    }

    /**
     * A run of the action can commit its change and then lose its record, for example on a GitHub rate limit. The
     * commit is the change of the phase when it is on the branch only and the factory did not build its tree.
     */
    private boolean hasUnbuiltCommit(IssueRecord record, Path worktree) {
        String headCommit = services.git().headCommit(worktree);
        boolean isOnTheBranchOnly = !services.git().isAncestor(worktree, headCommit,
                version.mergeTargetReference());
        boolean isBuilt = record.builtTreeHash()
                .equals(Optional.of(services.git().treeHashOf(worktree, headCommit)));
        return isOnTheBranchOnly && !isBuilt;
    }

    private ActionResult commitSolvedConflict(Issue issue, IssueRecord record, Path worktree) {
        Optional<Finding> remainingMarkers =
                version.conflictResolution().findRemainingMarkers(worktree, record.conflictedPaths());
        if (remainingMarkers.isPresent()) {
            return ActionResult.endWithFindings(record, "conflict-left", List.of(remainingMarkers.get()));
        }
        version.conflictResolution().commitAsOneCommit(worktree, ChangeTitle.describe(issue));
        return sendChanged(record.withoutConflictedPaths());
    }

    /** The refused session never started, so the record on GitHub must not say that it started. */
    private IssueRecord undoSessionStart(Issue issue, IssueRecord started, IssueRecord record) {
        if (!started.equals(record)) {
            services.statusPublisher().publish(issue, started, record);
        }
        return record;
    }

    /** A first session that stopped before claude saved it cannot resume, so the next run starts a new session. */
    private IssueRecord keepSessionThatCanResume(Issue issue, IssueRecord record, IssueRecord started, Path worktree) {
        boolean isLost = !record.agentSessionStarted()
                && !services.phaseRunner().hasSavedSession(started.sessionId(), worktree);
        return isLost ? undoSessionStart(issue, started, record) : started;
    }

    /** Publishes the start before the run, so that a retry resumes the session and does not start it again. */
    private IssueRecord markSessionStarted(Issue issue, IssueRecord record) {
        if (record.agentSessionStarted()) {
            return record;
        }
        return services.statusPublisher().publish(issue, record, record.withAgentSessionStarted());
    }
}
