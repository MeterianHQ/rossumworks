package com.meterian.factory.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.meterian.factory.configuration.ClaudeSettings;
import com.meterian.factory.testing.TestTools;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ClaudeCommandTest {

    private final ClaudeSettings settings = ClaudeSettings.createDefaults();
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
    }

    @Test
    void asksTheAgentToWriteToHumansInSimplifiedTechnicalEnglish() {
        List<String> arguments = ClaudeCommand.arguments(settings, requestOf(true));

        assertThat(arguments).containsSequence("--append-system-prompt", ClaudeCommand.HUMAN_LANGUAGE_RULE);
        assertThat(ClaudeCommand.HUMAN_LANGUAGE_RULE).contains("Simplified Technical English (ASD-STE100)");
        assertThat(arguments.indexOf("--append-system-prompt")).isLessThan(arguments.indexOf("--allowedTools"));
    }

    @Test
    void neverPutsThePromptInTheArguments() {
        List<String> arguments = ClaudeCommand.arguments(settings, requestOf(true));

        assertThat(arguments).doesNotContain("Fix it");
    }

    @Test
    void removesTheCredentialVariables() {
        Map<String, String> parent = Map.of(
                "ANTHROPIC_API_KEY", "a", "ANTHROPIC_AUTH_TOKEN", "b", "GH_TOKEN", "c",
                "GITHUB_TOKEN", "d", "FACTORY_GITHUB_TOKEN", "e", "PATH", "/bin",
                "ANTHROPIC_BASE_URL", "f", "CLAUDE_CODE_USE_BEDROCK", "g");

        assertThat(ClaudeCommand.environment(parent)).containsOnlyKeys("PATH");
    }

    @Test
    void usesTheAllowedToolsOfTheRequest() {
        PhaseRequest request = new PhaseRequest(7, sessionId, true, Path.of("."), "Fix it",
                List.of("Read", "Bash(npm test)"));

        List<String> arguments = ClaudeCommand.arguments(settings, request);

        int toolsStart = arguments.indexOf("--allowedTools") + 1;
        int toolsEnd = arguments.indexOf("--disallowedTools");
        assertThat(arguments.subList(toolsStart, toolsEnd)).containsExactly("Read", "Bash(npm test)");
    }

    private PhaseRequest requestOf(boolean newSession) {
        return new PhaseRequest(7, sessionId, newSession, Path.of("."), "Fix it", TestTools.ALLOWED_TOOLS);
    }
}
