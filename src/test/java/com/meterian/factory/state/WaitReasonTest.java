package com.meterian.factory.state;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class WaitReasonTest {

    @Test
    void theLiveConversationReasonTellsTheOwnerHowToLeave() {
        assertThat(WaitReason.LIVE_CONVERSATION.sentence()).isEqualTo(
                "You are in a live conversation with the agent. Type /factory:continue when you are done.");
    }
}
