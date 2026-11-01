package com.meterian.factory.workflow;

import com.meterian.factory.github.Issue;
import com.meterian.factory.state.IssueRecord;
import com.meterian.factory.state.IssueStatus;
import com.meterian.factory.state.Phase;
import com.meterian.factory.state.WaitReason;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

/** Stops the phases that give the issue text to an agent when the text changed after the label factory. */
final class IssueTextCheck {

    private static final Set<Phase> PHASES_WITH_THE_ISSUE_TEXT = EnumSet.of(Phase.IMPLEMENT, Phase.REVIEW, Phase.RISK);

    private final OwnerApproval ownerApproval;

    IssueTextCheck(OwnerApproval ownerApproval) {
        this.ownerApproval = ownerApproval;
    }

    /** Fail closed: a record without a hash counts as a change. */
    boolean hasChanged(Issue issue, IssueRecord record) {
        return PHASES_WITH_THE_ISSUE_TEXT.contains(record.phase())
                && !record.issueTextHash().equals(Optional.of(IssueText.hashOf(issue)));
    }

    /** Only an approval after the change counts, so an older label factory:approve goes away. */
    IssueRecord waitForTheOwner(IssueRecord record) {
        ownerApproval.withdraw(record.issueNumber());
        return record.withStatus(IssueStatus.WAITS_FOR_YOU)
                .withWaitReason(WaitReason.ISSUE_TEXT_CHANGED)
                .withNote(WaitReason.ISSUE_TEXT_CHANGED.sentence());
    }

    /** The approval is used up here, so it cannot also approve the merge. */
    IssueRecord acceptNewText(Issue issue, IssueRecord record) {
        ownerApproval.withdraw(record.issueNumber());
        IssueStatus status = record.phase() == Phase.IMPLEMENT ? IssueStatus.IN_PROGRESS : IssueStatus.IN_REVIEW;
        return record.withIssueTextHash(IssueText.hashOf(issue)).withoutWaitReason().withStatus(status);
    }
}
