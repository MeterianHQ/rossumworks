package com.meterian.factory.definition;

import static org.assertj.core.api.Assertions.assertThat;

import com.meterian.factory.testing.TestTools;
import com.meterian.factory.state.WaitReason;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BuiltInDefinitionTest {

    private final FactoryDefinition definition = BuiltInDefinition.load();

    @Test
    void loadsTheBuiltInDefinition() {
        assertThat(definition.workflow().start()).isEqualTo("prepare");
        assertThat(definition.workflow().states().keySet())
                .containsExactly("prepare", "implement", "build", "review", "risk", "merge", "done");
    }

    @Test
    void expressesTodaysSequence() {
        assertThat(transitionsOf("prepare")).containsExactly(Map.entry("done", goTo("implement")));
        assertThat(transitionsOf("implement")).containsExactly(
                Map.entry("changed", goTo("build")),
                Map.entry("question", new Transition.Wait(WaitReason.QUESTION)),
                Map.entry("no-change", new Transition.Block(WaitReason.FAILURE)),
                Map.entry("failed", new Transition.Block(WaitReason.FAILURE)),
                Map.entry("conflict-left", new Transition.EnterLoop("fix-loop")));
        assertThat(transitionsOf("build")).containsExactly(
                Map.entry("passed", goTo("review")), Map.entry("failed", new Transition.EnterLoop("fix-loop")));
        assertThat(transitionsOf("review")).containsExactly(
                Map.entry("approved", goTo("risk")), Map.entry("rejected", new Transition.EnterLoop("fix-loop")));
        assertThat(transitionsOf("risk")).containsExactly(Map.entry("assessed", goTo("merge")));
        assertThat(transitionsOf("merge")).containsExactly(
                Map.entry("merged", new Transition.GoTo("done", true)),
                Map.entry("needs-approval", new Transition.Wait(WaitReason.MERGE_NEEDS_APPROVAL)),
                Map.entry("needs-new-reviews", new Transition.GoTo("build", true)),
                Map.entry("conflict", new Transition.EnterLoop("fix-loop")));
        assertThat(transitionsOf("done")).isEmpty();
    }

    @Test
    void usesTodaysAllowedTools() {
        assertThat(definition.allowedTools()).isEqualTo(TestTools.ALLOWED_TOOLS);
    }

    @Test
    void hasTheFixLoopLimitOfToday() {
        assertThat(definition.limits()).containsEntry("fix-loops", 2);
        assertThat(definition.workflow().loop("fix-loop").orElseThrow().limit()).isEqualTo(2);
    }

    private List<Map.Entry<String, Transition>> transitionsOf(String stateName) {
        return List.copyOf(definition.workflow().state(stateName).transitions().entrySet());
    }

    private static Transition goTo(String state) {
        return new Transition.GoTo(state, false);
    }
}
