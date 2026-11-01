package com.meterian.factory.merge;

import static org.assertj.core.api.Assertions.assertThat;

import com.meterian.factory.state.IssueRecord;
import com.meterian.factory.state.ReviewKind;
import com.meterian.factory.state.RiskLevel;
import com.meterian.factory.state.Verdict;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class MergeGateTest {

    private static IssueRecord approvedRecord(RiskLevel risk, boolean small) {
        IssueRecord record = IssueRecord.queued(7).withRisk(risk, small).withReviewedTree("a", "patch").withBuiltTree("a");
        for (ReviewKind kind : ReviewKind.values()) {
            record = record.withReview(kind, Verdict.APPROVE);
        }
        return record;
    }

    @Test
    void mergesALowSmallChangeAutomatically() {
        MergeDecision decision = MergeGate.decide(approvedRecord(RiskLevel.LOW, true), "a", false, false);

        assertThat(decision).isInstanceOf(MergeDecision.Automatic.class);
    }

    @Test
    void refusesWhenTheFilesChangedAfterTheReviews() {
        MergeDecision decision = MergeGate.decide(approvedRecord(RiskLevel.LOW, true), "b", false, false);

        assertThat(decision).isEqualTo(new MergeDecision.Refused("the files changed after the reviews"));
    }

    @Test
    void refusesWhenTheBuildDoesNotMatchTheFiles() {
        IssueRecord record = approvedRecord(RiskLevel.LOW, true).withBuiltTree("c");

        MergeDecision decision = MergeGate.decide(record, "a", false, false);

        assertThat(decision).isEqualTo(new MergeDecision.Refused("the build does not match the files"));
    }

    @Test
    void refusesWhenTheReviewedTreeHashIsMissing() {
        IssueRecord record = approvedRecord(RiskLevel.LOW, true).withoutReviews();
        for (ReviewKind kind : ReviewKind.values()) {
            record = record.withReview(kind, Verdict.APPROVE);
        }

        MergeDecision decision = MergeGate.decide(record, "a", false, false);

        assertThat(decision).isEqualTo(new MergeDecision.Refused("the files changed after the reviews"));
    }

    @Test
    void refusesWhenOneReviewRejects() {
        IssueRecord record = approvedRecord(RiskLevel.LOW, true).withReview(ReviewKind.SECURITY, Verdict.REJECT);

        MergeDecision decision = MergeGate.decide(record, "a", false, true);

        assertThat(decision).isEqualTo(new MergeDecision.Refused("the review SECURITY does not approve"));
    }

    @Test
    void refusesWhenAReviewIsMissing() {
        IssueRecord record = IssueRecord.queued(7).withRisk(RiskLevel.LOW, true).withReviewedTree("a", "p")
                .withBuiltTree("a").withReview(ReviewKind.CORRECTNESS, Verdict.APPROVE);

        MergeDecision decision = MergeGate.decide(record, "a", false, true);

        assertThat(decision).isEqualTo(new MergeDecision.Refused("the review SECURITY does not approve"));
    }

    @Test
    void refusesWithAnOpenQuestionEvenWhenApproved() {
        MergeDecision decision = MergeGate.decide(approvedRecord(RiskLevel.LOW, true), "a", true, true);

        assertThat(decision).isEqualTo(new MergeDecision.Refused("an open question waits"));
    }

    @Test
    void asksTheOwnerForAMediumRisk() {
        MergeDecision decision = MergeGate.decide(approvedRecord(RiskLevel.MEDIUM, true), "a", false, false);

        assertThat(decision).isEqualTo(new MergeDecision.NeedsOwner("The merge needs your approval."));
    }

    @Test
    void asksTheOwnerForALowRiskThatIsNotSmall() {
        MergeDecision decision = MergeGate.decide(approvedRecord(RiskLevel.LOW, false), "a", false, false);

        assertThat(decision).isEqualTo(new MergeDecision.NeedsOwner("The merge needs your approval."));
    }

    @Test
    void asksTheOwnerWhenTheRiskIsUnknown() {
        IssueRecord record = approvedRecord(RiskLevel.LOW, true);
        record = new IssueRecord(record.issueNumber(), record.sessionId(), record.status(), record.phase(),
                record.reviews(), 0, Optional.empty(), true, record.reviewedTreeHash(),
                record.reviewedPatchId(), record.builtTreeHash(), record.waitReason(), record.pullRequestNumber(),
                record.statusCommentId(), record.notes(), record.agentSessionStarted());

        MergeDecision decision = MergeGate.decide(record, "a", false, false);

        assertThat(decision).isEqualTo(new MergeDecision.NeedsOwner("The merge needs your approval."));
    }

    @Test
    void mergesAfterTheApprovalOfTheOwner() {
        MergeDecision decision = MergeGate.decide(approvedRecord(RiskLevel.HIGH, false), "a", false, true);

        assertThat(decision).isInstanceOf(MergeDecision.Approved.class);
    }

    @Test
    void refusesWhenAllTreeHashesAreEmpty() {
        IssueRecord record = approvedRecord(RiskLevel.LOW, true).withReviewedTree("", "p").withBuiltTree("");

        MergeDecision decision = MergeGate.decide(record, "", false, true);

        assertThat(decision).isInstanceOf(MergeDecision.Refused.class);
    }

    @Test
    void refusesWhenTheBuiltTreeHashIsEmpty() {
        IssueRecord record = approvedRecord(RiskLevel.LOW, true).withBuiltTree("");

        MergeDecision decision = MergeGate.decide(record, "a", false, true);

        assertThat(decision).isEqualTo(new MergeDecision.Refused("the build does not match the files"));
    }
}
