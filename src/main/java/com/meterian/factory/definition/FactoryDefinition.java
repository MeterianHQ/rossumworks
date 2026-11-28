package com.meterian.factory.definition;

import java.util.List;
import java.util.Map;

/**
 * The definition folder .factory: the workflow, the build, the tools of claude, the merge, and the prompt texts.
 *
 * @param requiredChecks the names of the check runs that must pass before the merge. With no name, the merge gate
 *                       reads no check run.
 */
public record FactoryDefinition(WorkflowDefinition workflow, BuildDefinition build, List<String> allowedTools,
        String mergeTargetBranch, List<String> requiredChecks, List<String> repositories,
        Map<String, String> prompts) {

    public FactoryDefinition {
        requiredChecks = List.copyOf(requiredChecks);
    }

    /** The text of a prompt file, by its path relative to .factory, for example prompts/fix.md. */
    public String promptText(String relativePath) {
        String text = prompts.get(relativePath);
        if (text == null) {
            throw new IllegalArgumentException("The definition has no prompt file " + relativePath + ".");
        }
        return text;
    }
}
