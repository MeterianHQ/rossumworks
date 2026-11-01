package com.meterian.factory.configuration;

import java.util.List;

public record ClaudeSettings(String command, List<String> allowedTools, List<String> disallowedTools) {

    public static final String DEFAULT_COMMAND = "claude";

    public static final List<String> DEFAULT_ALLOWED_TOOLS = List.of(
            "Read", "Edit", "Write", "Glob", "Grep",
            "Bash(git status*)", "Bash(git diff*)", "Bash(git log*)", "Bash(git show*)",
            "Bash(./mvnw *)", "Bash(mvn *)");

    public static final List<String> DEFAULT_DISALLOWED_TOOLS = List.of(
            "Bash(git push*)", "Bash(git remote*)", "Bash(git config*)", "Bash(gh *)",
            "Bash(ssh *)", "Bash(scp *)", "Bash(curl *)", "Bash(wget *)",
            "WebFetch", "WebSearch");

    public static ClaudeSettings defaults() {
        return new ClaudeSettings(DEFAULT_COMMAND, DEFAULT_ALLOWED_TOOLS, DEFAULT_DISALLOWED_TOOLS);
    }
}
