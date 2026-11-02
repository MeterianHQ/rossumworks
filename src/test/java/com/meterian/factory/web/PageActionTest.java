package com.meterian.factory.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.meterian.factory.board.IssueCard;
import com.meterian.factory.state.IssueRecord;
import com.meterian.factory.state.IssueStatus;
import com.meterian.factory.state.LiveSession;
import com.meterian.factory.state.Phase;
import com.meterian.factory.state.WaitReason;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PageActionTest {

    private static final String LINK = "https://claude.ai/code/session_abc123";

    private static IssueRecord startedRecord(IssueStatus status, Phase phase) {
        return IssueRecord.queued(12).withAgentSessionStarted().withStatus(status).withPhase(phase);
    }

    private static IssueRecord liveRecord(Optional<String> link) {
        return startedRecord(IssueStatus.WAITS_FOR_YOU, Phase.IMPLEMENT)
                .withWaitReason(WaitReason.LIVE_CONVERSATION)
                .withLive(new LiveSession("abc", IssueStatus.IN_PROGRESS, Optional.empty(), link));
    }

    private static IssueCard card(IssueRecord record) {
        return card(record, Set.of("factory"));
    }

    private static IssueCard card(IssueRecord record, Set<String> labels) {
        return new IssueCard(12, "Title", labels, record, Optional.empty());
    }

    @Test
    void enterNeedsAStartedSession() {
        IssueRecord notStarted = IssueRecord.queued(12).withStatus(IssueStatus.IN_PROGRESS);

        assertThat(PageAction.ENTER.isPossible(card(notStarted), false)).isFalse();
        assertThat(PageAction.ENTER.isPossible(card(startedRecord(IssueStatus.IN_PROGRESS, Phase.IMPLEMENT)), false))
                .isTrue();
    }

    @Test
    void enterIsNotPossibleWhenThePhaseIsDone() {
        IssueRecord done = startedRecord(IssueStatus.DONE, Phase.DONE);

        assertThat(PageAction.ENTER.isPossible(card(done), false)).isFalse();
    }

    @Test
    void enterIsNotPossibleWhenTheLiveStateHasALink() {
        assertThat(PageAction.ENTER.isPossible(card(liveRecord(Optional.of(LINK))), false)).isFalse();
    }

    @Test
    void enterIsPossibleAgainWhenTheLiveStateHasNoLink() {
        assertThat(PageAction.ENTER.isPossible(card(liveRecord(Optional.empty())), false)).isTrue();
    }

    @Test
    void approveNeedsAnApprovableReason() {
        IssueRecord merge = startedRecord(IssueStatus.WAITS_FOR_YOU, Phase.MERGE)
                .withWaitReason(WaitReason.MERGE_NEEDS_APPROVAL);
        IssueRecord textChanged = startedRecord(IssueStatus.WAITS_FOR_YOU, Phase.PREPARE)
                .withWaitReason(WaitReason.ISSUE_TEXT_CHANGED);
        IssueRecord question = startedRecord(IssueStatus.WAITS_FOR_YOU, Phase.IMPLEMENT)
                .withWaitReason(WaitReason.QUESTION);

        assertThat(PageAction.APPROVE.isPossible(card(merge), false)).isTrue();
        assertThat(PageAction.APPROVE.isPossible(card(textChanged), false)).isTrue();
        assertThat(PageAction.APPROVE.isPossible(card(question), false)).isFalse();
    }

    @Test
    void blockIsNotPossibleWhenTheIssueIsBlockedOrDone() {
        assertThat(PageAction.BLOCK.isPossible(card(startedRecord(IssueStatus.IN_PROGRESS, Phase.IMPLEMENT)), false))
                .isTrue();
        assertThat(PageAction.BLOCK.isPossible(card(startedRecord(IssueStatus.BLOCKED, Phase.IMPLEMENT)), false))
                .isFalse();
        assertThat(PageAction.BLOCK.isPossible(card(startedRecord(IssueStatus.DONE, Phase.MERGE)), false))
                .isFalse();
        assertThat(PageAction.BLOCK.isPossible(card(startedRecord(IssueStatus.IN_REVIEW, Phase.DONE)), false))
                .isFalse();
    }

    @Test
    void continueNeedsALiveState() {
        assertThat(PageAction.CONTINUE.isPossible(card(liveRecord(Optional.of(LINK))), false)).isTrue();
        assertThat(PageAction.CONTINUE.isPossible(card(startedRecord(IssueStatus.IN_PROGRESS, Phase.IMPLEMENT)),
                false)).isFalse();
    }

    @Test
    void continueHidesWhenTheMarkerExists() {
        assertThat(PageAction.CONTINUE.isPossible(card(liveRecord(Optional.of(LINK))), true)).isFalse();
    }

    @Test
    void aLabelOnTheCardHidesItsButton() {
        IssueRecord merge = startedRecord(IssueStatus.WAITS_FOR_YOU, Phase.MERGE)
                .withWaitReason(WaitReason.MERGE_NEEDS_APPROVAL);

        assertThat(PageAction.APPROVE.isPossible(card(merge, Set.of("factory", "factory:approve")), false)).isFalse();
        assertThat(PageAction.BLOCK.isPossible(card(merge, Set.of("factory", "factory:block")), false)).isFalse();
        assertThat(PageAction.ENTER.isPossible(card(merge, Set.of("factory", "factory:enter")), false)).isFalse();
        assertThat(PageAction.BLOCK.isPossible(card(merge, Set.of("factory", "factory:approve")), false)).isTrue();
    }

    @Test
    void findsAnActionByItsPathName() {
        assertThat(PageAction.findByPathName("approve")).contains(PageAction.APPROVE);
        assertThat(PageAction.findByPathName("continue")).contains(PageAction.CONTINUE);
        assertThat(PageAction.findByPathName("APPROVE")).isEmpty();
        assertThat(PageAction.findByPathName("merge")).isEmpty();
    }

    @Test
    void continueHasNoLabel() {
        assertThat(PageAction.CONTINUE.label()).isEmpty();
        assertThat(PageAction.BLOCK.label()).contains("factory:block");
        assertThat(PageAction.BLOCK.pathName()).isEqualTo("block");
        assertThat(PageAction.BLOCK.buttonText()).isEqualTo("Block");
    }
}
