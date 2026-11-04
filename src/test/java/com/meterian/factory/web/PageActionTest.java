package com.meterian.factory.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.meterian.factory.board.IssueCard;
import com.meterian.factory.state.IssueRecord;
import com.meterian.factory.state.IssueStatus;
import com.meterian.factory.state.LivePlace;
import com.meterian.factory.state.LiveSession;
import com.meterian.factory.state.Phase;
import com.meterian.factory.state.WaitReason;
import java.util.Arrays;
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
        return action.isPossible(card, continueRequested, Set.of(), true);
    }

    /** The rule of the action for a viewer on this machine, without a continue request. */
    private static boolean isPossibleOnThisMachine(PageAction action, IssueCard card, Set<String> rememberedLabels) {
        return action.isPossible(card, false, rememberedLabels, true);
    }

    @Test
    void enterNeedsAStartedSession() {
        IssueRecord notStarted = IssueRecord.queued(12).withStatus(IssueStatus.IN_PROGRESS);
        IssueRecord started = startedRecord(IssueStatus.IN_PROGRESS, Phase.IMPLEMENT);

        assertThat(isPossible(PageAction.ENTER_PHONE, card(notStarted), false)).isFalse();
        assertThat(isPossible(PageAction.ENTER_PHONE, card(started), false)).isTrue();
    }

    @Test
    void enterIsNotPossibleWhenThePhaseIsDone() {
        IssueRecord done = startedRecord(IssueStatus.DONE, Phase.DONE);

        assertThat(isPossible(PageAction.ENTER_PHONE, card(done), false)).isFalse();
    }

    @Test
    void enterIsNotPossibleWhenTheLiveStateHasALink() {
        assertThat(isPossible(PageAction.ENTER_PHONE, card(liveRecord(Optional.of(LINK))), false)).isFalse();
    }

    @Test
    void enterIsPossibleAgainWhenTheLiveStateHasNoLink() {
        assertThat(isPossible(PageAction.ENTER_PHONE, card(liveRecord(Optional.empty())), false)).isTrue();
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

        assertThat(isPossibleOnThisMachine(PageAction.APPROVE, card(merge, Set.of("factory", "factory:approve")),
                remembered)).isFalse();
        assertThat(isPossibleOnThisMachine(PageAction.BLOCK, card(merge, Set.of("factory", "factory:block")),
                remembered)).isFalse();
        assertThat(isPossibleOnThisMachine(PageAction.ENTER_PHONE, card(merge, Set.of("factory", "factory:enter")),
                remembered)).isFalse();
        assertThat(isPossibleOnThisMachine(PageAction.BLOCK, card(merge, Set.of("factory", "factory:approve")),
                remembered)).isTrue();
    }

    @Test
    void aLabelThatThePageDidNotRequestKeepsItsButton() {
        IssueRecord merge = startedRecord(IssueStatus.WAITS_FOR_YOU, Phase.MERGE)
                .withWaitReason(WaitReason.MERGE_NEEDS_APPROVAL);

        assertThat(isPossible(PageAction.APPROVE, card(merge, Set.of("factory", "factory:approve")), false)).isTrue();
        assertThat(isPossible(PageAction.BLOCK, card(merge, Set.of("factory", "factory:block")), false)).isTrue();
        assertThat(isPossible(PageAction.ENTER_PHONE, card(merge, Set.of("factory", "factory:enter")), false)).isTrue();
    }

    @Test
    void aRememberedRequestWithoutTheLabelKeepsTheButton() {
        IssueRecord merge = startedRecord(IssueStatus.WAITS_FOR_YOU, Phase.MERGE)
                .withWaitReason(WaitReason.MERGE_NEEDS_APPROVAL);

        assertThat(isPossibleOnThisMachine(PageAction.APPROVE, card(merge), Set.of("factory:approve"))).isTrue();
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

    @Test
    void onlyTheTerminalActionsNeedThisMachine() {
        assertThat(Arrays.stream(PageAction.values()).filter(PageAction::needsThisMachine))
                .containsExactly(PageAction.ENTER_TERMINAL, PageAction.OPEN_TERMINAL);
    }

    @Test
    void aTerminalActionIsNotPossibleFromAnotherDevice() {
        IssueCard started = card(startedRecord(IssueStatus.IN_PROGRESS, Phase.IMPLEMENT));
        IssueCard live = card(liveRecord(Optional.of(LINK)));

        assertThat(PageAction.ENTER_TERMINAL.isPossible(started, false, Set.of(), false)).isFalse();
        assertThat(PageAction.OPEN_TERMINAL.isPossible(live, false, Set.of(), false)).isFalse();
        assertThat(PageAction.ENTER_PHONE.isPossible(started, false, Set.of(), false)).isTrue();
        assertThat(PageAction.ENTER_BROWSER.isPossible(started, false, Set.of(), false)).isTrue();
        assertThat(PageAction.SEND_TO_PHONE.isPossible(live, false, Set.of(), false)).isTrue();
    }

    @Test
    void theThreeEnterActionsHaveTheSameRule() {
        IssueCard started = card(startedRecord(IssueStatus.IN_PROGRESS, Phase.IMPLEMENT));
        IssueCard live = card(liveRecord(Optional.of(LINK)));

        assertThat(isPossible(PageAction.ENTER_TERMINAL, started, false)).isTrue();
        assertThat(isPossible(PageAction.ENTER_BROWSER, started, false)).isTrue();
        assertThat(isPossible(PageAction.ENTER_TERMINAL, live, false)).isFalse();
        assertThat(isPossible(PageAction.ENTER_BROWSER, live, false)).isFalse();
    }

    @Test
    void openTerminalNeedsALiveLink() {
        assertThat(isPossible(PageAction.OPEN_TERMINAL, card(liveRecord(Optional.of(LINK))), false)).isTrue();
        assertThat(isPossible(PageAction.OPEN_TERMINAL, card(liveRecord(Optional.empty())), false)).isFalse();
        assertThat(isPossible(PageAction.OPEN_TERMINAL, card(startedRecord(IssueStatus.IN_PROGRESS, Phase.IMPLEMENT)),
                false)).isFalse();
    }

    @Test
    void openTerminalIsNotPossibleWithAContinueRequest() {
        assertThat(isPossible(PageAction.OPEN_TERMINAL, card(liveRecord(Optional.of(LINK))), true)).isFalse();
    }

    @Test
    void sendToPhoneNeedsARemoteControlLink() {
        assertThat(isPossible(PageAction.SEND_TO_PHONE, card(liveRecord(Optional.of(LINK))), false)).isTrue();
        assertThat(isPossible(PageAction.SEND_TO_PHONE, card(liveRecord(Optional.empty())), false)).isFalse();
        assertThat(isPossible(PageAction.SEND_TO_PHONE, card(liveRecord(Optional.of("javascript:alert(1)"))), false))
                .isFalse();
    }

    @Test
    void sendToPhoneIsNotPossibleWithAContinueRequest() {
        assertThat(isPossible(PageAction.SEND_TO_PHONE, card(liveRecord(Optional.of(LINK))), true)).isFalse();
    }

    @Test
    void onlyTheEnterActionsHaveAPlace() {
        assertThat(PageAction.ENTER_TERMINAL.enterPlace()).contains(LivePlace.TERMINAL);
        assertThat(PageAction.ENTER_BROWSER.enterPlace()).contains(LivePlace.BROWSER);
        assertThat(PageAction.ENTER_PHONE.enterPlace()).contains(LivePlace.PHONE);
        assertThat(PageAction.OPEN_TERMINAL.enterPlace()).isEmpty();
        assertThat(PageAction.SEND_TO_PHONE.enterPlace()).isEmpty();
        assertThat(PageAction.APPROVE.enterPlace()).isEmpty();
    }

    @Test
    void theOpenActionsHaveNoLabelAndNoRequestLine() {
        assertThat(PageAction.OPEN_TERMINAL.label()).isEmpty();
        assertThat(PageAction.SEND_TO_PHONE.label()).isEmpty();
        assertThat(PageAction.OPEN_TERMINAL.requestSentence()).isEmpty();
        assertThat(PageAction.SEND_TO_PHONE.requestSentence()).isEmpty();
        assertThat(PageAction.ENTER_PHONE.requestSentence())
                .contains("Enter sent. The worker stops the current step, and the link goes to your phone in a few seconds.");
    }

    @Test
    void theEnterActionsHaveTheirTooltips() {
        assertThat(PageAction.ENTER_TERMINAL.tooltip()).isEqualTo(
                "Opens the conversation in a terminal window on this machine. Close the window with Ctrl-b d.");
        assertThat(PageAction.ENTER_BROWSER.tooltip()).isEqualTo(
                "Opens the conversation in a new tab of this browser. The tab waits a few seconds for the link.");
        assertThat(PageAction.ENTER_PHONE.tooltip())
                .isEqualTo("Sends the link of the conversation to your phone, with a GitHub notification.");
        assertThat(PageAction.OPEN_TERMINAL.tooltip())
                .isEqualTo("Opens one more terminal window on this machine, attached to the same conversation.");
        assertThat(PageAction.SEND_TO_PHONE.tooltip())
                .isEqualTo("Sends the link to your phone again, with one more GitHub comment.");
    }
}
