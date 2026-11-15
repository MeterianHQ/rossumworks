package com.meterian.factory.definition;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TargetTest {

    @Test
    void readsPreviousAsTheTargetPrevious() {
        assertThat(Target.read("previous")).isEqualTo(new Target.ToPrevious());
    }

    @Test
    void readsAnotherTextAsAState() {
        assertThat(Target.read("build")).isEqualTo(new Target.ToState("build"));
    }
}
