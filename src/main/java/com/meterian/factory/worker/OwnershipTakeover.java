package com.meterian.factory.worker;

import com.meterian.factory.definition.DefinitionLookup;
import com.meterian.factory.definition.WorkflowDefinition;
import com.meterian.factory.github.GitHubClient;
import com.meterian.factory.github.Issue;
import com.meterian.factory.github.PullRequest;
import com.meterian.factory.state.ActionType;
import com.meterian.factory.state.HandOver;
import com.meterian.factory.state.IssueRecord;
import com.meterian.factory.workflow.OnAnyStateMoves;
import com.meterian.factory.workflow.StatusPublisher;
import com.meterian.factory.workflow.WorkspaceLayout;
import java.util.Optional;

/**
 * Writes the first record of a new owner in a new status comment. The record continues the pushed branch and keeps
 * a block or an approval wait of the earlier record, in the matching state of the current workflow.
 */
final class OwnershipTakeover {

    private static final String OPEN_QUESTION_NOTE =
            "The earlier owner had an open question. The question stays on the machine of the earlier owner.";
    private static final String NEEDS_APPROVAL_EVENT = "needs-approval";

    private final GitHubClient gitHub;
    private final StatusPublisher statusPublisher;
    private final String workerLogin;
    private final DefinitionLookup definitions;
    private final OnAnyStateMoves onAnyStateMoves;
    private final WorkerMove workerMove;

    OwnershipTakeover(GitHubClient gitHub, StatusPublisher statusPublisher, String workerLogin,
                      DefinitionLookup definitions, OnAnyStateMoves onAnyStateMoves, WorkerMove workerMove) {
        this.workerMove = workerMove;
        this.gitHub = gitHub;
        this.statusPublisher = statusPublisher;
        this.workerLogin = workerLogin;
        this.definitions = definitions;
        this.onAnyStateMoves = onAnyStateMoves;
    }

    /**
     * Writes the first record of the new owner. The caller can look at the record before it calls
     * {@link #publish(Issue, IssueRecord)}, for example to find if the record holds a slot.
     *
     * @param queued a new record in the start state of the current definition version
     */
    IssueRecord prepare(Issue issue, IssueRecord queued, Optional<IssueRecord> earlierRecord) {
        IssueRecord record = keepAcceptedIssueText(queued, earlierRecord);
        record = continuePushedBranch(issue.number(), record)
                .withNote("The factory of " + workerLogin + " continues the work of an earlier owner.");
        if (earlierRecord.isPresent()) {
            record = keepBlockOrWait(record, earlierRecord.get());
        }
        return record;
    }

    /** The new status comment is the newest one of the worker, so the next poll does not take over again. */
    IssueRecord publish(Issue issue, IssueRecord prepared) {
        return statusPublisher.publishInNewComment(issue, prepared);
    }

    IssueRecord takeOver(Issue issue, IssueRecord queued, Optional<IssueRecord> earlierRecord) {
        return publish(issue, prepare(issue, queued, earlierRecord));
    }

    /**
     * Only the text that an earlier owner accepted counts. Fail closed: without its hash, the issue text check
     * stops the next action until the owner approves the text.
     */
    private static IssueRecord keepAcceptedIssueText(IssueRecord record, Optional<IssueRecord> earlierRecord) {
        return earlierRecord.flatMap(IssueRecord::issueTextHash).map(record::withIssueTextHash).orElse(record);
    }

    private IssueRecord continuePushedBranch(int issueNumber, IssueRecord record) {
        String branch = WorkspaceLayout.branch(issueNumber);
        if (gitHub.findBranchHead(branch).isEmpty()) {
            return record;
        }
        IssueRecord continuing = record.withHandOver(HandOver.CONTINUES_PUSHED_BRANCH);
        return gitHub.openPullRequest(branch).map(PullRequest::number).map(continuing::withPullRequest)
                .orElse(continuing);
    }

    /**
     * Only the block or the wait passes on. The other fields of an earlier record stay behind. A state without an
     * action waits for a continue, so it becomes the block target.
     */
    private IssueRecord keepBlockOrWait(IssueRecord record, IssueRecord earlierRecord) {
        if (isWaitOnTheEarlierMachine(earlierRecord)) {
            return record.withNote(OPEN_QUESTION_NOTE);
        }
        if (earlierRecord.action().isEmpty()) {
            return onAnyStateMoves.moveToBlockTarget(record, record.newestNote().orElseThrow());
        }
        if (earlierRecord.action().equals(Optional.of(ActionType.APPROVAL))) {
            return keepApprovalWait(record, earlierRecord);
        }
        return record;
    }

    /** A conversation needs the session and the worktree of the earlier owner, so the new owner cannot keep it. */
    private static boolean isWaitOnTheEarlierMachine(IssueRecord earlierRecord) {
        return earlierRecord.action().equals(Optional.of(ActionType.CONVERSATION)) || earlierRecord.live().isPresent();
    }

    /** An approval of the merge without the pushed branch has no pull request to merge, so the work starts again. */
    private IssueRecord keepApprovalWait(IssueRecord record, IssueRecord earlierRecord) {
        WorkflowDefinition workflow = definitions.definitionOf(record).workflow();
        Optional<WorkflowDefinition> earlierWorkflow = findWorkflowOf(earlierRecord);
        String earlierTextChangedTarget = earlierWorkflow.orElse(workflow).onAnyState().issueTextChanged();
        if (earlierRecord.state().equals(earlierTextChangedTarget)) {
            return onAnyStateMoves.moveToTextChangedTarget(record);
        }
        if (record.handOver().isEmpty()) {
            return record;
        }
        return findMergeGateEvent(earlierWorkflow, earlierRecord.state())
                .flatMap(workflow::findMergeGateTarget).map(workflow::state)
                .map(approvalState -> workerMove.moveTo(workflow, record, approvalState))
                .orElse(record);
    }

    /**
     * An approval state that no merge gate enters is not a wait for the merge, so the new owner continues its work.
     * Without the earlier workflow, the wait is the usual approval of the merge.
     */
    private static Optional<String> findMergeGateEvent(Optional<WorkflowDefinition> earlierWorkflow,
                                                       String earlierState) {
        if (earlierWorkflow.isEmpty()) {
            return Optional.of(NEEDS_APPROVAL_EVENT);
        }
        return earlierWorkflow.get().findMergeGateEventTo(earlierState);
    }

    /** The definition of an earlier record can fail to load. Then the current workflow gives the names. */
    private Optional<WorkflowDefinition> findWorkflowOf(IssueRecord earlierRecord) {
        try {
            return Optional.of(definitions.definitionOf(earlierRecord).workflow());
        } catch (IllegalStateException exception) {
            return Optional.empty();
        }
    }
}
