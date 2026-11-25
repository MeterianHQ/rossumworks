package com.meterian.factory.process;

import java.util.List;
import java.util.Map;

/**
 * Removes the Anthropic credentials from the environment of a child process, so that only claude gets the key,
 * and only through the provider.
 */
public final class ChildEnvironment {

    private static final List<String> CREDENTIAL_PREFIXES = List.of("ANTHROPIC_", "CLAUDE_CODE_USE_");

    private ChildEnvironment() {
    }

    /** Removes, in place, each variable that has a credential prefix. */
    public static void removeAnthropicCredentials(Map<String, String> environment) {
        environment.keySet().removeIf(ChildEnvironment::hasCredentialPrefix);
    }

    /** True when the name starts with a credential prefix. */
    public static boolean hasCredentialPrefix(String name) {
        return CREDENTIAL_PREFIXES.stream().anyMatch(name::startsWith);
    }
}
