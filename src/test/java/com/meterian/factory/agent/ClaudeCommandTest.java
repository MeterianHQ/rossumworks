package com.meterian.factory.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.meterian.factory.configuration.ClaudeSettings;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ClaudeCommandTest {

    private final ClaudeSettings settings = ClaudeSettings.defaults();
    private final UUID sessionId = UUID.randomUUID();

    @Test
    void startsANewSessionWithSessionId() {
        List<String> arguments = ClaudeCommand.arguments(settings, requestOf(true));

        assertThat(arguments).containsSequence("--session-id", sessionId.toString());
        assertThat(arguments).doesNotContain("--resume");
    }

    @Test
    void resumesAnExistingSession() {
        List<String> arguments = ClaudeCommand.arguments(settings, requestOf(false));

        assertThat(arguments).containsSequence("--resume", sessionId.toString());
        assertThat(arguments).doesNotContain("--session-id");
    }

    @Test
    void neverSkipsThePermissions() {
        List<String> arguments = ClaudeCommand.arguments(settings, requestOf(true));

        assertThat(arguments).doesNotContain("--dangerously-skip-permissions");
        assertThat(arguments).containsSequence("--permission-mode", "acceptEdits");
        assertThat(arguments.get(arguments.size() - 1)).isEqualTo("Fix it");
    }

    @Test
    void removesTheCredentialVariables() {
        Map<String, String> parent = Map.of(
                "ANTHROPIC_API_KEY", "a", "ANTHROPIC_AUTH_TOKEN", "b", "GH_TOKEN", "c",
                "GITHUB_TOKEN", "d", "FACTORY_GITHUB_TOKEN", "e", "PATH", "/bin",
                "ANTHROPIC_BASE_URL", "f", "CLAUDE_CODE_USE_BEDROCK", "g");

        assertThat(ClaudeCommand.environment(parent)).containsOnlyKeys("PATH");
    }

    private PhaseRequest requestOf(boolean newSession) {
        return new PhaseRequest(7, sessionId, newSession, Path.of("."), "Fix it");
    }
}
