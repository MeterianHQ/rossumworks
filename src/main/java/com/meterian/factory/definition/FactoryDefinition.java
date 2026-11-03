package com.meterian.factory.definition;

import java.util.List;
import java.util.Map;

public record FactoryDefinition(WorkflowDefinition workflow, Map<String, Integer> limits, BuildDefinition build,
        List<String> allowedTools, String mergeTargetBranch,
        List<String> repositories, Map<String, String> prompts) {

    /** The text of a prompt file, by its path relative to .factory, for example prompts/fix.md. */
    public String promptText(String relativePath) {
        String text = prompts.get(relativePath);
        if (text == null) {
            throw new IllegalArgumentException("The definition has no prompt file " + relativePath + ".");
        }
        return text;
    }
}
