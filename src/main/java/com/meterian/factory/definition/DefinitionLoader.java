package com.meterian.factory.definition;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import java.io.IOException;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Reads the definition folder .factory: the file factory.yaml with its state machine, and the prompt files. */
public final class DefinitionLoader {

    private static final int SUPPORTED_VERSION = 1;
    private static final Set<String> ROOT_KEYS = Set.of(
            "version", "workflow", "build", "claude", "merge", "repositories");

    private DefinitionLoader() {
    }

    public static FactoryDefinition load(DefinitionFiles files) throws DefinitionException {
        DefinitionNode root = new DefinitionNode(parseMainFile(files), "");
        root.requireOnlyKeys(ROOT_KEYS);
        verifyVersion(root);
        Map<String, String> promptTexts = new LinkedHashMap<>();
        WorkflowDefinition workflow = new WorkflowReader(new ActionReader(files, promptTexts))
                .read(root.requireObject("workflow"));
        DefinitionRules.verify(workflow);
        WorkflowGraph.verify(workflow);
        PromptPlaceholders.verify(workflow, promptTexts);
        DefinitionNode merge = root.requireObject("merge");
        merge.requireOnlyKeys(Set.of("target-branch", "required-checks"));
        return new FactoryDefinition(
                workflow,
                readBuild(root.requireObject("build")),
                readAllowedTools(root),
                merge.requireText("target-branch"),
                readRequiredChecks(merge),
                root.findTextList("repositories"),
                promptTexts);
    }

    private static JsonNode parseMainFile(DefinitionFiles files) throws DefinitionException {
        String text = files.read(DefinitionNode.MAIN_FILE).orElseThrow(MissingMainFileException::new);
        try {
            JsonNode tree = new YAMLMapper().readTree(text);
            if (tree == null || !tree.isObject()) {
                throw DefinitionNode.createFailure("the file is empty or is not a section");
            }
            return tree;
        } catch (IOException exception) {
            throw new DefinitionException(
                    DefinitionNode.MAIN_FILE + ": cannot read the file: " + exception.getMessage(), exception);
        }
    }

    private static void verifyVersion(DefinitionNode root) throws DefinitionException {
        int version = root.findInteger("version")
                .orElseThrow(() -> DefinitionNode.createFailure("key version is missing"));
        if (version != SUPPORTED_VERSION) {
            throw DefinitionNode.createFailure("version " + version + " is not supported");
        }
    }

    private static BuildDefinition readBuild(DefinitionNode section) throws DefinitionException {
        section.requireOnlyKeys(Set.of("command", "environment"));
        return new BuildDefinition(section.requireText("command"), section.findTextMap("environment"));
    }

    private static List<String> readAllowedTools(DefinitionNode root) throws DefinitionException {
        DefinitionNode section = root.findObject("claude")
                .orElseThrow(() -> DefinitionNode.createFailure("key claude.allowed-tools is missing"));
        section.requireOnlyKeys(Set.of("allowed-tools"));
        List<String> allowedTools = section.findTextList("allowed-tools");
        if (allowedTools.isEmpty()) {
            throw DefinitionNode.createFailure("key claude.allowed-tools is missing or has no tool");
        }
        return allowedTools;
    }

    /** Each name is the name of a check run: it is not empty, and it is in the list one time only. */
    private static List<String> readRequiredChecks(DefinitionNode section) throws DefinitionException {
        List<String> requiredChecks = section.findTextList("required-checks");
        Set<String> names = new HashSet<>();
        for (String name : requiredChecks) {
            if (name.isBlank()) {
                throw section.createInvalidKeyFailure("required-checks", "has an empty name");
            }
            if (!names.add(name)) {
                throw section.createInvalidKeyFailure("required-checks", "has the name " + name + " two times");
            }
        }
        return requiredChecks;
    }
}
