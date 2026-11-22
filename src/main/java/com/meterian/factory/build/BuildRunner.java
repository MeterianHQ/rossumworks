package com.meterian.factory.build;

import com.meterian.factory.agent.ClaudeCommand;
import com.meterian.factory.process.ProcessGroup;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Runs a build command in a worktree, in its own process group. A stop ends the group. */
public final class BuildRunner {

    private static final Duration READ_TIMEOUT = Duration.ofSeconds(5);
    private static final String OUTPUT_NOT_ENDED = "The build output did not end.";

    private final Map<String, String> parentEnvironment;
    private final RunningBuilds runningBuilds = new RunningBuilds();

    public BuildRunner() {
        this(System.getenv());
    }

    BuildRunner(Map<String, String> parentEnvironment) {
        this.parentEnvironment = parentEnvironment;
    }

    /** Ends the build that runs in the worktree, for example on a block of the issue. */
    public void stopBuild(Path worktree) {
        runningBuilds.stop(worktree);
    }

    /** Ends all builds and starts no new build, for example when the factory stops. */
    public void stopAllBuilds() {
        runningBuilds.stopAll();
    }

    public BuildOutcome run(Path worktree, BuildSettings settings) {
        if (runningBuilds.refusesNewBuilds()) {
            return BuildOutcome.createStopped();
        }
        Process process;
        try {
            process = startProcess(worktree, settings);
        } catch (IOException exception) {
            return new BuildOutcome(false, "The build did not start: " + exception.getMessage());
        }
        if (!runningBuilds.register(worktree, process)) {
            endProcessGroup(process);
            return BuildOutcome.createStopped();
        }
        try {
            BuildOutcome outcome = collectOutcome(process);
            return runningBuilds.wasStopped(worktree) ? BuildOutcome.createStopped() : outcome;
        } finally {
            runningBuilds.unregister(worktree);
            endProcessGroup(process);
        }
    }

    private BuildOutcome collectOutcome(Process process) {
        OutputTail tail = new OutputTail();
        CompletableFuture<Void> reading = CompletableFuture.runAsync(() -> readOutput(process, tail),
                runnable -> Thread.ofVirtual().start(runnable));
        try {
            int exitCode = process.waitFor();
            endProcessGroup(process);
            reading.get(READ_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            return BuildOutcome.createExited(exitCode, tail.text());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return new BuildOutcome(false, "The build was interrupted.");
        } catch (TimeoutException exception) {
            return new BuildOutcome(false, tail.text() + "\n" + OUTPUT_NOT_ENDED);
        } catch (ExecutionException exception) {
            return new BuildOutcome(false, "The build output cannot be read: " + exception.getCause());
        }
    }

    private void endProcessGroup(Process process) {
        boolean wasInterrupted = Thread.interrupted();
        ProcessGroup.terminate(process.pid());
        if (wasInterrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private Process startProcess(Path worktree, BuildSettings settings) throws IOException {
        ProcessBuilder builder = new ProcessBuilder("setsid", "--wait", "sh", "-c", settings.command());
        builder.directory(worktree.toFile());
        builder.environment().clear();
        Map<String, String> environment = new LinkedHashMap<>(parentEnvironment);
        environment.putAll(settings.environment());
        builder.environment().putAll(ClaudeCommand.environment(environment));
        builder.redirectErrorStream(true);
        builder.redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null")));
        return builder.start();
    }

    private void readOutput(Process process, OutputTail tail) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            tail.readAll(reader);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}
