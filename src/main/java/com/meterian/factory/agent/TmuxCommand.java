package com.meterian.factory.agent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Runs tmux commands on one tmux server. Each command has a time limit of 10 seconds. */
final class TmuxCommand {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private record CompletedCommand(int exitCode, String output, String error) {
    }

    private final List<String> serverOptions;

    /** Uses the default tmux server of the user. */
    TmuxCommand() {
        this.serverOptions = List.of();
    }

    /** Uses the private tmux server with this socket name. */
    TmuxCommand(String socketName) {
        this.serverOptions = List.of("-L", socketName);
    }

    /** Runs the command and returns its standard output. A failure names the command. */
    String run(String... arguments) throws LiveSessionException {
        CompletedCommand completed = execute(arguments);
        if (completed.exitCode() != 0) {
            throw new LiveSessionException("The command " + describe(arguments) + " failed: "
                    + completed.error().strip());
        }
        return completed.output();
    }

    /** Runs the command and tells if its exit code is 0. */
    boolean succeeds(String... arguments) throws LiveSessionException {
        return execute(arguments).exitCode() == 0;
    }

    private CompletedCommand execute(String... arguments) throws LiveSessionException {
        Path outputFile = null;
        Path errorFile = null;
        try {
            outputFile = Files.createTempFile("tmux-output", ".txt");
            errorFile = Files.createTempFile("tmux-error", ".txt");
            Process process = start(arguments, outputFile, errorFile);
            waitForTheEnd(process, arguments);
            return new CompletedCommand(process.exitValue(), Files.readString(outputFile), Files.readString(errorFile));
        } catch (IOException exception) {
            throw new LiveSessionException("The command " + describe(arguments) + " cannot run.", exception);
        } finally {
            deleteQuietly(outputFile);
            deleteQuietly(errorFile);
        }
    }

    /** The output goes to files, not to pipes: a tmux server that starts can keep a pipe open. */
    private Process start(String[] arguments, Path outputFile, Path errorFile) throws IOException {
        List<String> command = new ArrayList<>();
        command.add("tmux");
        command.addAll(serverOptions);
        command.addAll(List.of(arguments));
        return new ProcessBuilder(command)
                .redirectInput(ProcessBuilder.Redirect.from(Path.of("/dev/null").toFile()))
                .redirectOutput(outputFile.toFile())
                .redirectError(errorFile.toFile())
                .start();
    }

    private void waitForTheEnd(Process process, String[] arguments) throws LiveSessionException {
        try {
            if (!process.waitFor(TIMEOUT.toSeconds(), TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new LiveSessionException("The command " + describe(arguments) + " did not end in "
                        + TIMEOUT.toSeconds() + " seconds.");
            }
        } catch (InterruptedException exception) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new LiveSessionException("The command " + describe(arguments) + " was interrupted.", exception);
        }
    }

    /** Names only the subcommand: the other arguments can contain the environment of the session. */
    private String describe(String[] arguments) {
        return "tmux " + arguments[0];
    }

    private void deleteQuietly(Path file) {
        if (file == null) {
            return;
        }
        try {
            Files.deleteIfExists(file);
        } catch (IOException exception) {
            file.toFile().deleteOnExit();
        }
    }
}
