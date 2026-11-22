package com.meterian.factory.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import com.meterian.factory.agent.ContextUsage;
import com.meterian.factory.agent.PhaseRequest;
import com.meterian.factory.agent.UsageWindow;
import com.meterian.factory.testing.CollectedEvents;
import com.meterian.factory.testing.FakeAgentRunner;
import com.meterian.factory.testing.TestTools;
import com.meterian.factory.usage.ContextTracker;
import com.meterian.factory.usage.UsageTracker;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PhaseRunnerTest {

    private static final int ISSUE = 7;

    @TempDir
    Path worktree;

    private final ContextTracker contexts = new ContextTracker();

    private PhaseRunner createPhaseRunner(FakeAgentRunner agent) {
        return new PhaseRunner(agent, new UsageTracker(0.90, Clock.systemUTC()), contexts,
                CollectedEvents.discarded());
    }

    @Test
    void theAgentInChargeReportsItsContext() throws Exception {
        PhaseRunner phaseRunner = createPhaseRunner(
                new FakeAgentRunner().thenChangeNothing().withContextUsage(26262, 1_000_000));

        phaseRunner.runPhase(new PhaseRequest(ISSUE, UUID.randomUUID(), true, worktree, "Implement the issue.",
                TestTools.ALLOWED_TOOLS));

        assertThat(contexts.find(ISSUE)).contains(new ContextUsage(26262, 1_000_000));
    }

    @Test
    void aReviewSessionDoesNotChangeTheContext() throws Exception {
        PhaseRunner phaseRunner = createPhaseRunner(new FakeAgentRunner()
                .thenChangeNothing().withContextUsage(26262, 1_000_000)
                .thenChangeNothing().withContextUsage(500, 200_000));

        phaseRunner.runPhase(new PhaseRequest(ISSUE, UUID.randomUUID(), true, worktree, "Implement the issue.",
                TestTools.ALLOWED_TOOLS));
        phaseRunner.runInNewSession(ISSUE, worktree, "Review the change.", TestTools.ALLOWED_TOOLS);

        assertThat(contexts.find(ISSUE)).contains(new ContextUsage(26262, 1_000_000));
    }

    @Test
    void aPhaseThatMayNotStartWritesTheWaitForTheUsageLimit() {
        UsageTracker usage = new UsageTracker(0.90, Clock.systemUTC());
        Instant reset = Instant.now().plusSeconds(3600);
        usage.record(List.of(new UsageWindow("five_hour", 0.95, reset, "allowed", null)));
        CollectedEvents events = new CollectedEvents();
        PhaseRunner phaseRunner = new PhaseRunner(new FakeAgentRunner(), usage, contexts, events.workerEvents());

        boolean mayStart = phaseRunner.mayStartPhase();
        phaseRunner.mayStartPhase();

        assertThat(mayStart).isFalse();
        assertThat(events.ofType("wait")).singleElement().satisfies(line -> {
            assertThat(line.path("reason").asText()).isEqualTo("usage limit");
            assertThat(Instant.parse(line.path("until").asText())).isEqualTo(reset.truncatedTo(ChronoUnit.SECONDS));
        });
    }
}
