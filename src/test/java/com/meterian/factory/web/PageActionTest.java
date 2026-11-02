package com.meterian.factory.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.meterian.factory.board.IssueCard;
import com.meterian.factory.state.IssueRecord;
import com.meterian.factory.state.IssueStatus;
import com.meterian.factory.state.LivePlace;
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
                .withLive(new LiveSession("abc", IssueStatus.IN_PROGRESS, Optional.empty(), link, LivePlace.PHONE));
    }

    private static IssueCard card(IssueRecord record) {
        return card(record, Set.of("factory"));
    }

    private static IssueCard card(IssueRecord record, Set<String> labels) {
        return new IssueCard(12, "Title", labels, record, Optional.empty());
    }

    /** The rule of the action when the page remembers no request. */
    private static boolean isPossible(PageAction action, IssueCard card, boolean continueRequested) {
        return action.isPossible(card, continueRequested, Set.of());
    }

    @Test
    void enterNeedsAStartedSession() {
        IssueRecord notStarted = IssueRecord.queued(12).withStatus(IssueStatus.IN_PROGRESS);

        assertThat(isPossible(PageAction.ENTER, card(notStarted), false)).isFalse();
        assertThat(isPossible(PageAction.ENTER, card(startedRecord(IssueStatus.IN_PROGRESS, Phase.IMPLEMENT)), false))
                .isTrue();
    }

    @Test
    void enterIsNotPossibleWhenThePhaseIsDone() {
        IssueRecord done = startedRecord(IssueStatus.DONE, Phase.DONE);

        assertThat(isPossible(PageAction.ENTER, card(done), false)).isFalse();
    }

    @Test
    void enterIsNotPossibleWhenTheLiveStateHasALink() {
        assertThat(isPossible(PageAction.ENTER, card(liveRecord(Optional.of(LINK))), false)).isFalse();
    }

    @Test
    void enterIsPossibleAgainWhenTheLiveStateHasNoLink() {
        assertThat(isPossible(PageAction.ENTER, card(liveRecord(Optional.empty())), false)).isTrue();
    }

    @Test
    void approveNeedsAnApprovableReason() {
        IssueRecord merge = startedRecord(IssueStatus.WAITS_FOR_YOU, Phase.MERGE)
                .withWaitReason(WaitReason.MERGE_NEEDS_APPROVAL);
        IssueRecord textChanged = startedRecord(IssueStatus.WAITS_FOR_YOU, Phase.PREPARE)
                .withWaitReason(WaitReason.ISSUE_TEXT_CHANGED);
        IssueRecord question = startedRecord(IssueStatus.WAITS_FOR_YOU, Phase.IMPLEMENT)
                .withWaitReason(WaitReason.QUESTION);

        assertThat(isPossible(PageAction.APPROVE, card(merge), false)).isTrue();
        assertThat(isPossible(PageAction.APPROVE, card(textChanged), false)).isTrue();
        assertThat(isPossible(PageAction.APPROVE, card(question), false)).isFalse();
    }

    @Test
    void blockIsNotPossibleWhenTheIssueIsBlockedOrDone() {
        assertThat(isPossible(PageAction.BLOCK, card(startedRecord(IssueStatus.IN_PROGRESS, Phase.IMPLEMENT)), false))
                .isTrue();
        assertThat(isPossible(PageAction.BLOCK, card(startedRecord(IssueStatus.BLOCKED, Phase.IMPLEMENT)), false))
                .isFalse();
        assertThat(isPossible(PageAction.BLOCK, card(startedRecord(IssueStatus.DONE, Phase.MERGE)), false))
                .isFalse();
        assertThat(isPossible(PageAction.BLOCK, card(startedRecord(IssueStatus.IN_REVIEW, Phase.DONE)), false))
                .isFalse();
    }

    @Test
    void continueNeedsALiveState() {
        assertThat(isPossible(PageAction.CONTINUE, card(liveRecord(Optional.of(LINK))), false)).isTrue();
        assertThat(isPossible(PageAction.CONTINUE, card(startedRecord(IssueStatus.IN_PROGRESS, Phase.IMPLEMENT)),
                false)).isFalse();
    }

    @Test
    void continueHidesWhenTheMarkerExists() {
        assertThat(isPossible(PageAction.CONTINUE, card(liveRecord(Optional.of(LINK))), true)).isFalse();
    }

    @Test
    void aRememberedLabelOnTheCardHidesItsButton() {
        IssueRecord merge = startedRecord(IssueStatus.WAITS_FOR_YOU, Phase.MERGE)
                .withWaitReason(WaitReason.MERGE_NEEDS_APPROVAL);
        Set<String> remembered = Set.of("factory:approve", "factory:block", "factory:enter");

        assertThat(PageAction.APPROVE.isPossible(card(merge, Set.of("factory", "factory:approve")), false, remembered))
                .isFalse();
        assertThat(PageAction.BLOCK.isPossible(card(merge, Set.of("factory", "factory:block")), false, remembered))
                .isFalse();
        assertThat(PageAction.ENTER.isPossible(card(merge, Set.of("factory", "factory:enter")), false, remembered))
                .isFalse();
        assertThat(PageAction.BLOCK.isPossible(card(merge, Set.of("factory", "factory:approve")), false, remembered))
                .isTrue();
    }

    @Test
    void aLabelThatThePageDidNotRequestKeepsItsButton() {
        IssueRecord merge = startedRecord(IssueStatus.WAITS_FOR_YOU, Phase.MERGE)
                .withWaitReason(WaitReason.MERGE_NEEDS_APPROVAL);

        assertThat(isPossible(PageAction.APPROVE, card(merge, Set.of("factory", "factory:approve")), false)).isTrue();
        assertThat(isPossible(PageAction.BLOCK, card(merge, Set.of("factory", "factory:block")), false)).isTrue();
        assertThat(isPossible(PageAction.ENTER, card(merge, Set.of("factory", "factory:enter")), false)).isTrue();
    }

    @Test
    void aRememberedRequestWithoutTheLabelKeepsTheButton() {
        IssueRecord merge = startedRecord(IssueStatus.WAITS_FOR_YOU, Phase.MERGE)
                .withWaitReason(WaitReason.MERGE_NEEDS_APPROVAL);

        assertThat(PageAction.APPROVE.isPossible(card(merge), false, Set.of("factory:approve"))).isTrue();
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
