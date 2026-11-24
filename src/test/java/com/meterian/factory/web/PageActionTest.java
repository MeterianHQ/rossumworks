package com.meterian.factory.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.meterian.factory.board.IssueCard;
import com.meterian.factory.state.IssueRecord;
import com.meterian.factory.state.LivePlace;
import com.meterian.factory.state.LiveSession;
import com.meterian.factory.testing.RecordStates;
import java.util.Arrays;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PageActionTest {

    private static final String LINK = "https://claude.ai/code/session_abc123";

    private static IssueRecord startedRecord(String stateName) {
        return RecordStates.move(RecordStates.move(RecordStates.queued(12).withAgentSessionStarted(), "implement"),
                stateName);
    }

    private static IssueRecord liveRecord(Optional<String> link) {
        return startedRecord("live")
                .withLive(new LiveSession("abc", link, LivePlace.BROWSER));
    }

    private static IssueCard card(IssueRecord record) {
        return card(record, Set.of("factory"));
    }

    private static IssueCard card(IssueRecord record, Set<String> labels) {
        return new IssueCard(12, "Title", labels, record, Optional.empty());
    }

    /** The state blocked is the block target of the built-in workflow. */
    private static boolean isInBlockTarget(IssueCard card) {
        return card.record().state().equals("blocked");
    }

    /** The rule of the action when the page remembers no request. */
    private static boolean isPossible(PageAction action, IssueCard card, boolean continueRequested) {
        return action.isPossible(card, continueRequested, Set.of(), true, true, isInBlockTarget(card));
    }

    /** The rule of the action for a viewer on this machine, without a continue request. */
    private static boolean isPossibleOnThisMachine(PageAction action, IssueCard card, Set<String> rememberedLabels) {
        return action.isPossible(card, false, rememberedLabels, true, true, isInBlockTarget(card));
    }

    /** The rule of the action for a viewer on another device. */
    private static boolean isPossibleFromAnotherDevice(PageAction action, IssueCard card) {
        return action.isPossible(card, false, Set.of(), false, true, isInBlockTarget(card));
    }

    /** The rule of the action in API key mode, for a viewer on this machine. */
    private static boolean isPossibleWithoutTheLiveMode(PageAction action, IssueCard card) {
        return action.isPossible(card, false, Set.of(), true, false, isInBlockTarget(card));
    }

    @Test
    void enterNeedsAStartedSession() {
        IssueRecord notStarted = RecordStates.move(RecordStates.queued(12), "implement");
        IssueRecord started = startedRecord("implement");

        assertThat(isPossible(PageAction.ENTER_BROWSER, card(notStarted), false)).isFalse();
        assertThat(isPossible(PageAction.ENTER_BROWSER, card(started), false)).isTrue();
    }

    @Test
    void enterIsNotPossibleInTheFinishState() {
        IssueRecord done = startedRecord("done");

        assertThat(isPossible(PageAction.ENTER_BROWSER, card(done), false)).isFalse();
    }

    @Test
    void enterIsNotPossibleWhenTheLiveStateHasALink() {
        assertThat(isPossible(PageAction.ENTER_BROWSER, card(liveRecord(Optional.of(LINK))), false)).isFalse();
    }

    @Test
    void enterIsPossibleAgainWhenTheLiveStateHasNoLink() {
        assertThat(isPossible(PageAction.ENTER_BROWSER, card(liveRecord(Optional.empty())), false)).isTrue();
    }

    @Test
    void approveNeedsAnApprovalState() {
        IssueRecord merge = startedRecord("waits-for-approval");
        IssueRecord textChanged = startedRecord("text-changed");
        IssueRecord question = liveRecord(Optional.of(LINK));

        assertThat(isPossible(PageAction.APPROVE, card(merge), false)).isTrue();
        assertThat(isPossible(PageAction.APPROVE, card(textChanged), false)).isTrue();
        assertThat(isPossible(PageAction.APPROVE, card(question), false)).isFalse();
    }

    @Test
    void blockIsNotPossibleWithoutAnActionOrInTheFinishState() {
        assertThat(isPossible(PageAction.BLOCK, card(startedRecord("implement")), false)).isTrue();
        assertThat(isPossible(PageAction.BLOCK, card(startedRecord("waits-for-approval")), false)).isTrue();
        assertThat(isPossible(PageAction.BLOCK, card(startedRecord("blocked")), false)).isFalse();
        assertThat(isPossible(PageAction.BLOCK, card(startedRecord("done")), false)).isFalse();
    }

    @Test
    void continueNeedsALiveStateOrAStateWithoutAnAction() {
        assertThat(isPossible(PageAction.CONTINUE, card(liveRecord(Optional.of(LINK))), false)).isTrue();
        assertThat(isPossible(PageAction.CONTINUE, card(startedRecord("blocked")), false)).isTrue();
        assertThat(isPossible(PageAction.CONTINUE, card(startedRecord("implement")), false)).isFalse();
        assertThat(isPossible(PageAction.CONTINUE, card(startedRecord("waits-for-approval")), false)).isFalse();
    }

    @Test
    void continueHidesWhenTheMarkerExists() {
        assertThat(isPossible(PageAction.CONTINUE, card(liveRecord(Optional.of(LINK))), true)).isFalse();
        assertThat(isPossible(PageAction.CONTINUE, card(startedRecord("blocked")), true)).isFalse();
    }

    @Test
    void continueIsPendingOnABlockedIssueUntilTheIssueMoves() {
        IssueCard blocked = card(startedRecord("blocked"));

        assertThat(PageAction.CONTINUE.isRequestPending(blocked, true, Set.of(), true)).isTrue();
        assertThat(PageAction.CONTINUE.isRequestPending(card(startedRecord("implement")), true, Set.of(), false))
                .isFalse();
    }

    @Test
    void aRememberedLabelOnTheCardHidesItsButton() {
        IssueRecord merge = startedRecord("waits-for-approval");
        Set<String> remembered = Set.of("factory:approve", "factory:block", "factory:enter");

        assertThat(isPossibleOnThisMachine(PageAction.APPROVE, card(merge, Set.of("factory", "factory:approve")),
                remembered)).isFalse();
        assertThat(isPossibleOnThisMachine(PageAction.BLOCK, card(merge, Set.of("factory", "factory:block")),
                remembered)).isFalse();
        assertThat(isPossibleOnThisMachine(PageAction.ENTER_BROWSER, card(merge, Set.of("factory", "factory:enter")),
                remembered)).isFalse();
        assertThat(isPossibleOnThisMachine(PageAction.BLOCK, card(merge, Set.of("factory", "factory:approve")),
                remembered)).isTrue();
    }

    @Test
    void aLabelThatThePageDidNotRequestKeepsItsButton() {
        IssueRecord merge = startedRecord("waits-for-approval");

        assertThat(isPossible(PageAction.APPROVE, card(merge, Set.of("factory", "factory:approve")), false)).isTrue();
        assertThat(isPossible(PageAction.BLOCK, card(merge, Set.of("factory", "factory:block")), false)).isTrue();
        assertThat(isPossible(PageAction.ENTER_BROWSER, card(merge, Set.of("factory", "factory:enter")), false)).isTrue();
    }

    @Test
    void aRememberedRequestWithoutTheLabelKeepsTheButton() {
        IssueRecord merge = startedRecord("waits-for-approval");

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
    void startAgainIsPossibleOnlyInTheBlockTargetWithoutALiveConversation() {
        PageAction startAgain = PageAction.findByPathName("start-again").orElseThrow();

        assertThat(startAgain.label()).contains("factory:start-again");
        assertThat(isPossible(startAgain, card(startedRecord("blocked")), false)).isTrue();
        assertThat(isPossible(startAgain, card(startedRecord("build")), false)).isFalse();
        assertThat(isPossible(startAgain, card(startedRecord("done")), false)).isFalse();
        assertThat(isPossible(startAgain, card(RecordStates.move(liveRecord(Optional.empty()), "blocked")), false))
                .isFalse();
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
        IssueCard started = card(startedRecord("implement"));
        IssueCard live = card(liveRecord(Optional.of(LINK)));

        assertThat(isPossibleFromAnotherDevice(PageAction.ENTER_TERMINAL, started)).isFalse();
        assertThat(isPossibleFromAnotherDevice(PageAction.OPEN_TERMINAL, live)).isFalse();
        assertThat(isPossibleFromAnotherDevice(PageAction.ENTER_BROWSER, started)).isTrue();
    }

    @Test
    void theTwoEnterActionsHaveTheSameRule() {
        IssueCard started = card(startedRecord("implement"));
        IssueCard live = card(liveRecord(Optional.of(LINK)));

        assertThat(isPossible(PageAction.ENTER_TERMINAL, started, false)).isTrue();
        assertThat(isPossible(PageAction.ENTER_BROWSER, started, false)).isTrue();
        assertThat(isPossible(PageAction.ENTER_TERMINAL, live, false)).isFalse();
        assertThat(isPossible(PageAction.ENTER_BROWSER, live, false)).isFalse();
    }

    @Test
    void onlyTheLiveActionsNeedTheLiveMode() {
        assertThat(Arrays.stream(PageAction.values()).filter(PageAction::needsLiveMode))
                .containsExactly(PageAction.ENTER_TERMINAL, PageAction.ENTER_BROWSER, PageAction.OPEN_TERMINAL);
    }

    @Test
    void aLiveActionIsNotPossibleWithoutTheLiveMode() {
        IssueCard started = card(startedRecord("implement"));
        IssueCard live = card(liveRecord(Optional.of(LINK)));

        assertThat(isPossibleWithoutTheLiveMode(PageAction.ENTER_TERMINAL, started)).isFalse();
        assertThat(isPossibleWithoutTheLiveMode(PageAction.ENTER_BROWSER, started)).isFalse();
        assertThat(isPossibleWithoutTheLiveMode(PageAction.OPEN_TERMINAL, live)).isFalse();
    }

    @Test
    void theOtherActionsDoNotNeedTheLiveMode() {
        IssueCard started = card(startedRecord("implement"));
        IssueCard merge = card(startedRecord("waits-for-approval"));
        IssueCard blocked = card(startedRecord("blocked"));

        assertThat(isPossibleWithoutTheLiveMode(PageAction.APPROVE, merge)).isTrue();
        assertThat(isPossibleWithoutTheLiveMode(PageAction.BLOCK, started)).isTrue();
        assertThat(isPossibleWithoutTheLiveMode(PageAction.START_AGAIN, blocked)).isTrue();
        assertThat(isPossibleWithoutTheLiveMode(PageAction.CONTINUE, blocked)).isTrue();
    }

    @Test
    void openTerminalNeedsALiveLink() {
        assertThat(isPossible(PageAction.OPEN_TERMINAL, card(liveRecord(Optional.of(LINK))), false)).isTrue();
        assertThat(isPossible(PageAction.OPEN_TERMINAL, card(liveRecord(Optional.empty())), false)).isFalse();
        assertThat(isPossible(PageAction.OPEN_TERMINAL, card(startedRecord("implement")),
                false)).isFalse();
    }

    @Test
    void openTerminalIsNotPossibleWithAContinueRequest() {
        assertThat(isPossible(PageAction.OPEN_TERMINAL, card(liveRecord(Optional.of(LINK))), true)).isFalse();
    }

    @Test
    void onlyTheEnterActionsHaveAPlace() {
        assertThat(PageAction.ENTER_TERMINAL.enterPlace()).contains(LivePlace.TERMINAL);
        assertThat(PageAction.ENTER_BROWSER.enterPlace()).contains(LivePlace.BROWSER);
        assertThat(PageAction.OPEN_TERMINAL.enterPlace()).isEmpty();
        assertThat(PageAction.APPROVE.enterPlace()).isEmpty();
    }

    @Test
    void theOpenActionsHaveNoLabelAndNoRequestLine() {
        assertThat(PageAction.OPEN_TERMINAL.label()).isEmpty();
        assertThat(PageAction.OPEN_TERMINAL.requestSentence()).isEmpty();
        assertThat(PageAction.ENTER_BROWSER.requestSentence()).contains(
                "Enter sent. The worker stops the current action, and the new tab opens the conversation in a few seconds.");
    }

    @Test
    void theEnterActionsHaveTheirTooltips() {
        assertThat(PageAction.ENTER_TERMINAL.tooltip()).isEqualTo(
                "Opens the conversation in a terminal window on this machine. Close the window with Ctrl-b d.");
        assertThat(PageAction.ENTER_BROWSER.tooltip()).isEqualTo(
                "Opens the conversation in a new tab of this browser. The tab waits a few seconds for the link.");
        assertThat(PageAction.OPEN_TERMINAL.tooltip())
                .isEqualTo("Opens one more terminal window on this machine, attached to the same conversation.");
    }
}
