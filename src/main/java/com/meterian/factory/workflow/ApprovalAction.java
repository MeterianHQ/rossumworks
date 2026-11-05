package com.meterian.factory.workflow;

import com.meterian.factory.definition.FactoryDefinition;
import com.meterian.factory.git.Git;
import com.meterian.factory.github.Issue;
import com.meterian.factory.state.IssueRecord;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Waits for the label factory:approve (spec 5.5). In the target of issue-text-changed, the label accepts the new
 * text and goes away. In another approval state, the label approves the merge and stays: the merge gate reads it. The approval records the tree of the approved files.
 */
final class ApprovalAction implements Action {

    private static final String APPROVED_EVENT = "approved";

    private final Git git;
    private final OwnerApproval ownerApproval;
    private final IssueTextCheck issueTextCheck;
    private final String textChangedTarget;

    ApprovalAction(Git git, OwnerApproval ownerApproval, IssueTextCheck issueTextCheck, FactoryDefinition definition) {
        this.git = git;
        this.ownerApproval = ownerApproval;
        this.issueTextCheck = issueTextCheck;
        this.textChangedTarget = definition.workflow().onAnyState().issueTextChanged();
    }

    /** Only an approval after the change of the text counts, so an older label goes away. */
    @Override
    public Optional<ActionResult> start(Issue issue, IssueRecord record) {
        if (!isTextChangedTarget(record)) {
            return Optional.empty();
        }
        ownerApproval.withdraw(record.issueNumber());
        return Optional.of(ActionResult.stay(record.withoutApprovedTree()));
    }

    @Override
    public ActionResult run(Issue issue, IssueRecord record, Path worktree) {
        if (!ownerApproval.isGiven(issue)) {
            return ActionResult.stay(record);
        }
        if (isTextChangedTarget(record)) {
            return ActionResult.endWith(issueTextCheck.acceptNewText(issue, record), APPROVED_EVENT);
        }
        return ActionResult.endWith(record.withApprovedTree(git.treeHash(worktree)), APPROVED_EVENT);
    }

    private boolean isTextChangedTarget(IssueRecord record) {
        return record.state().equals(textChangedTarget);
    }
}
