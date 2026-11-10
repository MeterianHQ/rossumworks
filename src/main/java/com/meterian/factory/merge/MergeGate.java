package com.meterian.factory.merge;

import com.meterian.factory.definition.MergeMode;
import com.meterian.factory.state.IssueRecord;
import com.meterian.factory.state.RiskLevel;
import com.meterian.factory.state.Verdict;
import java.util.List;
import java.util.Optional;

/** Decides if a change can merge. A missing proof means refuse. */
public final class MergeGate {

    private static final String OWNER_APPROVAL_NEEDED = "The merge needs approval.";

    private MergeGate() {
    }

    /**
     * @param approved true when the owner approved the merge for the current files
     * @param mode     auto-merge checks only the open question; the other modes make the four checks
     * @param checks   the checks that the workflow makes possible
     */
    public static MergeDecision decide(IssueRecord record, String currentTreeHash, boolean questionOpen,
                                       boolean approved, MergeMode mode, MergeChecks checks) {
        if (questionOpen) {
            return new MergeDecision.Refused("an open question waits");
        }
        if (mode == MergeMode.AUTO_MERGE) {
            return new MergeDecision.Automatic();
        }
        Optional<MergeDecision> refusal = findRefusal(record, currentTreeHash, checks);
        if (refusal.isPresent()) {
            return refusal.get();
        }
        if (mode == MergeMode.RISK && isLowRiskAndSmall(record)) {
            return new MergeDecision.Automatic();
        }
        if (approved) {
            return new MergeDecision.Approved();
        }
        return new MergeDecision.NeedsOwner(OWNER_APPROVAL_NEEDED);
    }

    private static Optional<MergeDecision> findRefusal(IssueRecord record, String currentTreeHash, MergeChecks checks) {
        if (checks.hasReviews()) {
            Optional<String> notApproving = findReviewThatDoesNotApprove(record, checks.reviewNames());
            if (notApproving.isPresent()) {
                return refuse("the review " + notApproving.get() + " does not approve");
            }
            if (!isRecordedForTree(record.reviewedTreeHash(), currentTreeHash)) {
                return refuse("the files changed after the reviews");
            }
        }
        if (checks.hasBuild() && !isRecordedForTree(record.builtTreeHash(), currentTreeHash)) {
            return refuse("the build does not match the files");
        }
        return Optional.empty();
    }

    private static Optional<MergeDecision> refuse(String reason) {
        return Optional.of(new MergeDecision.Refused(reason));
    }

    private static boolean isLowRiskAndSmall(IssueRecord record) {
        return record.risk().equals(Optional.of(RiskLevel.LOW)) && record.small();
    }

    private static boolean isRecordedForTree(Optional<String> recordedTreeHash, String currentTreeHash) {
        return !currentTreeHash.isBlank() && recordedTreeHash.filter(currentTreeHash::equals).isPresent();
    }

    private static Optional<String> findReviewThatDoesNotApprove(IssueRecord record, List<String> reviewNames) {
        return reviewNames.stream()
                .filter(reviewName -> record.reviews().get(reviewName) != Verdict.APPROVE)
                .findFirst();
    }
}
