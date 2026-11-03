package com.meterian.factory.configuration;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** The facts of this machine. The workflow, the build, and the accounts are in the folder .factory. */
public record FactoryConfiguration(
        RepositoryName repository,
        Path workDirectory,
        Path gatekeeperDirectory,
        Path liveDirectory,
        WorkerLimits limits,
        Map<String, String> buildEnvironment,
        IsolationMode isolationMode,
        ClaudeSettings claude,
        Optional<WebSettings> web,
        List<String> terminalCommand) {

    public static final List<String> DEFAULT_TERMINAL_COMMAND = List.of("gnome-terminal", "--");

    private static final String CLONE_FOLDER = "repository";

    public Path cloneDirectory() {
        return workDirectory.resolve(CLONE_FOLDER);
    }
}
