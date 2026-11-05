package com.meterian.factory.workflow;

import com.meterian.factory.github.Issue;
import com.meterian.factory.state.ActionType;
import com.meterian.factory.state.IssueRecord;
import java.util.Optional;

/** Stops the actions that give the issue text to an agent when the text changed after the label factory. */
final class IssueTextCheck {

    static final String CHANGED_NOTE = "The issue text changed after the label factory."
            + " Add the label factory:approve to accept the new text.";

    private final OwnerApproval ownerApproval;

    IssueTextCheck(OwnerApproval ownerApproval) {
        this.ownerApproval = ownerApproval;
    }

    /** Fail closed: a record without a hash counts as a change. */
    boolean hasChanged(Issue issue, IssueRecord record) {
        return record.action().filter(ActionType::usesIssueText).isPresent()
                && !record.issueTextHash().equals(Optional.of(IssueText.computeHash(issue)));
    }

    /** The approval is used up here, so it cannot also approve the merge. */
    IssueRecord acceptNewText(Issue issue, IssueRecord record) {
        ownerApproval.withdraw(record.issueNumber());
        return record.withIssueTextHash(IssueText.computeHash(issue)).withoutApprovedTree();
    }
}
