package com.meterian.factory.build;

import java.util.Optional;

/**
 * The result of a build. The output tail holds the last 200 lines. A stopped build did not pass. The exit code is
 * empty when the build command did not end by itself.
 */
public record BuildOutcome(boolean passed, String outputTail, boolean stopped, Optional<Integer> exitCode) {

    private static final String STOPPED_TEXT = "The build was stopped.";

    public BuildOutcome(boolean passed, String outputTail) {
        this(passed, outputTail, false, Optional.empty());
    }

    public static BuildOutcome createExited(int exitCode, String outputTail) {
        return new BuildOutcome(exitCode == 0, outputTail, false, Optional.of(exitCode));
    }

    public static BuildOutcome createStopped() {
        return new BuildOutcome(false, STOPPED_TEXT, true, Optional.empty());
    }
}
