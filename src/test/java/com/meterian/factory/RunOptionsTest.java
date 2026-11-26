package com.meterian.factory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.meterian.factory.configuration.RepositoryName;
import com.meterian.factory.setup.WorkerFileFinder;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RunOptionsTest {

    @TempDir
    Path temporaryDirectory;

    private static WorkerFileFinder refusingFinder() {
        return new WorkerFileFinder(Path.of("no-home"), () -> {
            throw new AssertionError("The command gives the file, so the finder must not run.");
        });
    }

    @Test
    void readsTheConfigurationFile() throws Main.StartupException {
        RunOptions options = RunOptions.parse(new String[] {"run", "--config", "worker.yaml"});

        assertThat(options.findConfigurationFile(refusingFinder())).isEqualTo(Path.of("worker.yaml"));
        assertThat(options.definitionFolder()).isEmpty();
    }

    @Test
    void findsTheConfigurationOfTheCloneWithoutTheOptionConfig() throws Exception {
        Path workerFile = Files.createDirectories(temporaryDirectory.resolve(".rossumworks/owner-project"))
                .resolve("worker.yaml");
        Files.writeString(workerFile, "repository: owner/project\n");
        WorkerFileFinder finder = new WorkerFileFinder(temporaryDirectory,
                () -> Optional.of(new RepositoryName("owner", "project")));

        RunOptions options = RunOptions.parse(new String[] {"run"});

        assertThat(options.findConfigurationFile(finder)).isEqualTo(workerFile);
    }

    @Test
    void readsTheDefinitionFolderInEachOrder() throws Main.StartupException {
        RunOptions first = RunOptions.parse(new String[] {"run", "--definition", "my-factory"});
        RunOptions second = RunOptions.parse(
                new String[] {"run", "--definition", "my-factory", "--config", "worker.yaml"});
        RunOptions third = RunOptions.parse(
                new String[] {"run", "--config", "worker.yaml", "--definition", "my-factory"});

        assertThat(first.definitionFolder()).contains(Path.of("my-factory"));
        assertThat(second.definitionFolder()).contains(Path.of("my-factory"));
        assertThat(third.configurationFile()).contains(Path.of("worker.yaml"));
    }

    @Test
    void refusesOtherArguments() {
        for (String[] arguments : List.of(new String[] {}, new String[] {"start", "--config", "worker.yaml"},
                new String[] {"run", "--config"}, new String[] {"run", "--definition"},
                new String[] {"run", "--file", "worker.yaml"}, new String[] {"run", "worker.yaml"},
                new String[] {"run", "--config", "a.yaml", "--config", "b.yaml"},
                new String[] {"run", "--definition", "a", "--definition", "b"})) {
            assertThatThrownBy(() -> RunOptions.parse(arguments)).hasMessage(RunOptions.USAGE);
        }
    }

    @Test
    void givesTheAbsolutePathOfAFolderThatExists() throws Exception {
        Path folder = Files.createDirectories(temporaryDirectory.resolve("definition"));

        RunOptions options = RunOptions.parse(new String[] {"run", "--definition", folder.toString()});

        assertThat(options.verifyDefinitionFolder()).contains(folder.toAbsolutePath());
    }

    @Test
    void refusesAMissingDefinitionFolder() throws Main.StartupException {
        Path missing = temporaryDirectory.resolve("missing");
        RunOptions options = RunOptions.parse(new String[] {"run", "--definition", missing.toString()});

        assertThatThrownBy(options::verifyDefinitionFolder)
                .hasMessage("The definition folder " + missing.toAbsolutePath() + " does not exist.");
    }

    @Test
    void refusesADefinitionFolderThatIsAFile() throws Exception {
        Path file = Files.writeString(temporaryDirectory.resolve("file"), "text");
        RunOptions options = RunOptions.parse(new String[] {"run", "--definition", file.toString()});

        assertThatThrownBy(options::verifyDefinitionFolder)
                .hasMessage("The definition folder " + file.toAbsolutePath() + " is not a folder.");
    }

    @Test
    void givesNoFolderWithoutTheOption() throws Main.StartupException, IOException {
        assertThat(RunOptions.parse(new String[] {"run"}).verifyDefinitionFolder()).isEmpty();
    }
}
