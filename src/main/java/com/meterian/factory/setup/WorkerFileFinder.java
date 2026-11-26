package com.meterian.factory.setup;

import com.meterian.factory.Main.StartupException;
import com.meterian.factory.configuration.RepositoryName;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Supplier;

/** Finds the worker.yaml of the repository of the current folder, in the default work folder. */
public final class WorkerFileFinder {

    private final Path home;
    private final Supplier<Optional<RepositoryName>> currentRepository;

    /**
     * @param home              the folder of the default work folders
     * @param currentRepository the GitHub repository of the current folder, if it is a clone
     */
    public WorkerFileFinder(Path home, Supplier<Optional<RepositoryName>> currentRepository) {
        this.home = home;
        this.currentRepository = currentRepository;
    }

    /** The finder of the current folder of this process. */
    public static WorkerFileFinder ofCurrentFolder(Path home, Path currentFolder) {
        return new WorkerFileFinder(home, () -> OriginRemote.readRepository(currentFolder));
    }

    public Path find() throws StartupException {
        RepositoryName repository = currentRepository.get().orElseThrow(() -> new StartupException(
                "The current folder is not a clone of a GitHub repository. "
                        + "Run the command in a clone of the repository, or give the file with --config <file>."));
        Path workerFile = WorkerSettings.configurationFileIn(WorkerSettings.defaultWorkDirectory(home, repository));
        if (!Files.exists(workerFile)) {
            throw new StartupException("The file " + workerFile + " does not exist. Run "
                    + RunningProgram.findStartCommand() + " create in this folder.");
        }
        return workerFile;
    }
}
