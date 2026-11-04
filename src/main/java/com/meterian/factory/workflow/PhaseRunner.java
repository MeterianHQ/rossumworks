package com.meterian.factory.workflow;

import com.meterian.factory.agent.AgentException;
import com.meterian.factory.agent.AgentRunner;
import com.meterian.factory.agent.ApiKeyDetectedException;
import com.meterian.factory.agent.PhaseRequest;
import com.meterian.factory.agent.SessionReport;
import com.meterian.factory.usage.ContextTracker;
import com.meterian.factory.usage.UsageTracker;
import java.nio.file.Path;
import java.util.UUID;

/** Runs one agent phase and records the usage windows of its report, and the context of the agent in charge. */
final class PhaseRunner {

    private final AgentRunner agentRunner;
    private final UsageTracker usage;
    private final ContextTracker contexts;

    PhaseRunner(AgentRunner agentRunner, UsageTracker usage, ContextTracker contexts) {
        this.agentRunner = agentRunner;
        this.usage = usage;
        this.contexts = contexts;
    }

    /** Runs a phase of the agent in charge of the issue. */
    SessionReport runPhase(PhaseRequest request) throws AgentException, ApiKeyDetectedException {
        SessionReport report = runAndRecordUsage(request);
        report.contextUsage().ifPresent(context -> contexts.record(request.issueNumber(), context));
        return report;
    }

    /** Runs a phase in a new session that no other phase uses. The context bar does not show this session. */
    SessionReport runInNewSession(int issueNumber, Path worktree, String prompt)
            throws AgentException, ApiKeyDetectedException {
        return runAndRecordUsage(new PhaseRequest(issueNumber, UUID.randomUUID(), true, worktree, prompt));
    }

    boolean hasSavedSession(UUID sessionId, Path worktree) {
        return agentRunner.hasSavedSession(sessionId, worktree);
    }

    boolean mayStartPhase() {
        return usage.mayStartPhase();
    }

    private SessionReport runAndRecordUsage(PhaseRequest request) throws AgentException, ApiKeyDetectedException {
        SessionReport report = agentRunner.runPhase(request);
        usage.record(report.usageWindows());
        return report;
    }
}
