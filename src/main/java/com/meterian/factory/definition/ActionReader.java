package com.meterian.factory.definition;

import com.meterian.factory.state.ActionType;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Reads the action of a state and its prompt files. It keeps the text of each prompt file once. */
final class ActionReader {

    static final String RESUME_PROMPT = "resume";

    private static final Set<String> ACTION_KEYS = Set.of("type", "prompts");
    private static final Set<String> MERGE_GATE_KEYS = Set.of("type", "prompts", "mode");
    private static final List<String> REQUIRED_CODING_PROMPTS = List.of("implement", "fix", "continue");
    private static final List<String> REQUIRED_RISK_PROMPTS = List.of("risk");
    private static final Pattern REVIEW_NAME = Pattern.compile("[a-z0-9]+(-[a-z0-9]+)*");

    private final DefinitionFiles files;
    private final Map<String, String> promptTexts;

    ActionReader(DefinitionFiles files, Map<String, String> promptTexts) {
        this.files = files;
        this.promptTexts = promptTexts;
    }

    ActionDefinition read(String stateName, DefinitionNode action) throws DefinitionException {
        ActionType type = readType(stateName, action);
        StateReader.refuseUnknownKey(stateName, action,
                type == ActionType.MERGE_GATE ? MERGE_GATE_KEYS : ACTION_KEYS, "action.");
        Map<String, String> promptPaths = action.findTextMap("prompts");
        verifyPromptNames(stateName, type, promptPaths.keySet());
        for (String path : promptPaths.values()) {
            readPromptFile(stateName, path);
        }
        return new ActionDefinition(type, promptPaths, readMergeMode(stateName, action));
    }

    private static ActionType readType(String stateName, DefinitionNode action) throws DefinitionException {
        String typeName = action.requireText("type");
        return ActionType.findByYamlName(typeName).orElseThrow(() -> DefinitionNode.createFailure(
                "the state " + stateName + " has the unknown action type " + typeName));
    }

    private static void verifyPromptNames(String stateName, ActionType type, Set<String> names)
            throws DefinitionException {
        switch (type) {
            case CODING -> verifyFixedPrompts(stateName, names, REQUIRED_CODING_PROMPTS, Set.of(RESUME_PROMPT));
            case RISK -> verifyFixedPrompts(stateName, names, REQUIRED_RISK_PROMPTS, Set.of());
            case REVIEWS -> verifyReviewNames(stateName, names);
            default -> verifyFixedPrompts(stateName, names, List.of(), Set.of());
        }
    }

    private static void verifyFixedPrompts(String stateName, Set<String> names, List<String> requiredNames,
            Set<String> optionalNames) throws DefinitionException {
        for (String name : names) {
            if (!requiredNames.contains(name) && !optionalNames.contains(name)) {
                throw DefinitionNode.createFailure("the state " + stateName + " has the unknown prompt " + name);
            }
        }
        for (String name : requiredNames) {
            if (!names.contains(name)) {
                throw DefinitionNode.createFailure("the state " + stateName + " needs the prompt " + name);
            }
        }
    }

    private static void verifyReviewNames(String stateName, Set<String> names) throws DefinitionException {
        if (names.isEmpty()) {
            throw DefinitionNode.createFailure("the state " + stateName + " needs one review prompt or more");
        }
        for (String name : names) {
            if (!REVIEW_NAME.matcher(name).matches()) {
                throw DefinitionNode.createFailure("the state " + stateName + " has the review " + name
                        + "; a review name has only small letters, digits, and hyphens");
            }
        }
    }

    private void readPromptFile(String stateName, String path) throws DefinitionException {
        if (promptTexts.containsKey(path)) {
            return;
        }
        String text = files.read(path).orElseThrow(() -> DefinitionNode.createFailure(
                "the state " + stateName + ": prompt file " + path + " does not exist"));
        promptTexts.put(path, text);
    }

    private static MergeMode readMergeMode(String stateName, DefinitionNode action) throws DefinitionException {
        if (!action.containsKey("mode")) {
            return MergeMode.RISK;
        }
        String modeName = action.requireText("mode");
        return MergeMode.findByYamlName(modeName).orElseThrow(() -> DefinitionNode.createFailure(
                "the state " + stateName + " has the unknown mode " + modeName));
    }
}
