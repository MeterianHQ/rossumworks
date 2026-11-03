package com.meterian.factory.workflow;

import com.meterian.factory.definition.DefinitionLookup;
import com.meterian.factory.github.Issue;
import com.meterian.factory.state.IssueRecord;
import com.meterian.factory.state.IssueStatus;
import com.meterian.factory.state.WaitReason;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

/** Continues an issue that waits for the label factory:approve: a merge, or a changed issue text. */
public final class ApprovalCheck {

    private static final Set<WaitReason> APPROVABLE_REASONS =
            EnumSet.of(WaitReason.MERGE_NEEDS_APPROVAL, WaitReason.FACTORY_CHANGE_NEEDS_APPROVAL,
                    WaitReason.ISSUE_TEXT_CHANGED);

    private final OwnerApproval ownerApproval;
    private final IssueTextCheck issueTextCheck;
    private final DefinitionLookup definitions;

    ApprovalCheck(OwnerApproval ownerApproval, IssueTextCheck issueTextCheck, DefinitionLookup definitions) {
        this.ownerApproval = ownerApproval;
        this.issueTextCheck = issueTextCheck;
        this.definitions = definitions;
    }

    public static boolean waitsForApproval(IssueRecord record) {
        return record.status() == IssueStatus.WAITS_FOR_YOU
                && record.waitReason().filter(APPROVABLE_REASONS::contains).isPresent();
    }

    /**
     * A label from an account that is not allowed gets a note, and the issue keeps waiting. An approved merge
     * stays in its state and gets the status of the state again.
     */
    IssueRecord continueAfterApproval(Issue issue, IssueRecord record) {
        if (!waitsForApproval(record)) {
            return record;
        }
        if (!ownerApproval.isGiven(issue)) {
            return ownerApproval.noteIgnoredLabel(issue, record);
        }
        if (record.waitReason().equals(Optional.of(WaitReason.ISSUE_TEXT_CHANGED))) {
            return issueTextCheck.acceptNewText(issue, record);
        }
        return record.withoutWaitReason().withStatus(definitions.currentStateOf(record).status());
    }
}
