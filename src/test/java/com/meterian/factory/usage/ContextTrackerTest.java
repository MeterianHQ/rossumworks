package com.meterian.factory.usage;

import static org.assertj.core.api.Assertions.assertThat;

import com.meterian.factory.agent.ContextUsage;
import org.junit.jupiter.api.Test;

class ContextTrackerTest {

    private final ContextTracker tracker = new ContextTracker();

    @Test
    void keepsTheNewestContextOfEachIssue() {
        tracker.record(3, new ContextUsage(100, 1000));
        tracker.record(3, new ContextUsage(500, 1000));
        tracker.record(4, new ContextUsage(10, 200));

        assertThat(tracker.find(3)).contains(new ContextUsage(500, 1000));
        assertThat(tracker.find(4)).contains(new ContextUsage(10, 200));
        assertThat(tracker.find(5)).isEmpty();
    }
}
