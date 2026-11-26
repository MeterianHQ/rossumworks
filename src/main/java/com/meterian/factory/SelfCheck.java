package com.meterian.factory;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * The command self-check: it runs each step, and prints one line for each step. A native program needs GraalVM
 * metadata for the resources and the reflection. A part without its metadata fails here, without network.
 */
final class SelfCheck {

    private static final String FOLDER_PREFIX = "rossumworks-self-check-";

    private final List<SelfCheckStep> steps;
    private final PrintStream output;

    SelfCheck(List<SelfCheckStep> steps, PrintStream output) {
        this.steps = List.copyOf(steps);
        this.output = output;
    }

    static SelfCheck withAllSteps(PrintStream output) {
        return new SelfCheck(SelfCheckSteps.all(), output);
    }

    int run() {
        boolean allStepsPassed = true;
        for (SelfCheckStep step : steps) {
            allStepsPassed &= runStep(step);
        }
        output.println(allStepsPassed ? "The self-check passed." : "The self-check failed. The program is not complete.");
        return allStepsPassed ? Main.EXIT_SUCCESS : Main.EXIT_FAILURE;
    }

    /** A step that throws any exception fails, so that the next steps still run. */
    private boolean runStep(SelfCheckStep step) {
        try {
            Path folder = Files.createTempDirectory(FOLDER_PREFIX);
            try {
                step.check().run(folder);
            } finally {
                deleteFolder(folder);
            }
            output.println("ok: " + step.name());
            return true;
        } catch (Exception | LinkageError exception) {
            output.println("failed: " + step.name() + ": " + describe(exception));
            return false;
        }
    }

    private static String describe(Throwable exception) {
        String message = exception.getMessage();
        return message == null ? exception.getClass().getName() : message;
    }

    private static void deleteFolder(Path folder) throws IOException {
        try (Stream<Path> paths = Files.walk(folder)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }
}
