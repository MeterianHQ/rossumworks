package com.meterian.factory.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class UnavailableLiveSessionsTest {

    private static final String MESSAGE =
            "The live mode is not available with the provider anthropic-api-key. Answer on GitHub.";

    private final UnavailableLiveSessions sessions = new UnavailableLiveSessions();

    @Test
    void refusesToOpenASession() {
        assertThatThrownBy(() -> sessions.open(7, UUID.randomUUID(), Path.of("/work"), List.of("Read")))
                .isInstanceOf(LiveSessionException.class)
                .hasMessage(MESSAGE);
    }

    @Test
    void refusesToOpenATerminal() {
        assertThatThrownBy(() -> sessions.openTerminal(7))
                .isInstanceOf(LiveSessionException.class)
                .hasMessage(MESSAGE);
    }

    @Test
    void hasNoOpenSessionAndNoContinueRequest() {
        sessions.requestContinue(7);

        assertThat(sessions.isOpen(7)).isFalse();
        assertThat(sessions.isContinueRequested(7)).isFalse();
    }
}
