package com.meterian.factory.setup;

import com.meterian.factory.configuration.RepositoryName;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/** The values that the command create writes to the file worker.yaml. */
public record WorkerSettings(
        RepositoryName repository,
        Path workDirectory,
        Optional<WebPage> web,
        Optional<List<String>> terminalCommand,
        Optional<String> javaHome) {

    public Path configurationFile() {
        return workDirectory.resolve("worker.yaml");
    }

    public Path gatekeeperDirectory() {
        return workDirectory.resolve("gatekeeper.git");
    }
}
