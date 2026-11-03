package com.meterian.factory.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import com.meterian.factory.agent.ContextUsage;
import com.meterian.factory.agent.PhaseRequest;
import com.meterian.factory.configuration.ClaudeSettings;
import com.meterian.factory.testing.FakeAgentRunner;
import com.meterian.factory.usage.ContextTracker;
import com.meterian.factory.usage.UsageTracker;
import java.nio.file.Path;
import java.time.Clock;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PhaseRunnerTest {

    private static final int ISSUE = 7;

    @TempDir
    Path worktree;

    private final ContextTracker contexts = new ContextTracker();

    private PhaseRunner createPhaseRunner(FakeAgentRunner agent) {
        return new PhaseRunner(agent, new UsageTracker(0.90, Clock.systemUTC()), contexts);
    }

    @Test
    void theAgentInChargeReportsItsContext() throws Exception {
        PhaseRunner phaseRunner = createPhaseRunner(
                new FakeAgentRunner().thenChangeNothing().withContextUsage(26262, 1_000_000));

        phaseRunner.runPhase(new PhaseRequest(ISSUE, UUID.randomUUID(), true, worktree, "Implement the issue.",
                ClaudeSettings.DEFAULT_ALLOWED_TOOLS));

        assertThat(contexts.find(ISSUE)).contains(new ContextUsage(26262, 1_000_000));
    }

    @Test
    void aReviewSessionDoesNotChangeTheContext() throws Exception {
        PhaseRunner phaseRunner = createPhaseRunner(new FakeAgentRunner()
                .thenChangeNothing().withContextUsage(26262, 1_000_000)
                .thenChangeNothing().withContextUsage(500, 200_000));

        phaseRunner.runPhase(new PhaseRequest(ISSUE, UUID.randomUUID(), true, worktree, "Implement the issue.",
                ClaudeSettings.DEFAULT_ALLOWED_TOOLS));
        phaseRunner.runInNewSession(ISSUE, worktree, "Review the change.", ClaudeSettings.DEFAULT_ALLOWED_TOOLS);

        assertThat(contexts.find(ISSUE)).contains(new ContextUsage(26262, 1_000_000));
    }
}
