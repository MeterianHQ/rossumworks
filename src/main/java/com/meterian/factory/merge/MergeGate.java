package com.meterian.factory.merge;

import com.meterian.factory.state.IssueRecord;
import com.meterian.factory.state.ReviewKind;
import com.meterian.factory.state.RiskLevel;
import com.meterian.factory.state.Verdict;
import java.util.Optional;

/** Decides if a change can merge. A missing proof means refuse. */
public final class MergeGate {

    private static final String OWNER_APPROVAL_NEEDED = "The merge needs your approval.";

    private MergeGate() {
    }

    public static MergeDecision decide(IssueRecord record, String currentTreeHash, boolean questionOpen,
                                       boolean ownerApproved) {
        if (questionOpen) {
            return new MergeDecision.Refused("an open question waits");
        }
        Optional<ReviewKind> notApproving = findReviewThatDoesNotApprove(record);
        if (notApproving.isPresent()) {
            return new MergeDecision.Refused("the review " + notApproving.get() + " does not approve");
        }
        if (!record.reviewedTreeHash().equals(Optional.of(currentTreeHash))) {
            return new MergeDecision.Refused("the files changed after the reviews");
        }
        if (!record.builtTreeHash().equals(Optional.of(currentTreeHash))) {
            return new MergeDecision.Refused("the build does not match the files");
        }
        if (record.risk().equals(Optional.of(RiskLevel.LOW)) && record.small()) {
            return new MergeDecision.Automatic();
        }
        if (ownerApproved) {
            return new MergeDecision.Approved();
        }
        return new MergeDecision.NeedsOwner(OWNER_APPROVAL_NEEDED);
    }

    private static Optional<ReviewKind> findReviewThatDoesNotApprove(IssueRecord record) {
        for (ReviewKind kind : ReviewKind.values()) {
            if (record.reviews().get(kind) != Verdict.APPROVE) {
                return Optional.of(kind);
            }
        }
        return Optional.empty();
    }
}
