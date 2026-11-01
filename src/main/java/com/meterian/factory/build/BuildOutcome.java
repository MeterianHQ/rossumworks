package com.meterian.factory.build;

/** The result of a build. The output tail holds the last 200 lines. A stopped build did not pass. */
public record BuildOutcome(boolean passed, String outputTail, boolean stopped) {

    private static final String STOPPED_TEXT = "The build was stopped.";

    public BuildOutcome(boolean passed, String outputTail) {
        this(passed, outputTail, false);
    }

    public static BuildOutcome createStopped() {
        return new BuildOutcome(false, STOPPED_TEXT, true);
    }
}
