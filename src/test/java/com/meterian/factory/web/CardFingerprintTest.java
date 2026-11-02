package com.meterian.factory.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.meterian.factory.board.IssueCard;
import com.meterian.factory.state.IssueRecord;
import com.meterian.factory.state.IssueStatus;
import com.meterian.factory.state.Phase;
import com.meterian.factory.state.WaitReason;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CardFingerprintTest {

    private static IssueRecord mergeWaitsForApproval() {
        return IssueRecord.queued(12).withAgentSessionStarted().withStatus(IssueStatus.WAITS_FOR_YOU)
                .withPhase(Phase.MERGE).withWaitReason(WaitReason.MERGE_NEEDS_APPROVAL);
    }

    private static IssueCard card(IssueRecord record, Set<String> labels) {
        return new IssueCard(12, "Title", labels, record, Optional.empty());
    }

    private static String fingerprintOf(IssueRecord record) {
        return CardFingerprint.of(card(record, Set.of("factory")));
    }

    @Test
    void isSixteenHexadecimalCharacters() {
        assertThat(fingerprintOf(mergeWaitsForApproval())).matches("[0-9a-f]{16}");
    }

    @Test
    void isTheSameForTheSameRecord() {
        assertThat(fingerprintOf(mergeWaitsForApproval())).isEqualTo(fingerprintOf(mergeWaitsForApproval()));
    }

    @Test
    void doesNotDependOnTheLabels() {
        IssueRecord record = mergeWaitsForApproval();

        assertThat(CardFingerprint.of(card(record, Set.of("factory", "factory:approve"))))
                .isEqualTo(CardFingerprint.of(card(record, Set.of("factory"))));
    }

    @Test
    void changesWhenTheThingToApproveChanges() {
        String original = fingerprintOf(mergeWaitsForApproval());

        assertThat(fingerprintOf(mergeWaitsForApproval().withWaitReason(WaitReason.ISSUE_TEXT_CHANGED)))
                .isNotEqualTo(original);
        assertThat(fingerprintOf(mergeWaitsForApproval().withFixLoop())).isNotEqualTo(original);
        assertThat(fingerprintOf(mergeWaitsForApproval().withPhase(Phase.IMPLEMENT))).isNotEqualTo(original);
        assertThat(fingerprintOf(mergeWaitsForApproval().withStatus(IssueStatus.IN_REVIEW))).isNotEqualTo(original);
        assertThat(fingerprintOf(mergeWaitsForApproval().withReviewedTree("tree-1", "patch-1")))
                .isNotEqualTo(original);
        assertThat(fingerprintOf(mergeWaitsForApproval().withIssueTextHash("text-1"))).isNotEqualTo(original);
    }
}
