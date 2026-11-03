package com.meterian.factory.definition;

import com.meterian.factory.state.ReviewKind;
import com.meterian.factory.state.StepType;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Reads the step of a state and its prompt files. It keeps the text of each prompt file. */
final class StepReader {

    private static final Set<String> STEP_KEYS = Set.of("type", "prompts");
    private static final Map<StepType, List<String>> REQUIRED_PROMPTS = Map.of(
            StepType.AGENT, List.of("implement", "fix", "continue"),
            StepType.RISK, List.of("risk"));

    private final DefinitionFiles files;
    private final Map<String, String> promptTexts;

    StepReader(DefinitionFiles files, Map<String, String> promptTexts) {
        this.files = files;
        this.promptTexts = promptTexts;
    }

    StepDefinition read(DefinitionNode step, String stateName) throws DefinitionException {
        step.requireOnlyKeys(STEP_KEYS);
        String typeName = step.requireText("type");
        StepType type = StepType.findByYamlName(typeName).orElseThrow(
                () -> DefinitionNode.createFailure("state " + stateName + ": unknown step type " + typeName));
        Map<String, String> promptPaths = step.findTextMap("prompts");
        verifyPromptKeys(type, promptPaths.keySet(), stateName);
        for (String path : promptPaths.values()) {
            readPromptFile(path, stateName);
        }
        return new StepDefinition(type, promptPaths);
    }

    private static void verifyPromptKeys(StepType type, Set<String> keys, String stateName)
            throws DefinitionException {
        List<String> allowed = findAllowedPromptKeys(type);
        for (String key : keys) {
            if (!allowed.contains(key)) {
                throw DefinitionNode.createFailure("state " + stateName + " has the unknown prompt " + key);
            }
        }
        for (String key : allowed) {
            if (!keys.contains(key)) {
                throw DefinitionNode.createFailure("state " + stateName + " needs the prompt " + key);
            }
        }
    }

    private static List<String> findAllowedPromptKeys(StepType type) {
        if (type == StepType.REVIEWS) {
            return Arrays.stream(ReviewKind.values()).map(kind -> kind.name().toLowerCase(Locale.ROOT)).toList();
        }
        return REQUIRED_PROMPTS.getOrDefault(type, List.of());
    }

    private void readPromptFile(String path, String stateName) throws DefinitionException {
        if (promptTexts.containsKey(path)) {
            return;
        }
        String text = files.read(path).orElseThrow(() -> DefinitionNode.createFailure(
                "state " + stateName + ": prompt file " + path + " does not exist"));
        promptTexts.put(path, text);
    }
}
