package com.meterian.factory.state;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class IssueRecordTest {

    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new Jdk8Module());

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

    @Test
    void countsTheFailedForwardChecksOfOneTree() {
        IssueRecord twice = queued.withFailedForwardCheck("aaa").withFailedForwardCheck("aaa");
        IssueRecord otherTree = twice.withFailedForwardCheck("bbb");

        assertThat(twice.failedForwardChecks()).isEqualTo(2);
        assertThat(otherTree.failedForwardChecks()).isEqualTo(1);
        assertThat(otherTree.failedForwardTree()).contains("bbb");
        assertThat(otherTree.withoutFailedForwardChecks().failedForwardChecks()).isZero();
        assertThat(otherTree.withoutFailedForwardChecks().failedForwardTree()).isEmpty();
    }

    @Test
    void forgetsTheReviewedHeadCommitWithTheReviews() {
        IssueRecord reviewed = queued.withReviewedHeadCommit("ccc");

        assertThat(reviewed.reviewedHeadCommit()).contains("ccc");
        assertThat(reviewed.withoutReviews().reviewedHeadCommit()).isEmpty();
    }

    private static final LiveSession SESSION =
            new LiveSession("abc", IssueStatus.IN_REVIEW, Optional.of(WaitReason.MERGE_NEEDS_APPROVAL), Optional.empty(),
                    LivePlace.GITHUB_COMMENT);

    @Test
    void anOldRecordWithoutTheLiveFieldsReadsAsNotLive() throws Exception {
        String json = MAPPER.writeValueAsString(queued)
                .replace(",\"live\":null", "")
                .replace(",\"afterConversation\":false", "");

        IssueRecord old = MAPPER.readValue(json, IssueRecord.class);

        assertThat(json).doesNotContain("live").doesNotContain("afterConversation");
        assertThat(old.live()).isEmpty();
        assertThat(old.afterConversation()).isFalse();
    }

    @Test
    void withLiveKeepsTheStateBeforeTheConversation() {
        IssueRecord live = queued.withLive(SESSION);

        assertThat(live.live().orElseThrow().statusBefore()).isEqualTo(IssueStatus.IN_REVIEW);
        assertThat(live.live().orElseThrow().waitBefore()).contains(WaitReason.MERGE_NEEDS_APPROVAL);
    }

    @Test
    void withLiveLinkSetsOnlyTheLink() {
        IssueRecord linked = queued.withLive(SESSION).withLiveLink("https://claude.ai/code/session_x");

        assertThat(linked.live().orElseThrow().link()).contains("https://claude.ai/code/session_x");
        assertThat(linked.live().orElseThrow().startHeadCommit()).isEqualTo("abc");
        assertThat(linked.withoutLiveLink().live().orElseThrow().link()).isEmpty();
    }

    @Test
    void withoutLiveClearsTheLiveState() {
        assertThat(queued.withLive(SESSION).withoutLive().live()).isEmpty();
    }

    @Test
    void aPhaseOutsideImplementClearsTheAfterConversationFlag() {
        IssueRecord afterConversation = queued.withPhase(Phase.IMPLEMENT).withAfterConversation();

        assertThat(afterConversation.withPhase(Phase.IMPLEMENT).afterConversation()).isTrue();
        assertThat(afterConversation.withPhase(Phase.BUILD).afterConversation()).isFalse();
    }

    @Test
    void theAfterConversationFlagSetsAndClears() {
        assertThat(queued.withAfterConversation().afterConversation()).isTrue();
        assertThat(queued.withAfterConversation().withoutAfterConversation().afterConversation()).isFalse();
    }

    @Test
    void withLiveLinkDoesNothingWhenTheIssueIsNotLive() {
        assertThat(queued.withLiveLink("https://claude.ai/code/session_x")).isEqualTo(queued);
        assertThat(queued.withoutLiveLink()).isEqualTo(queued);
    }
}
