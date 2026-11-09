package com.meterian.factory.setup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.meterian.factory.Main.StartupException;
import com.meterian.factory.configuration.RepositoryName;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkerFileFinderTest {

    private static final RepositoryName REPOSITORY = new RepositoryName("owner", "project");

    @TempDir
    Path home;

    @Test
    void findsTheWorkerFileOfTheRepositoryOfTheClone() throws Exception {
        Path workerFile = createWorkerFile();

        Path found = new WorkerFileFinder(home, () -> Optional.of(REPOSITORY)).find();

        assertThat(found).isEqualTo(workerFile);
    }

    @Test
    void refusesAFolderThatIsNotACloneOfAGitHubRepository() {
        WorkerFileFinder finder = new WorkerFileFinder(home, Optional::empty);

        assertThatThrownBy(finder::find).isInstanceOf(StartupException.class)
                .hasMessage("The current folder is not a clone of a GitHub repository. "
                        + "Run the command in a clone of the repository, or give the file with --config <file>.");
    }

    @Test
    void refusesARepositoryWithoutAWorkerFile() {
        WorkerFileFinder finder = new WorkerFileFinder(home, () -> Optional.of(REPOSITORY));
        Path workerFile = home.resolve(".claude-ai-factory/owner-project/worker.yaml");

        assertThatThrownBy(finder::find).isInstanceOf(StartupException.class)
                .hasMessageStartingWith("The file " + workerFile + " does not exist. Run java -jar ")
                .hasMessageEndingWith(" create in this folder.");
    }

    private Path createWorkerFile() throws IOException {
        Path workDirectory = Files.createDirectories(home.resolve(".claude-ai-factory/owner-project"));
        return Files.writeString(workDirectory.resolve("worker.yaml"), "repository: owner/project\n");
    }
}
