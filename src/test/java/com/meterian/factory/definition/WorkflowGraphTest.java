package com.meterian.factory.definition;

import static com.meterian.factory.definition.TestDefinitions.DEFAULT_YAML;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class WorkflowGraphTest {

    private static final String IMPLEMENT_LIMIT = "      limit: { entries: 3, then: blocked }\n";

    @Test
    void acceptsTheDefaultWorkflow() throws Exception {
        FactoryDefinition definition = DefinitionLoader.load(TestDefinitions.filesWith(DEFAULT_YAML));

        assertThat(definition.workflow().states()).hasSize(12);
    }

    @Test
    void refusesAnUnreachableState() {
        String securityScan = "    security-scan:\n      column: in-progress\n      on: { continue: blocked }\n";

        assertRefused(DEFAULT_YAML.replace("    review:\n", securityScan + "    review:\n"),
                "factory.yaml: no state goes to the state security-scan");
    }

    @Test
    void countsTheOnAnyStateTargetsAsReachable() throws Exception {
        String yaml = DEFAULT_YAML
                .replace("no-change: blocked", "no-change: live")
                .replace("failed: blocked", "failed: live")
                .replace("then: blocked", "then: live");

        FactoryDefinition definition = DefinitionLoader.load(TestDefinitions.filesWith(yaml));

        assertThat(definition.workflow().state("blocked")).isNotNull();
    }

    @Test
    void refusesAStateWithoutAPathToFinish() {
        String reviewAgain = "    review-again:\n      column: in-review\n      on: { continue: review }\n";
        String yaml = DEFAULT_YAML
                .replace("on: { approved: risk, rejected: implement }",
                        "on: { approved: review-again, rejected: review-again }")
                .replace("limit: { entries: 10, then: blocked }", "limit: { entries: 10, then: review-again }")
                .replace("on: { continue: previous, changed: build }", "on: { continue: previous, changed: risk }")
                .replace("    risk:\n", reviewAgain + "    risk:\n");

        assertRefused(yaml, "factory.yaml: the state review has no path to the state done");
    }

    @Test
    void countsPreviousAsAPath() throws Exception {
        FactoryDefinition definition = DefinitionLoader.load(TestDefinitions.filesWith(DEFAULT_YAML));

        assertThat(definition.workflow().state("text-changed").on().get("approved"))
                .isEqualTo(new Target.ToPrevious());
    }

    @Test
    void refusesACycleWithoutLimit() {
        assertRefused(DEFAULT_YAML.replace(IMPLEMENT_LIMIT, ""),
                "factory.yaml: the cycle implement, build has no limit");
    }

    @Test
    void acceptsACycleThroughAWaitingState() throws Exception {
        String yaml = withoutOtherMovesToImplement(DEFAULT_YAML.replace(IMPLEMENT_LIMIT, ""))
                .replace("conflict-left: implement", "conflict-left: blocked");

        FactoryDefinition definition = DefinitionLoader.load(TestDefinitions.filesWith(yaml));

        assertThat(definition.workflow().state("blocked").on().get("continue")).isEqualTo(new Target.ToPrevious());
    }

    @Test
    void refusesACycleThroughTheThenOfALimit() {
        assertRefused(DEFAULT_YAML.replace("limit: { entries: 3, then: blocked }",
                        "limit: { entries: 3, then: build }"),
                "factory.yaml: the cycle implement, build has no limit");
    }

    @Test
    void refusesASelfCycleWithoutLimit() {
        assertRefused(withoutOtherMovesToImplement(DEFAULT_YAML.replace(IMPLEMENT_LIMIT, "")),
                "factory.yaml: the cycle implement has no limit");
    }

    /** The moves build, review, and merge to implement go to blocked, so only the self-move stays. */
    private static String withoutOtherMovesToImplement(String yaml) {
        return yaml
                .replace("on: { passed: review, failed: implement }", "on: { passed: review, failed: blocked }")
                .replace("rejected: implement", "rejected: blocked")
                .replace("conflict: implement", "conflict: blocked");
    }

    private static void assertRefused(String yaml, String expectedMessage) {
        assertThatThrownBy(() -> DefinitionLoader.load(TestDefinitions.filesWith(yaml)))
                .isInstanceOf(DefinitionException.class)
                .hasMessage(expectedMessage);
    }
}
