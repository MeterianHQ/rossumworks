package com.meterian.factory.workflow;

import com.meterian.factory.agent.AgentException;
import com.meterian.factory.agent.AgentRunner;
import com.meterian.factory.agent.CredentialMismatchException;
import com.meterian.factory.agent.PhaseRequest;
import com.meterian.factory.agent.SessionReport;
import com.meterian.factory.events.WorkerEvents;
import com.meterian.factory.usage.ContextTracker;
import com.meterian.factory.usage.UsageTracker;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Runs one agent phase and records the usage windows of its report, and the context of the agent in charge. */
final class PhaseRunner {

    private static final String USAGE_WAIT_REASON = "usage limit";

    private final AgentRunner agentRunner;
    private final UsageTracker usage;
    private final ContextTracker contexts;
    private final WorkerEvents events;

    PhaseRunner(AgentRunner agentRunner, UsageTracker usage, ContextTracker contexts, WorkerEvents events) {
        this.agentRunner = agentRunner;
        this.usage = usage;
        this.contexts = contexts;
        this.events = events;
    }

    /** Runs a phase of the agent in charge of the issue. */
    SessionReport runPhase(PhaseRequest request) throws AgentException, CredentialMismatchException {
        SessionReport report = runAndRecordUsage(request);
        report.contextUsage().ifPresent(context -> contexts.record(request.issueNumber(), context));
        return report;
    }

    /** Runs a phase in a new session that no other phase uses. The context bar does not show this session. */
    SessionReport runInNewSession(int issueNumber, Path worktree, String prompt, List<String> allowedTools)
            throws AgentException, CredentialMismatchException {
        return runAndRecordUsage(
                new PhaseRequest(issueNumber, UUID.randomUUID(), true, worktree, prompt, allowedTools));
    }

    boolean hasSavedSession(UUID sessionId, Path worktree) {
        return agentRunner.hasSavedSession(sessionId, worktree);
    }

    /** A phase that may not start reports the wait for the usage limit. */
    boolean mayStartPhase() {
        Optional<Instant> resumeTime = usage.resumesAt();
        resumeTime.ifPresent(until -> events.writeWait(USAGE_WAIT_REASON, until));
        return resumeTime.isEmpty();
    }

    private SessionReport runAndRecordUsage(PhaseRequest request) throws AgentException, CredentialMismatchException {
        SessionReport report = agentRunner.runPhase(request);
        usage.record(report.usageWindows());
        return report;
    }
}
