package com.meterian.factory.definition;

import static com.meterian.factory.definition.TestDefinitions.DEFAULT_YAML;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;

class PromptPlaceholdersTest {

    @Test
    void acceptsThePlaceholdersOfTheDefault() throws Exception {
        FactoryDefinition definition = DefinitionLoader.load(
                TestDefinitions.filesWith(DEFAULT_YAML, defaultPrompts()));

        assertThat(definition.promptText("prompts/review-security.md")).contains("{{tree}}");
    }

    @Test
    void refusesAnUnknownPlaceholder() {
        assertRefused(Map.of("prompts/fix.md", "Fix this: {{isue}}"),
                "factory.yaml: the prompt prompts/fix.md of the state implement has the placeholder {{isue}};"
                        + " the prompt fix knows only {{findings}}");
    }

    @Test
    void refusesAPlaceholderOfAnotherPrompt() {
        assertRefused(Map.of("prompts/implement.md", "{{issue}} {{tree}}"),
                "factory.yaml: the prompt prompts/implement.md of the state implement has the placeholder {{tree}};"
                        + " the prompt implement knows only {{issue}}");
    }

    @Test
    void refusesAPlaceholderInAPromptWithoutPlaceholders() {
        assertRefused(Map.of("prompts/continue.md", "Go on with {{issue}}"),
                "factory.yaml: the prompt prompts/continue.md of the state implement has the placeholder {{issue}};"
                        + " the prompt continue knows no placeholder");
    }

    @Test
    void listsThePlaceholdersOfAReview() {
        assertRefused(Map.of("prompts/review-quality.md", "{{findings}}"),
                "factory.yaml: the prompt prompts/review-quality.md of the state review has the placeholder"
                        + " {{findings}}; the prompt quality knows only {{issue}}, {{tree}}, {{base}}");
    }

    @Test
    void refusesTheTreeInThePromptRisk() {
        assertRefused(Map.of("prompts/risk.md", "{{tree}}"),
                "factory.yaml: the prompt prompts/risk.md of the state risk has the placeholder {{tree}};"
                        + " the prompt risk knows only {{issue}}, {{base}}");
    }

    private static Map<String, String> defaultPrompts() {
        Map<String, String> prompts = new java.util.HashMap<>();
        for (String name : java.util.List.of("implement", "fix", "continue", "review-correctness",
                "review-security", "review-architecture", "review-quality", "risk")) {
            prompts.put("prompts/" + name + ".md", TestDefinitions.readDefaultFile("prompts/" + name + ".md"));
        }
        return prompts;
    }

    private static void assertRefused(Map<String, String> overrides, String expectedMessage) {
        assertThatThrownBy(() -> DefinitionLoader.load(TestDefinitions.filesWith(DEFAULT_YAML, overrides)))
                .isInstanceOf(DefinitionException.class)
                .hasMessage(expectedMessage);
    }
}
