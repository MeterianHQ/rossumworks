package com.meterian.factory.prompt;

import com.meterian.factory.definition.FactoryDefinition;
import com.meterian.factory.definition.StepDefinition;
import com.meterian.factory.state.ReviewKind;
import com.meterian.factory.state.StepType;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;

/** The prompt texts of a definition, for the steps agent, reviews, and risk. */
public record PromptTexts(String implement, String fix, String continueAfterConversation,
        Map<ReviewKind, String> reviews, String risk) {

    /** Reads the texts. It throws IllegalArgumentException if the workflow lacks one of the three steps. */
    public static PromptTexts from(FactoryDefinition definition) {
        StepDefinition agent = definition.workflow().stateWithStep(StepType.AGENT).step();
        StepDefinition reviews = definition.workflow().stateWithStep(StepType.REVIEWS).step();
        StepDefinition risk = definition.workflow().stateWithStep(StepType.RISK).step();
        Map<ReviewKind, String> reviewTexts = new EnumMap<>(ReviewKind.class);
        for (ReviewKind kind : reviews.reviewKinds()) {
            reviewTexts.put(kind, textOf(definition, reviews, kind.name().toLowerCase(Locale.ROOT)));
        }
        return new PromptTexts(
                textOf(definition, agent, "implement"),
                textOf(definition, agent, "fix"),
                textOf(definition, agent, "continue"),
                Map.copyOf(reviewTexts),
                textOf(definition, risk, "risk"));
    }

    private static String textOf(FactoryDefinition definition, StepDefinition step, String promptName) {
        return definition.promptText(step.promptPaths().get(promptName));
    }
}
