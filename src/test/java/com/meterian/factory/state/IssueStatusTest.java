package com.meterian.factory.state;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

class IssueStatusTest {

    @Test
    void givesTheSixLabelNames() {
        assertThat(Arrays.stream(IssueStatus.values()).map(IssueStatus::labelName)).containsExactly(
                "factory:status:queued", "factory:status:in-progress", "factory:status:in-review",
                "factory:status:waits-for-you", "factory:status:blocked", "factory:status:done");
    }

    @Test
    void givesSixDistinctColors() {
        assertThat(Arrays.stream(IssueStatus.values()).map(IssueStatus::labelColor).distinct()).hasSize(6)
                .allMatch(color -> color.matches("[0-9a-f]{6}"));
    }

    @Test
    void describesTheLabel() {
        assertThat(IssueStatus.WAITS_FOR_YOU.labelDescription())
                .isEqualTo("Factory status: Waits for you. The factory sets this label.");
    }
}
