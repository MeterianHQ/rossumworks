package com.meterian.factory.definition;

import com.meterian.factory.state.ActionType;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Checks rule 12 of the loader: a prompt uses only the placeholders that the factory fills for it. */
final class PromptPlaceholders {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{(\\w+)}}");
    private static final List<String> REVIEW_PLACEHOLDERS = List.of("issue", "tree", "base");
    private static final List<String> RISK_PLACEHOLDERS = List.of("issue", "base");

    private PromptPlaceholders() {
    }

    static void verify(WorkflowDefinition workflow, Map<String, String> promptTexts) throws DefinitionException {
        for (StateDefinition state : workflow.states().values()) {
            if (state.action().isEmpty()) {
                continue;
            }
            ActionDefinition action = state.action().get();
            for (Map.Entry<String, String> prompt : action.promptPaths().entrySet()) {
                verifyPrompt(state.name(), action.type(), prompt.getKey(), prompt.getValue(),
                        promptTexts.get(prompt.getValue()));
            }
        }
    }

    private static void verifyPrompt(String stateName, ActionType type, String promptName, String path, String text)
            throws DefinitionException {
        List<String> known = findKnownPlaceholders(type, promptName);
        Matcher matcher = PLACEHOLDER.matcher(text);
        while (matcher.find()) {
            if (!known.contains(matcher.group(1))) {
                throw DefinitionNode.createFailure("the prompt " + path + " of the state " + stateName
                        + " has the placeholder " + matcher.group() + "; the prompt " + promptName
                        + " knows " + describe(known));
            }
        }
    }

    private static List<String> findKnownPlaceholders(ActionType type, String promptName) {
        return switch (type) {
            case CODING -> promptName.equals("implement") ? List.of("issue")
                    : promptName.equals("continue") ? List.of() : List.of("findings");
            case REVIEWS -> REVIEW_PLACEHOLDERS;
            case RISK -> RISK_PLACEHOLDERS;
            default -> List.of();
        };
    }

    private static String describe(List<String> known) {
        if (known.isEmpty()) {
            return "no placeholder";
        }
        return "only " + String.join(", ", known.stream().map(name -> "{{" + name + "}}").toList());
    }
}
