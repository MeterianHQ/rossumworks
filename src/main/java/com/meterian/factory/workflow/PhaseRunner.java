package com.meterian.factory.workflow;

import com.meterian.factory.agent.AgentException;
import com.meterian.factory.agent.AgentRunner;
import com.meterian.factory.agent.ApiKeyDetectedException;
import com.meterian.factory.agent.PhaseRequest;
import com.meterian.factory.agent.SessionReport;
import com.meterian.factory.usage.UsageTracker;
import java.nio.file.Path;
import java.util.UUID;

/** Runs one agent phase and records the usage windows of its report. */
final class PhaseRunner {

    private final AgentRunner agentRunner;
    private final UsageTracker usage;

    PhaseRunner(AgentRunner agentRunner, UsageTracker usage) {
        this.agentRunner = agentRunner;
        this.usage = usage;
    }

    SessionReport runPhase(PhaseRequest request) throws AgentException, ApiKeyDetectedException {
        SessionReport report = agentRunner.runPhase(request);
        usage.record(report.usageWindows());
        return report;
    }

    /** Runs a phase in a new session that no other phase uses. */
    SessionReport runInNewSession(int issueNumber, Path worktree, String prompt)
            throws AgentException, ApiKeyDetectedException {
        return runPhase(new PhaseRequest(issueNumber, UUID.randomUUID(), true, worktree, prompt));
    }

    boolean mayStartPhase() {
        return usage.mayStartPhase();
    }
}
