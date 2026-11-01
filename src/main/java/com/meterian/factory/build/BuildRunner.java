package com.meterian.factory.build;

import com.meterian.factory.agent.ClaudeCommand;
import com.meterian.factory.configuration.TargetRepository;
import com.meterian.factory.process.ProcessGroup;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

/** Runs the build command of a target in a worktree, in its own process group. */
public final class BuildRunner {

    private static final int TAIL_LINES = 200;

    private final Map<String, String> parentEnvironment;

    public BuildRunner() {
        this(System.getenv());
    }

    BuildRunner(Map<String, String> parentEnvironment) {
        this.parentEnvironment = parentEnvironment;
    }

    public BuildOutcome run(Path worktree, TargetRepository target) {
        try {
            Process process = startProcess(worktree, target);
            CompletableFuture<String> tail = CompletableFuture.supplyAsync(() -> readTail(process),
                    runnable -> Thread.ofVirtual().start(runnable));
            int exitCode = process.waitFor();
            ProcessGroup.terminate(process.pid());
            return new BuildOutcome(exitCode == 0, tail.get());
        } catch (IOException exception) {
            return new BuildOutcome(false, "The build did not start: " + exception.getMessage());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return new BuildOutcome(false, "The build was interrupted.");
        } catch (ExecutionException exception) {
            return new BuildOutcome(false, "The build output cannot be read: " + exception.getCause());
        }
    }

    private Process startProcess(Path worktree, TargetRepository target) throws IOException {
        ProcessBuilder builder = new ProcessBuilder("setsid", "--wait", "sh", "-c", target.buildCommand());
        builder.directory(worktree.toFile());
        builder.environment().clear();
        builder.environment().putAll(ClaudeCommand.environment(parentEnvironment));
        builder.environment().putAll(target.buildEnvironment());
        builder.redirectErrorStream(true);
        builder.redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null")));
        return builder.start();
    }

    private String readTail(Process process) {
        Deque<String> lines = new ArrayDeque<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                lines.addLast(line);
                if (lines.size() > TAIL_LINES) {
                    lines.removeFirst();
                }
            }
        } catch (IOException exception) {
            throw new java.io.UncheckedIOException(exception);
        }
        return String.join("\n", lines);
    }
}
