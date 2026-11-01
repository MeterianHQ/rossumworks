package com.meterian.factory.agent;

public interface AgentRunner {

    SessionReport runPhase(PhaseRequest request) throws AgentException, ApiKeyDetectedException;

    void stopPhase(int issueNumber);

    /** After this call, each new runPhase throws an AgentException. The factory calls it when it stops. */
    void refuseNewPhases();
}
