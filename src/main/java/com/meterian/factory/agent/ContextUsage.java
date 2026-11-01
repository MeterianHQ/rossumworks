package com.meterian.factory.agent;

public record ContextUsage(long tokensInUse, long contextWindow) {

    public double fraction() {
        return contextWindow == 0 ? 0.0 : (double) tokensInUse / contextWindow;
    }
}
