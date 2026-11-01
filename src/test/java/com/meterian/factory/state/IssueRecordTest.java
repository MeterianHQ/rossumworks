package com.meterian.factory.state;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class IssueRecordTest {

    private final IssueRecord queued = IssueRecord.queued(3);

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
}
