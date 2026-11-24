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
        Optional<String> javaHome,
        ProviderChoice provider) {

    /** The subscription is the default provider. */
    public WorkerSettings(RepositoryName repository, Path workDirectory, Optional<WebPage> web,
                          Optional<List<String>> terminalCommand, Optional<String> javaHome) {
        this(repository, workDirectory, web, terminalCommand, javaHome, ProviderChoice.subscription());
    }

    /** The work folder that create proposes and that run finds without --config. */
    public static Path defaultWorkDirectory(Path home, RepositoryName repository) {
        return home.resolve(".rossumworks").resolve(repository.owner() + "-" + repository.name());
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
