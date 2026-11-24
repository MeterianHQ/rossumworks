package com.meterian.factory.workflow;

import com.meterian.factory.agent.AgentException;
import com.meterian.factory.agent.AgentRunner;
import com.meterian.factory.agent.CredentialMismatchException;
import com.meterian.factory.agent.PhaseRequest;
import com.meterian.factory.agent.SessionReport;
import com.meterian.factory.events.WorkerEvents;
import com.meterian.factory.usage.ContextTracker;
import com.meterian.factory.usage.PhaseGuard;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Runs one agent phase and records it in the guard, and the context of the agent in charge. */
final class PhaseRunner {

    private final AgentRunner agentRunner;
    private final PhaseGuard guard;
    private final ContextTracker contexts;
    private final WorkerEvents events;

    PhaseRunner(AgentRunner agentRunner, PhaseGuard guard, ContextTracker contexts, WorkerEvents events) {
        this.agentRunner = agentRunner;
        this.guard = guard;
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

    /** A phase that may not start reports the wait of the guard. */
    boolean mayStartPhase() {
        Optional<Instant> resumeTime = guard.resumesAt();
        resumeTime.ifPresent(until -> events.writeWait(guard.waitReason(), until));
        return resumeTime.isEmpty();
    }

    private SessionReport runAndRecordUsage(PhaseRequest request) throws AgentException, CredentialMismatchException {
        SessionReport report = agentRunner.runPhase(request);
        guard.recordSession(request.issueNumber(), report);
        return report;
    }
}
