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

    /** The work folder that create proposes and that run finds without --config. */
    public static Path defaultWorkDirectory(Path home, RepositoryName repository) {
        return home.resolve(".claude-ai-factory").resolve(repository.owner() + "-" + repository.name());
    }

    public static Path configurationFileIn(Path workDirectory) {
        return workDirectory.resolve("worker.yaml");
    }

    public Path configurationFile() {
        return configurationFileIn(workDirectory);
    }

    public Path gatekeeperDirectory() {
        return workDirectory.resolve("gatekeeper.git");
    }
}
