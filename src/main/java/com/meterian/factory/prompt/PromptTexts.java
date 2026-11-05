package com.meterian.factory.prompt;

import com.meterian.factory.definition.FactoryDefinition;
import com.meterian.factory.definition.StateDefinition;
import com.meterian.factory.definition.WorkflowDefinition;
import com.meterian.factory.state.ActionType;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The prompt texts of a definition, for the actions coding, reviews, and risk. A text of an action that the
 * workflow does not have is empty: no action asks for it.
 *
 * @param resume  the prompt resume; only a workflow with an entry into the coding state without findings has it
 * @param reviews the text of each review, by review name, in the order of the definition
 */
public record PromptTexts(String implement, String fix, String continueAfterConversation, Optional<String> resume,
        Map<String, String> reviews, String risk) {

    private static final String NO_TEXT = "";

    public static PromptTexts from(FactoryDefinition definition) {
        WorkflowDefinition workflow = definition.workflow();
        Map<String, String> coding = promptsOf(definition, workflow, ActionType.CODING);
        Map<String, String> reviews = promptsOf(definition, workflow, ActionType.REVIEWS);
        Map<String, String> risk = promptsOf(definition, workflow, ActionType.RISK);
        return new PromptTexts(
                coding.getOrDefault("implement", NO_TEXT),
                coding.getOrDefault("fix", NO_TEXT),
                coding.getOrDefault("continue", NO_TEXT),
                Optional.ofNullable(coding.get("resume")),
                Collections.unmodifiableMap(reviews),
                risk.getOrDefault("risk", NO_TEXT));
    }

    /** The text of each prompt of the state with the action, by the prompt name. */
    private static Map<String, String> promptsOf(FactoryDefinition definition, WorkflowDefinition workflow,
            ActionType type) {
        Optional<StateDefinition> state = workflow.findStateWithAction(type);
        Map<String, String> texts = new LinkedHashMap<>();
        state.flatMap(StateDefinition::action).ifPresent(action -> action.promptPaths()
                .forEach((name, path) -> texts.put(name, definition.promptText(path))));
        return texts;
    }
}
