package com.meterian.factory.agent;

import java.nio.file.Path;
import java.util.UUID;

public interface AgentRunner {

    SessionReport runPhase(PhaseRequest request) throws AgentException, CredentialMismatchException;

    void stopPhase(int issueNumber);

    /** Tells if claude saved the session. Only a saved session can resume: a very early stop leaves no file. */
    boolean hasSavedSession(UUID sessionId, Path worktree);

    /** After this call, each new runPhase throws an AgentException. The factory calls it when it stops. */
    void refuseNewPhases();
}
