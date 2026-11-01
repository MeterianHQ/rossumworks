package com.meterian.factory.agent;

import com.meterian.factory.configuration.ClaudeSettings;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class ClaudeCommand {

    private static final Set<String> CREDENTIAL_VARIABLES = Set.of(
            "ANTHROPIC_API_KEY", "ANTHROPIC_AUTH_TOKEN", "GH_TOKEN", "GITHUB_TOKEN", "FACTORY_GITHUB_TOKEN");

    private ClaudeCommand() {
    }

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
        arguments.add(request.prompt());
        return arguments;
    }

    public static Map<String, String> environment(Map<String, String> parent) {
        Map<String, String> environment = new LinkedHashMap<>(parent);
        environment.keySet().removeAll(CREDENTIAL_VARIABLES);
        return environment;
    }
}
