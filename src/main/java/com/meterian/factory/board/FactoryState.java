package com.meterian.factory.board;

/** The state of the whole factory, in the top right corner of the page. */
public enum FactoryState {

    RUNNING("Running"),
    /** The owner asked for a stop, and at least one issue is still in work. */
    STOPPING("Stopping"),
    STOPPED("Stopped");

    private final String displayName;

    FactoryState(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }

    public static FactoryState find(boolean stopRequested, int issuesInWork) {
        if (!stopRequested) {
            return RUNNING;
        }
        return issuesInWork > 0 ? STOPPING : STOPPED;
    }
}
