package com.meterian.factory.configuration;

import java.util.List;

public record ClaudeSettings(String command, List<String> disallowedTools) {

    public static final String DEFAULT_COMMAND = "claude";

    public static final List<String> DEFAULT_DISALLOWED_TOOLS = List.of(
            "Bash(git push*)", "Bash(git remote*)", "Bash(git config*)", "Bash(gh *)",
            "Bash(ssh *)", "Bash(scp *)", "Bash(curl *)", "Bash(wget *)",
            "WebFetch", "WebSearch");

    public static ClaudeSettings createDefaults() {
        return new ClaudeSettings(DEFAULT_COMMAND, DEFAULT_DISALLOWED_TOOLS);
    }
}
