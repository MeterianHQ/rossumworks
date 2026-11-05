package com.meterian.factory.definition;

import static org.assertj.core.api.Assertions.assertThat;

import com.meterian.factory.testing.TestTools;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class BuiltInDefinitionTest {

    private final FactoryDefinition definition = BuiltInDefinition.load();

    @Test
    void loadsTheDefaultWorkflowOfTheSpecification() {
        assertThat(definition.workflow().start()).isEqualTo("prepare");
        assertThat(definition.workflow().states().keySet()).containsExactly("prepare", "implement", "build", "review",
                "risk", "merge", "waits-for-approval", "factory-change-approval", "text-changed", "live", "blocked",
                "done");
    }

    @Test
    void expressesTodaysSequence() {
        assertThat(nextStatesOf("prepare")).containsExactly(Map.entry("done", goTo("implement")));
        assertThat(nextStatesOf("implement")).containsExactly(
                Map.entry("changed", goTo("build")),
                Map.entry("no-change", goTo("blocked")),
                Map.entry("question", goTo("live")),
                Map.entry("conflict-left", goTo("implement")),
                Map.entry("failed", goTo("blocked")));
        assertThat(nextStatesOf("build")).containsExactly(
                Map.entry("passed", goTo("review")), Map.entry("failed", goTo("implement")));
        assertThat(nextStatesOf("review")).containsExactly(
                Map.entry("approved", goTo("risk")), Map.entry("rejected", goTo("implement")));
        assertThat(nextStatesOf("merge")).containsExactly(
                Map.entry("merged", goTo("done")),
                Map.entry("needs-approval", goTo("waits-for-approval")),
                Map.entry("needs-new-reviews", goTo("build")),
                Map.entry("conflict", goTo("implement")),
                Map.entry("factory-changed", goTo("factory-change-approval")));
        assertThat(nextStatesOf("live")).containsExactly(
                Map.entry("continue", new Target.ToPrevious()), Map.entry("changed", goTo("build")));
        assertThat(nextStatesOf("blocked")).containsExactly(Map.entry("continue", new Target.ToPrevious()));
        assertThat(nextStatesOf("done")).isEmpty();
    }

    @Test
    void usesTodaysAllowedTools() {
        assertThat(definition.allowedTools()).isEqualTo(TestTools.ALLOWED_TOOLS);
    }

    @Test
    void limitsTheImplementationToTheFirstEntryAndTwoFixes() {
        assertThat(definition.workflow().state("implement").limit())
                .contains(new LimitDefinition(3, goTo("blocked")));
        assertThat(definition.workflow().state("review").limit())
                .contains(new LimitDefinition(10, goTo("blocked")));
        assertThat(definition.workflow().state("build").limit()).isEqualTo(Optional.empty());
    }

    private Iterable<Map.Entry<String, Target>> nextStatesOf(String stateName) {
        return definition.workflow().state(stateName).on().entrySet();
    }

    private static Target goTo(String state) {
        return new Target.ToState(state);
    }
}
