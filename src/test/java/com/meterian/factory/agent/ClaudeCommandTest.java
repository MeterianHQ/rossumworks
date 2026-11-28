package com.meterian.factory.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

import com.meterian.factory.configuration.ClaudeSettings;
import com.meterian.factory.testing.TestTools;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ClaudeCommandTest {

    static final List<String> PARENT_SESSION_VARIABLES = List.of(
            "CLAUDECODE", "CLAUDE_CODE_CHILD_NOTES_DIR", "CLAUDE_CODE_CHILD_SESSION", "CLAUDE_CODE_ENTRYPOINT",
            "CLAUDE_CODE_EXECPATH", "CLAUDE_CODE_MESSAGING_SOCKET", "CLAUDE_CODE_MESSAGING_TOKEN",
            "CLAUDE_CODE_SESSION_ATTENDED", "CLAUDE_CODE_SESSION_ID", "CLAUDE_EFFORT", "CLAUDE_PID");

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
    void removesTheVariablesOfAParentClaudeCodeSession() {
        Map<String, String> parent = new HashMap<>();
        PARENT_SESSION_VARIABLES.forEach(name -> parent.put(name, "parent"));
        parent.put("PATH", "/bin");

        assertThat(ClaudeCommand.environment(parent)).containsOnlyKeys("PATH");
    }

    @Test
    void keepsTheOtherClaudeCodeVariables() {
        Map<String, String> parent = Map.of("CLAUDE_CODE_MAX_OUTPUT_TOKENS", "64000", "CLAUDECODE", "1");

        assertThat(ClaudeCommand.environment(parent)).containsExactly(entry("CLAUDE_CODE_MAX_OUTPUT_TOKENS", "64000"));
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

    @Test
    void theProviderArgumentsComeBeforeTheToolLists() {
        List<String> arguments = ClaudeCommand.arguments(settings, requestOf(true),
                List.of("--settings", "/work/credentials/api-key-settings.json"));

        assertThat(arguments).containsSubsequence("--settings", "/work/credentials/api-key-settings.json",
                "--allowedTools");
    }

    private PhaseRequest requestOf(boolean newSession) {
        return new PhaseRequest(7, sessionId, newSession, Path.of("."), "Fix it", TestTools.ALLOWED_TOOLS);
    }
}
