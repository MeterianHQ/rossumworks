package com.meterian.factory.state;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

class ActionTypeTest {

    @Test
    void readsTheNewActionTypes() {
        assertThat(ActionType.findByYamlName("approval")).contains(ActionType.APPROVAL);
        assertThat(ActionType.findByYamlName("conversation")).contains(ActionType.CONVERSATION);
        assertThat(ActionType.APPROVAL.events()).containsExactly("approved");
        assertThat(ActionType.CONVERSATION.events()).containsExactlyInAnyOrder("continue", "changed");
        assertThat(ActionType.EVENTS_WITHOUT_ACTION).containsExactly("continue");
    }

    @Test
    void givesTheTypesThatWaitForYou() {
        assertThat(selectTypes(ActionType::waitsForYou)).containsExactly(ActionType.APPROVAL, ActionType.CONVERSATION);
    }

    @Test
    void givesTheTypesThatUseTheIssueText() {
        assertThat(selectTypes(ActionType::usesIssueText))
                .containsExactly(ActionType.CODING, ActionType.REVIEWS, ActionType.RISK);
    }

    @Test
    void allowsSeveralStatesOnlyForApproval() {
        assertThat(selectTypes(ActionType::allowsSeveralStates)).containsExactly(ActionType.APPROVAL);
    }

    @Test
    void givesTheTypesThatNeedAWorktree() {
        assertThat(selectTypes(ActionType::needsWorktree)).containsExactly(ActionType.CODING, ActionType.BUILD,
                ActionType.REVIEWS, ActionType.RISK, ActionType.MERGE_GATE, ActionType.CONVERSATION);
    }

    private static ActionType[] selectTypes(Predicate<ActionType> filter) {
        return Arrays.stream(ActionType.values()).filter(filter).toArray(ActionType[]::new);
    }
}
