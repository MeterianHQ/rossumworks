package com.meterian.factory.merge;

import static org.assertj.core.api.Assertions.assertThat;

import com.meterian.factory.definition.MergeMode;
import com.meterian.factory.state.IssueRecord;
import com.meterian.factory.testing.RecordStates;
import com.meterian.factory.state.RiskLevel;
import com.meterian.factory.state.Verdict;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class MergeGateTest {

    private static MergeChecks checksOf(List<String> reviewNames) {
        return new MergeChecks(reviewNames, true);
    }

    private static IssueRecord approvedRecord(RiskLevel risk, boolean small) {
        IssueRecord record = RecordStates.queued(7).withRisk(risk, small, List.of()).withReviewedTree("a", "patch").withBuiltTree("a");
        for (String reviewName : RecordStates.REVIEW_NAMES) {
            record = record.withReview(reviewName, Verdict.APPROVE);
        }
        return record;
    }

    @Test
    void mergesALowSmallChangeAutomatically() {
        MergeDecision decision = MergeGate.decide(approvedRecord(RiskLevel.LOW, true), "a", false, false, MergeMode.RISK, checksOf(RecordStates.REVIEW_NAMES));

        assertThat(decision).isInstanceOf(MergeDecision.Automatic.class);
    }

    @Test
    void refusesWhenTheFilesChangedAfterTheReviews() {
        MergeDecision decision = MergeGate.decide(approvedRecord(RiskLevel.LOW, true), "b", false, false, MergeMode.RISK, checksOf(RecordStates.REVIEW_NAMES));

        assertThat(decision).isEqualTo(new MergeDecision.Refused("the files changed after the reviews"));
    }

    @Test
    void refusesWhenTheBuildDoesNotMatchTheFiles() {
        IssueRecord record = approvedRecord(RiskLevel.LOW, true).withBuiltTree("c");

        MergeDecision decision = MergeGate.decide(record, "a", false, false, MergeMode.RISK, checksOf(RecordStates.REVIEW_NAMES));

        assertThat(decision).isEqualTo(new MergeDecision.Refused("the build does not match the files"));
    }

    @Test
    void refusesWhenTheReviewedTreeHashIsMissing() {
        IssueRecord record = approvedRecord(RiskLevel.LOW, true).withoutReviews();
        for (String reviewName : RecordStates.REVIEW_NAMES) {
            record = record.withReview(reviewName, Verdict.APPROVE);
        }

        MergeDecision decision = MergeGate.decide(record, "a", false, false, MergeMode.RISK, checksOf(RecordStates.REVIEW_NAMES));

        assertThat(decision).isEqualTo(new MergeDecision.Refused("the files changed after the reviews"));
    }

    @Test
    void refusesWhenOneReviewRejects() {
        IssueRecord record = approvedRecord(RiskLevel.LOW, true).withReview("security", Verdict.REJECT);

        MergeDecision decision = MergeGate.decide(record, "a", false, true, MergeMode.RISK, checksOf(RecordStates.REVIEW_NAMES));

        assertThat(decision).isEqualTo(new MergeDecision.Refused("the review security does not approve"));
    }

    @Test
    void refusesTheMergeWhenAReviewOfTheDefinitionDoesNotApprove() {
        List<String> names = List.of("security", "style");
        IssueRecord record = RecordStates.queued(7).withRisk(RiskLevel.LOW, true, List.of()).withReviewedTree("a", "p")
                .withBuiltTree("a").withReview("security", Verdict.APPROVE);

        MergeDecision decision = MergeGate.decide(record, "a", false, true, MergeMode.RISK, checksOf(names));

        assertThat(decision).isEqualTo(new MergeDecision.Refused("the review style does not approve"));
    }

    @Test
    void ignoresAReviewThatTheDefinitionDoesNotName() {
        IssueRecord record = RecordStates.queued(7).withRisk(RiskLevel.LOW, true, List.of()).withReviewedTree("a", "p")
                .withBuiltTree("a").withReview("security", Verdict.APPROVE).withReview("old", Verdict.REJECT);

        MergeDecision decision = MergeGate.decide(record, "a", false, false, MergeMode.RISK, checksOf(List.of("security")));

        assertThat(decision).isInstanceOf(MergeDecision.Automatic.class);
    }

    @Test
    void refusesWhenAReviewIsMissing() {
        IssueRecord record = RecordStates.queued(7).withRisk(RiskLevel.LOW, true, List.of()).withReviewedTree("a", "p")
                .withBuiltTree("a").withReview("correctness", Verdict.APPROVE);

        MergeDecision decision = MergeGate.decide(record, "a", false, true, MergeMode.RISK, checksOf(RecordStates.REVIEW_NAMES));

        assertThat(decision).isEqualTo(new MergeDecision.Refused("the review security does not approve"));
    }

    @Test
    void refusesWithAnOpenQuestionEvenWhenApproved() {
        MergeDecision decision = MergeGate.decide(approvedRecord(RiskLevel.LOW, true), "a", true, true, MergeMode.RISK, checksOf(RecordStates.REVIEW_NAMES));

        assertThat(decision).isEqualTo(new MergeDecision.Refused("an open question waits"));
    }

    @Test
    void asksTheOwnerForAMediumRisk() {
        MergeDecision decision = MergeGate.decide(approvedRecord(RiskLevel.MEDIUM, true), "a", false, false, MergeMode.RISK, checksOf(RecordStates.REVIEW_NAMES));

        assertThat(decision).isEqualTo(new MergeDecision.NeedsOwner("The merge needs approval."));
    }

    @Test
    void asksTheOwnerForALowRiskThatIsNotSmall() {
        MergeDecision decision = MergeGate.decide(approvedRecord(RiskLevel.LOW, false), "a", false, false, MergeMode.RISK, checksOf(RecordStates.REVIEW_NAMES));

        assertThat(decision).isEqualTo(new MergeDecision.NeedsOwner("The merge needs approval."));
    }

    @Test
    void asksTheOwnerWhenTheRiskIsUnknown() {
        IssueRecord record = approvedRecord(RiskLevel.LOW, true);
        record = new IssueRecord(record.issueNumber(), record.sessionId(), record.state(), record.action(),
                record.column(), record.previousState(), record.entries(), record.pendingPrompt(),
                record.actionStarted(), record.reviews(), Optional.empty(), true, record.riskReasons(), record.reviewedTreeHash(),
                record.reviewedPatchId(), record.builtTreeHash(), record.pullRequestNumber(),
                record.statusCommentId(), record.notes(), record.agentSessionStarted(),
                record.failedReviewRounds(), record.conflictedPaths(), record.issueTextHash(),
                record.reviewedHeadCommit(), record.failedForwardChecks(), record.failedForwardTree(),
                record.live(), record.afterConversation(), record.definitionVersion(), record.approvedTree(),
                record.handOver());

        MergeDecision decision = MergeGate.decide(record, "a", false, false, MergeMode.RISK, checksOf(RecordStates.REVIEW_NAMES));

        assertThat(decision).isEqualTo(new MergeDecision.NeedsOwner("The merge needs approval."));
    }

    @Test
    void mergesAfterTheApprovalOfTheOwner() {
        MergeDecision decision = MergeGate.decide(approvedRecord(RiskLevel.HIGH, false), "a", false, true, MergeMode.RISK, checksOf(RecordStates.REVIEW_NAMES));

        assertThat(decision).isInstanceOf(MergeDecision.Approved.class);
    }

    @Test
    void refusesWhenAllTreeHashesAreEmpty() {
        IssueRecord record = approvedRecord(RiskLevel.LOW, true).withReviewedTree("", "p").withBuiltTree("");

        MergeDecision decision = MergeGate.decide(record, "", false, true, MergeMode.RISK, checksOf(RecordStates.REVIEW_NAMES));

        assertThat(decision).isInstanceOf(MergeDecision.Refused.class);
    }

    @Test
    void refusesWhenTheBuiltTreeHashIsEmpty() {
        IssueRecord record = approvedRecord(RiskLevel.LOW, true).withBuiltTree("");

        MergeDecision decision = MergeGate.decide(record, "a", false, true, MergeMode.RISK, checksOf(RecordStates.REVIEW_NAMES));

        assertThat(decision).isEqualTo(new MergeDecision.Refused("the build does not match the files"));
    }

    private static final MergeChecks NO_REVIEWS_AND_NO_BUILD = new MergeChecks(List.of(), false);

    @Test
    void mergesWithAutoMergeWithoutReviewsAndBuild() {
        IssueRecord record = RecordStates.queued(7);

        MergeDecision decision = MergeGate.decide(record, "a", false, false, MergeMode.AUTO_MERGE, checksOf(RecordStates.REVIEW_NAMES));

        assertThat(decision).isInstanceOf(MergeDecision.Automatic.class);
    }

    @Test
    void refusesAutoMergeWithAnOpenQuestion() {
        MergeDecision decision = MergeGate.decide(RecordStates.queued(7), "a", true, false, MergeMode.AUTO_MERGE, NO_REVIEWS_AND_NO_BUILD);

        assertThat(decision).isEqualTo(new MergeDecision.Refused("an open question waits"));
    }

    @Test
    void asksAlwaysWithAlwaysAsk() {
        MergeDecision decision = MergeGate.decide(approvedRecord(RiskLevel.LOW, true), "a", false, false, MergeMode.ALWAYS_ASK, checksOf(RecordStates.REVIEW_NAMES));

        assertThat(decision).isEqualTo(new MergeDecision.NeedsOwner("The merge needs approval."));
    }

    @Test
    void mergesWithAlwaysAskAfterTheApproval() {
        MergeDecision decision = MergeGate.decide(approvedRecord(RiskLevel.LOW, true), "a", false, true, MergeMode.ALWAYS_ASK, checksOf(RecordStates.REVIEW_NAMES));

        assertThat(decision).isInstanceOf(MergeDecision.Approved.class);
    }

    @Test
    void skipsTheBuildCheckWithoutABuildState() {
        IssueRecord record = approvedRecord(RiskLevel.LOW, true).withoutBuiltTree();

        MergeDecision decision = MergeGate.decide(record, "a", false, false, MergeMode.RISK, new MergeChecks(RecordStates.REVIEW_NAMES, false));

        assertThat(decision).isInstanceOf(MergeDecision.Automatic.class);
    }

    @Test
    void skipsTheReviewChecksWithoutAReviewsState() {
        IssueRecord record = RecordStates.queued(7).withRisk(RiskLevel.LOW, true, List.of()).withBuiltTree("a");

        MergeDecision decision = MergeGate.decide(record, "a", false, false, MergeMode.RISK, new MergeChecks(List.of(), true));

        assertThat(decision).isInstanceOf(MergeDecision.Automatic.class);
    }

    @Test
    void asksWithoutARiskState() {
        IssueRecord record = RecordStates.queued(7);

        MergeDecision decision = MergeGate.decide(record, "a", false, false, MergeMode.RISK, NO_REVIEWS_AND_NO_BUILD);

        assertThat(decision).isEqualTo(new MergeDecision.NeedsOwner("The merge needs approval."));
    }
}
