package com.meterian.factory.definition;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** The default definition of the jar as text, and a definition folder in memory. */
final class TestDefinitions {

    static final String DEFAULT_YAML = readDefaultFile("factory.yaml");

    private static final List<String> PROMPT_FILES = List.of("implement", "fix", "continue", "resume",
            "review-correctness", "review-security", "review-architecture", "review-quality", "risk");

    private TestDefinitions() {
    }

    static DefinitionFiles filesWith(String mainYaml) {
        return filesWith(mainYaml, Map.of());
    }

    /** The prompt files hold the text "prompt NAME", except the files that the overrides replace. */
    static DefinitionFiles filesWith(String mainYaml, Map<String, String> overrides) {
        Map<String, String> files = new HashMap<>();
        files.put("factory.yaml", mainYaml);
        for (String name : PROMPT_FILES) {
            files.put("prompts/" + name + ".md", "prompt " + name);
        }
        files.putAll(overrides);
        return path -> Optional.ofNullable(files.get(path));
    }

    /** A file of the default definition of the jar. */
    static String readDefaultFile(String relativePath) {
        try {
            return new ClasspathDefinitionFiles().read(relativePath).orElseThrow();
        } catch (DefinitionException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
