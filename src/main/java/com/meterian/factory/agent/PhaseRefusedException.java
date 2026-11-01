package com.meterian.factory.agent;

/** The runner refuses a new phase because the factory stops. No session starts. */
public class PhaseRefusedException extends AgentException {

    public PhaseRefusedException(String message) {
        super(message);
    }
}
