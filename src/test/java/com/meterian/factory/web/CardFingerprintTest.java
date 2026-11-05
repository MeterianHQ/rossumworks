package com.meterian.factory.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.meterian.factory.board.IssueCard;
import com.meterian.factory.state.IssueRecord;
import com.meterian.factory.testing.RecordStates;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CardFingerprintTest {

    private static IssueRecord mergeWaitsForApproval() {
        return RecordStates.move(RecordStates.queued(12).withAgentSessionStarted(), "waits-for-approval");
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

        assertThat(fingerprintOf(RecordStates.move(mergeWaitsForApproval(), "text-changed")))
                .isNotEqualTo(original);
        assertThat(fingerprintOf(mergeWaitsForApproval().withEntryCounted("implement"))).isNotEqualTo(original);
        assertThat(fingerprintOf(RecordStates.move(mergeWaitsForApproval(), "implement"))).isNotEqualTo(original);
        assertThat(fingerprintOf(mergeWaitsForApproval().withMove("waits-for-approval", mergeWaitsForApproval().action(),
                "in-review"))).isNotEqualTo(original);
        assertThat(fingerprintOf(mergeWaitsForApproval().withReviewedTree("tree-1", "patch-1")))
                .isNotEqualTo(original);
        assertThat(fingerprintOf(mergeWaitsForApproval().withIssueTextHash("text-1"))).isNotEqualTo(original);
    }
}
