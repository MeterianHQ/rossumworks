package com.meterian.factory.state;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class IssueRecordTest {

    private final IssueRecord queued = IssueRecord.queued(3);

    @Test
    void withoutRiskAndWithoutBuiltTreeClearOnlyTheirFields() {
        IssueRecord full = queued.withRisk(RiskLevel.HIGH, true).withBuiltTree("built").withPullRequest(9);

        IssueRecord cleared = full.withoutRisk().withoutBuiltTree();

        assertThat(cleared.risk()).isEmpty();
        assertThat(cleared.small()).isFalse();
        assertThat(cleared.builtTreeHash()).isEmpty();
        assertThat(cleared.pullRequestNumber()).contains(9);
    }

    @Test
    void withoutReviewsClearsTheReviewedTree() {
        IssueRecord cleared = queued
                .withReview(ReviewKind.SECURITY, Verdict.REJECT)
                .withReviewedTree("tree", "patch")
                .withBuiltTree("built")
                .withoutReviews();

        assertThat(cleared.reviews()).isEmpty();
        assertThat(cleared.reviewedTreeHash()).isEmpty();
        assertThat(cleared.reviewedPatchId()).isEmpty();
        assertThat(cleared.builtTreeHash()).contains("built");
    }

    @Test
    void withNoteSkipsADuplicate() {
        IssueRecord noted = queued.withNote("a").withNote("a").withNote("b");

        assertThat(noted.notes()).containsExactly("a", "b");
    }

    @Test
    void withFixLoopAddsOne() {
        assertThat(queued.withFixLoop().withFixLoop().fixLoops()).isEqualTo(2);
    }

    @Test
    void withoutWaitReasonClearsTheReason() {
        IssueRecord waiting = queued.withWaitReason(WaitReason.QUESTION);

        assertThat(waiting.waitReason()).contains(WaitReason.QUESTION);
        assertThat(waiting.withoutWaitReason().waitReason()).isEqualTo(Optional.empty());
    }

    @Test
    void withSessionIdReplacesTheSession() {
        UUID session = UUID.randomUUID();

        assertThat(queued.withSessionId(session).sessionId()).isEqualTo(session);
    }

    @Test
    void withSessionIdForgetsThatTheAgentSessionStarted() {
        IssueRecord started = queued.withAgentSessionStarted();

        assertThat(started.agentSessionStarted()).isTrue();
        assertThat(started.withSessionId(UUID.randomUUID()).agentSessionStarted()).isFalse();
    }

    @Test
    void countsAndClearsTheFailedReviewRounds() {
        IssueRecord failedTwice = queued.withFailedReviewRound().withFailedReviewRound();

        assertThat(failedTwice.failedReviewRounds()).isEqualTo(2);
        assertThat(failedTwice.withoutFailedReviewRounds().failedReviewRounds()).isZero();
    }

    @Test
    void keepsAndClearsTheConflictedPaths() {
        IssueRecord withConflict = queued.withConflictedPaths(List.of("a.txt", "café.txt"));

        assertThat(withConflict.conflictedPaths()).containsExactly("a.txt", "café.txt");
        assertThat(withConflict.withoutConflictedPaths().conflictedPaths()).isEmpty();
    }

    @Test
    void keepsTheIssueTextHashThroughTheOtherChanges() {
        IssueRecord hashed = queued.withIssueTextHash("0a1b2c");

        IssueRecord changed = hashed.withPhase(Phase.REVIEW).withoutReviews().withoutRisk().withoutBuiltTree()
                .withSessionId(UUID.randomUUID()).withNote("n").withFixLoop().withStatus(IssueStatus.IN_REVIEW);

        assertThat(queued.issueTextHash()).isEmpty();
        assertThat(changed.issueTextHash()).contains("0a1b2c");
    }
}
