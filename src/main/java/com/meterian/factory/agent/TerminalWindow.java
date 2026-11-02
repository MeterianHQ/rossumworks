package com.meterian.factory.agent;

import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Opens a desktop terminal window that attaches to a session of the tmux server of the factory.
 * The terminal command comes from the configuration. The tmux server and the session name are separate arguments.
 */
public final class TerminalWindow {

    private static final Duration OPEN_WAIT = Duration.ofSeconds(5);
    private static final String FAILURE_PREFIX = "The terminal window did not open: ";

    private final List<String> terminalCommand;
    private final String tmuxServer;
    private final Map<String, String> parentEnvironment;

    public TerminalWindow(List<String> terminalCommand, String tmuxServer, Map<String, String> parentEnvironment) {
        this.terminalCommand = List.copyOf(terminalCommand);
        this.tmuxServer = tmuxServer;
        this.parentEnvironment = Map.copyOf(parentEnvironment);
    }

    /** A terminal that still runs after the wait is the open window. Nothing stops it. */
    public void open(String sessionName) throws LiveSessionException {
        Process process = startTerminal(sessionName);
        try {
            if (process.waitFor(OPEN_WAIT.toMillis(), TimeUnit.MILLISECONDS) && process.exitValue() != 0) {
                throw new LiveSessionException(FAILURE_PREFIX + "the terminal command ended with the exit code "
                        + process.exitValue() + ".");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new LiveSessionException(FAILURE_PREFIX + "the wait was interrupted.", exception);
        }
    }

    private Process startTerminal(String sessionName) throws LiveSessionException {
        ProcessBuilder builder = new ProcessBuilder(createCommand(sessionName));
        builder.environment().clear();
        builder.environment().putAll(ClaudeCommand.environment(parentEnvironment));
        builder.redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null")));
        builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        builder.redirectError(ProcessBuilder.Redirect.DISCARD);
        try {
            return builder.start();
        } catch (IOException exception) {
            throw new LiveSessionException(FAILURE_PREFIX + exception.getMessage(), exception);
        }
    }

    private List<String> createCommand(String sessionName) {
        List<String> command = new ArrayList<>(terminalCommand);
        command.addAll(List.of("tmux", "-L", tmuxServer, "attach", "-t", sessionName));
        return command;
    }
}
