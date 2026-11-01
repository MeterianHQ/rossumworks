package com.meterian.factory.agent;

import com.meterian.factory.configuration.ClaudeSettings;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class ClaudeCommand {

    private static final List<String> REMOVED_PREFIXES = List.of("ANTHROPIC_", "CLAUDE_CODE_USE_");
    private static final Set<String> REMOVED_NAMES = Set.of("GH_TOKEN", "GITHUB_TOKEN", "FACTORY_GITHUB_TOKEN");

    private ClaudeCommand() {
    }

    /** The prompt is not an argument: the runner writes it to the standard input, after the tool lists. */
    public static List<String> arguments(ClaudeSettings settings, PhaseRequest request) {
        List<String> arguments = new ArrayList<>();
        arguments.add(settings.command());
        arguments.add("-p");
        arguments.add(request.newSession() ? "--session-id" : "--resume");
        arguments.add(request.sessionId().toString());
        arguments.addAll(List.of("--output-format", "stream-json", "--verbose"));
        arguments.addAll(List.of("--permission-mode", "acceptEdits"));
        arguments.add("--allowedTools");
        arguments.addAll(settings.allowedTools());
        arguments.add("--disallowedTools");
        arguments.addAll(settings.disallowedTools());
        return arguments;
    }

    public static Map<String, String> environment(Map<String, String> parent) {
        Map<String, String> environment = new LinkedHashMap<>(parent);
        environment.keySet().removeIf(ClaudeCommand::isRemoved);
        return environment;
    }

    private static boolean isRemoved(String name) {
        return REMOVED_NAMES.contains(name) || REMOVED_PREFIXES.stream().anyMatch(name::startsWith);
    }
}
