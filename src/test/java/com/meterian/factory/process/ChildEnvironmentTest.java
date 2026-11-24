package com.meterian.factory.process;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ChildEnvironmentTest {

    @Test
    void removesTheAnthropicCredentials() {
        Map<String, String> environment = new HashMap<>(Map.of(
                "ANTHROPIC_API_KEY", "secret",
                "ANTHROPIC_BASE_URL", "https://example.com",
                "CLAUDE_CODE_USE_BEDROCK", "1"));

        ChildEnvironment.removeAnthropicCredentials(environment);

        assertThat(environment).isEmpty();
    }

    @Test
    void keepsTheOtherVariables() {
        Map<String, String> environment = new HashMap<>(Map.of(
                "PATH", "/usr/bin",
                "GH_TOKEN", "token",
                "HOME", "/home/owner",
                "ANTHROPIC_API_KEY", "secret"));

        ChildEnvironment.removeAnthropicCredentials(environment);

        assertThat(environment).containsOnlyKeys("PATH", "GH_TOKEN", "HOME");
    }
}
