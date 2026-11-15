package com.meterian.factory.state;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.meterian.factory.testing.RecordStates;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class IssueRecordTest {

    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new Jdk8Module());

    private final IssueRecord queued = RecordStates.queued(3);

    @Test
    void withoutRiskAndWithoutBuiltTreeClearOnlyTheirFields() {
        IssueRecord full = queued.withRisk(RiskLevel.HIGH, true, List.of()).withBuiltTree("built").withPullRequest(9);

        IssueRecord cleared = full.withoutRisk().withoutBuiltTree();

        assertThat(cleared.risk()).isEmpty();
        assertThat(cleared.small()).isFalse();
        assertThat(cleared.builtTreeHash()).isEmpty();
        assertThat(cleared.pullRequestNumber()).contains(9);
    }

    @Test
    void withoutReviewsClearsTheReviewedTree() {
        IssueRecord cleared = queued
                .withReview("security", Verdict.REJECT)
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
    void queuedStartsInTheStartStateWithTheActionPrepare() {
        IssueRecord started = IssueRecord.queued(3, "begin");

        assertThat(started.state()).isEqualTo("begin");
        assertThat(started.action()).contains(ActionType.PREPARE);
        assertThat(started.column()).isEqualTo("queued");
        assertThat(started.previousState()).contains("begin");
        assertThat(started.entries()).isEmpty();
        assertThat(started.pendingPrompt()).isEmpty();
        assertThat(started.actionStarted()).isFalse();
    }

    @Test
    void countsTheEntriesOfEachStateAndClearsThem() {
        IssueRecord counted = queued.withEntryCounted("implement").withEntryCounted("implement")
                .withEntryCounted("review");

        assertThat(counted.entriesOf("implement")).isEqualTo(2);
        assertThat(counted.entriesOf("review")).isEqualTo(1);
        assertThat(counted.entriesOf("build")).isZero();
        assertThat(counted.withoutEntries().entries()).isEmpty();
    }

    @Test
    void withMoveSetsTheStateTheActionAndTheColumnAndForgetsTheStart() {
        IssueRecord moved = queued.withActionStarted().withMove("blocked", Optional.empty(), "blocked");

        assertThat(moved.state()).isEqualTo("blocked");
        assertThat(moved.action()).isEmpty();
        assertThat(moved.column()).isEqualTo("blocked");
        assertThat(moved.actionStarted()).isFalse();
        assertThat(moved.previousState()).isEqualTo(queued.previousState());
    }

    @Test
    void keepsThePreviousStateAndThePendingPrompt() {
        IssueRecord changed = queued.withPreviousState("implement").withPendingPrompt(PendingPrompt.FIX);

        assertThat(changed.previousState()).contains("implement");
        assertThat(changed.pendingPrompt()).contains(PendingPrompt.FIX);
    }

    @Test
    void writesAndReadsTheFieldsOfTheStateMachine() throws Exception {
        IssueRecord record = RecordStates.move(queued, "implement").withEntryCounted("implement")
                .withPendingPrompt(PendingPrompt.RESUME).withActionStarted();

        String json = MAPPER.writeValueAsString(record);

        assertThat(json).contains("\"action\":\"coding\"").contains("\"column\":\"in-progress\"");
        assertThat(MAPPER.readValue(json, IssueRecord.class)).isEqualTo(record);
    }

    @Test
    void readsAStateWithoutAnAction() throws Exception {
        IssueRecord blocked = RecordStates.move(queued, "blocked");

        assertThat(MAPPER.readValue(MAPPER.writeValueAsString(blocked), IssueRecord.class).action()).isEmpty();
    }

    @Test
    void givesTheNewestNote() {
        assertThat(queued.newestNote()).isEmpty();
        assertThat(queued.withNote("a").withNote("b").newestNote()).contains("b");
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

        IssueRecord changed = RecordStates.move(hashed, "review").withoutReviews().withoutRisk().withoutBuiltTree()
                .withSessionId(UUID.randomUUID()).withNote("n").withEntryCounted("review").withActionStarted();

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

    private static final LiveSession SESSION = new LiveSession("abc", Optional.empty(), LivePlace.GITHUB_COMMENT);

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
    void withLiveKeepsTheHeadCommitOfTheStart() {
        IssueRecord live = queued.withLive(SESSION);

        assertThat(live.live().orElseThrow().startHeadCommit()).isEqualTo("abc");
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
    void theAfterConversationFlagSetsAndClears() {
        assertThat(queued.withAfterConversation().afterConversation()).isTrue();
        assertThat(queued.withAfterConversation().withoutAfterConversation().afterConversation()).isFalse();
    }

    @Test
    void withLiveLinkDoesNothingWhenTheIssueIsNotLive() {
        assertThat(queued.withLiveLink("https://claude.ai/code/session_x")).isEqualTo(queued);
        assertThat(queued.withoutLiveLink()).isEqualTo(queued);
    }

    @Test
    void withersKeepTheHandOver() {
        IssueRecord handedOver = queued.withHandOver(HandOver.CONTINUES_PUSHED_BRANCH);

        assertThat(handedOver.withMove("review", Optional.of(ActionType.REVIEWS), "in-review").handOver())
                .contains(HandOver.CONTINUES_PUSHED_BRANCH);
        assertThat(handedOver.withActionStarted().handOver()).contains(HandOver.CONTINUES_PUSHED_BRANCH);
        assertThat(handedOver.withNote("A note").handOver()).contains(HandOver.CONTINUES_PUSHED_BRANCH);
        assertThat(handedOver.withSessionId(UUID.randomUUID()).handOver()).contains(HandOver.CONTINUES_PUSHED_BRANCH);
    }

    @Test
    void withoutHandOverClearsTheHandOver() {
        assertThat(queued.withHandOver(HandOver.RELEASED).withoutHandOver().handOver()).isEmpty();
    }
}
