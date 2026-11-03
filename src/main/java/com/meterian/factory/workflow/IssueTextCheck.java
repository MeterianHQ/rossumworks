package com.meterian.factory.workflow;

import com.meterian.factory.github.Issue;
import com.meterian.factory.state.IssueRecord;
import com.meterian.factory.state.IssueStatus;
import com.meterian.factory.state.StepType;
import com.meterian.factory.state.WaitReason;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

/** Stops the phases that give the issue text to an agent when the text changed after the label factory. */
final class IssueTextCheck {

    private static final Set<StepType> STEPS_WITH_THE_ISSUE_TEXT =
            EnumSet.of(StepType.AGENT, StepType.REVIEWS, StepType.RISK);

    private final OwnerApproval ownerApproval;

    IssueTextCheck(OwnerApproval ownerApproval) {
        this.ownerApproval = ownerApproval;
    }

    /** Fail closed: a record without a hash counts as a change. */
    boolean hasChanged(Issue issue, IssueRecord record) {
        return STEPS_WITH_THE_ISSUE_TEXT.contains(record.step())
                && !record.issueTextHash().equals(Optional.of(IssueText.computeHash(issue)));
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
        IssueStatus status = record.step() == StepType.AGENT ? IssueStatus.IN_PROGRESS : IssueStatus.IN_REVIEW;
        return record.withIssueTextHash(IssueText.computeHash(issue)).withoutWaitReason().withStatus(status);
    }
}
