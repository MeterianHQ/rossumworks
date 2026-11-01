package com.meterian.factory.agent;

public interface AgentRunner {

    SessionReport runPhase(PhaseRequest request) throws AgentException, ApiKeyDetectedException;

    void stopPhase(int issueNumber);
}
