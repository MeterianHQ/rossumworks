package com.meterian.factory.git;

import com.meterian.factory.process.ChildEnvironment;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/** Runs one git process in an isolated environment. A process with a timeout ends at the timeout. */
final class GitProcess {

    private static final Map<String, String> ISOLATION = Map.of(
            "GIT_CONFIG_GLOBAL", "/dev/null",
            "GIT_CONFIG_NOSYSTEM", "1",
            "GIT_TERMINAL_PROMPT", "0",
            "GIT_SSH_COMMAND", "ssh -o BatchMode=yes -o ConnectTimeout=30 -o ServerAliveInterval=15"
                    + " -o ServerAliveCountMax=4");
    private static final List<String> REDIRECTING_VARIABLES = List.of(
            "GIT_DIR", "GIT_WORK_TREE", "GIT_INDEX_FILE", "GIT_ASKPASS", "SSH_ASKPASS");

    /** The output holds the bytes of the standard output as git wrote them. */
    record Result(int exitCode, byte[] output, String errorOutput) {

        String outputText() {
            return new String(output, StandardCharsets.UTF_8);
        }
    }

    private final Map<String, String> extraEnvironment;

    /** The isolation entries always win over the extra entries. */
    GitProcess(Map<String, String> extraEnvironment) {
        this.extraEnvironment = Map.copyOf(extraEnvironment);
    }

    /** The process gets no input: its standard input is closed at the start. */
    Result execute(Path directory, Optional<Duration> timeout, List<String> arguments) {
        Path outputFile = null;
        Path errorFile = null;
        try {
            outputFile = Files.createTempFile("factory-git", ".out");
            errorFile = Files.createTempFile("factory-git", ".err");
            Process process = start(directory, arguments, outputFile, errorFile);
            process.getOutputStream().close();
            int exitCode = waitForExit(process, timeout, arguments);
            return new Result(exitCode, Files.readAllBytes(outputFile), Files.readString(errorFile));
        } catch (IOException exception) {
            throw new GitException("git " + String.join(" ", arguments) + " cannot run", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new GitException("git " + String.join(" ", arguments) + " was interrupted", exception);
        } finally {
            deleteQuietly(outputFile);
            deleteQuietly(errorFile);
        }
    }

    private Process start(Path directory, List<String> arguments, Path outputFile, Path errorFile)
            throws IOException {
        List<String> command = new ArrayList<>(List.of("git"));
        command.addAll(arguments);
        ProcessBuilder builder = new ProcessBuilder(command)
                .directory(directory.toFile())
                .redirectOutput(outputFile.toFile())
                .redirectError(errorFile.toFile());
        Map<String, String> environment = builder.environment();
        environment.putAll(extraEnvironment);
        ChildEnvironment.removeAnthropicCredentials(environment);
        environment.keySet().removeAll(REDIRECTING_VARIABLES);
        environment.putAll(ISOLATION);
        return builder.start();
    }

    private static int waitForExit(Process process, Optional<Duration> timeout, List<String> arguments)
            throws InterruptedException {
        if (timeout.isEmpty()) {
            return process.waitFor();
        }
        if (process.waitFor(timeout.get().toMillis(), TimeUnit.MILLISECONDS)) {
            return process.exitValue();
        }
        endWithDescendants(process);
        throw new GitException("git " + arguments.getFirst() + " did not end in " + describe(timeout.get()) + ".");
    }

    /** Git does not lead a process group, so the descendants (ssh, a hook) end one by one, then git. */
    private static void endWithDescendants(Process process) throws InterruptedException {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
        process.waitFor();
    }

    private static String describe(Duration duration) {
        boolean inMinutes = duration.toSeconds() % 60 == 0;
        long amount = inMinutes ? duration.toMinutes() : duration.toSeconds();
        String unit = inMinutes ? "minute" : "second";
        return amount + " " + unit + (amount == 1 ? "" : "s");
    }

    private static void deleteQuietly(Path file) {
        try {
            if (file != null) {
                Files.deleteIfExists(file);
            }
        } catch (IOException ignored) {
            // The file is in the temporary folder.
        }
    }
}
