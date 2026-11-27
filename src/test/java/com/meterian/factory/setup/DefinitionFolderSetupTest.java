package com.meterian.factory.setup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.meterian.factory.definition.DefinitionLoader;
import com.meterian.factory.definition.FolderDefinitionFiles;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DefinitionFolderSetupTest {

    private static final Map<String, String> FILES = Map.of("factory.yaml", "version: 1\n",
            "prompts/implement.md", "Implement.\n");

    @TempDir
    Path temporaryDirectory;

    @Test
    void needsADefinitionForAMissingFolder() throws SetupException {
        DefinitionFolderSetup setup = new DefinitionFolderSetup(temporaryDirectory.resolve("missing"));

        assertThat(setup.needsDefinition()).isTrue();
    }

    @Test
    void needsADefinitionForAnEmptyFolder() throws Exception {
        DefinitionFolderSetup setup = new DefinitionFolderSetup(
                Files.createDirectories(temporaryDirectory.resolve("empty")));

        assertThat(setup.needsDefinition()).isTrue();
    }

    @Test
    void refusesAMainFileThatIsASymbolicLink() throws Exception {
        Path folder = Files.createDirectories(temporaryDirectory.resolve("definition"));
        Path target = Files.writeString(temporaryDirectory.resolve("outside.yaml"), "version: 1\n");
        Files.createSymbolicLink(folder.resolve("factory.yaml"), target);

        assertThatThrownBy(() -> new DefinitionFolderSetup(folder).needsDefinition())
                .isInstanceOf(SetupException.class)
                .hasMessage("The definition folder " + folder + " has files, but no factory.yaml. "
                        + "Correct the folder, or give an empty folder, and run create again.");
    }

    @Test
    void needsNoDefinitionWhenTheFolderHasTheMainFile() throws Exception {
        Path folder = Files.createDirectories(temporaryDirectory.resolve("definition"));
        Files.writeString(folder.resolve("factory.yaml"), "version: 1\n");

        assertThat(new DefinitionFolderSetup(folder).needsDefinition()).isFalse();
    }

    @Test
    void refusesAFolderWithOtherFilesAndNoMainFile() throws Exception {
        Path folder = Files.createDirectories(temporaryDirectory.resolve("definition"));
        Files.writeString(folder.resolve("notes.md"), "notes");

        assertThatThrownBy(() -> new DefinitionFolderSetup(folder).needsDefinition())
                .isInstanceOf(SetupException.class)
                .hasMessage("The definition folder " + folder + " has files, but no factory.yaml. "
                        + "Correct the folder, or give an empty folder, and run create again.");
    }

    @Test
    void refusesAPathThatIsAFile() throws Exception {
        Path file = Files.writeString(temporaryDirectory.resolve("file"), "text");

        assertThatThrownBy(() -> new DefinitionFolderSetup(file).needsDefinition())
                .isInstanceOf(SetupException.class)
                .hasMessage("The definition folder " + file + " is not a folder.");
    }

    @Test
    void writesTheFilesAndMakesTheFolder() throws Exception {
        Path folder = temporaryDirectory.resolve("new/definition");

        new DefinitionFolderSetup(folder).write(FILES);

        assertThat(folder.resolve("factory.yaml")).hasContent("version: 1");
        assertThat(folder.resolve("prompts/implement.md")).hasContent("Implement.");
    }

    @Test
    void neverOverwritesAFile() throws Exception {
        Path folder = Files.createDirectories(temporaryDirectory.resolve("definition"));
        Files.writeString(folder.resolve("factory.yaml"), "mine");

        assertThatThrownBy(() -> new DefinitionFolderSetup(folder).write(FILES))
                .isInstanceOf(SetupException.class)
                .hasMessageStartingWith("Cannot write the definition folder " + folder);
        assertThat(folder.resolve("factory.yaml")).hasContent("mine");
    }

    @Test
    void namesThePathThatExists() throws Exception {
        Path folder = Files.createDirectories(temporaryDirectory.resolve("definition"));
        Path mainFile = Files.writeString(folder.resolve("factory.yaml"), "mine");

        assertThatThrownBy(() -> new DefinitionFolderSetup(folder).write(Map.of("factory.yaml", "version: 1\n")))
                .isInstanceOf(SetupException.class)
                .hasMessage("Cannot write the definition folder " + folder + ": the path " + mainFile + " exists.");
    }

    @Test
    void writesTheMainFileLastSoThatAFailedWriteLeavesNoMainFile() throws Exception {
        Path folder = Files.createDirectories(temporaryDirectory.resolve("definition"));
        Files.writeString(folder.resolve("prompts"), "a file");
        Map<String, String> files = new LinkedHashMap<>();
        files.put("factory.yaml", "version: 1\n");
        files.put("prompts/implement.md", "Implement.\n");

        assertThatThrownBy(() -> new DefinitionFolderSetup(folder).write(files))
                .isInstanceOf(SetupException.class);
        assertThat(folder.resolve("factory.yaml")).doesNotExist();
    }

    @Test
    void writesTheDefaultDefinitionThatTheWorkerLoads() throws Exception {
        Path folder = temporaryDirectory.resolve("definition");
        new DefinitionFolderSetup(folder).write(DefaultFactoryFolder.definitionFiles(RepositoryBuild.MAVEN, "main"));

        assertThat(DefinitionLoader.load(new FolderDefinitionFiles(folder)).mergeTargetBranch()).isEqualTo("main");
    }

    @Test
    void needsADefinitionWhenTheFolderHasOnlyAHiddenEntry() throws Exception {
        Path folder = Files.createDirectories(temporaryDirectory.resolve("definition"));
        Files.createDirectories(folder.resolve(".git"));
        Files.writeString(folder.resolve(".git/HEAD"), "ref");

        assertThat(new DefinitionFolderSetup(folder).needsDefinition()).isTrue();
    }

    @Test
    void refusesAFolderWithAHiddenEntryAndOtherFilesAndNoMainFile() throws Exception {
        Path folder = Files.createDirectories(temporaryDirectory.resolve("definition"));
        Files.createDirectories(folder.resolve(".git"));
        Files.writeString(folder.resolve("notes.md"), "notes");

        assertThatThrownBy(() -> new DefinitionFolderSetup(folder).needsDefinition())
                .isInstanceOf(SetupException.class)
                .hasMessageStartingWith("The definition folder " + folder + " has files, but no factory.yaml.");
    }

    @Test
    void writesTheFilesIntoAFolderWithAHiddenEntry() throws Exception {
        Path folder = Files.createDirectories(temporaryDirectory.resolve("definition"));
        Files.createDirectories(folder.resolve(".git"));

        new DefinitionFolderSetup(folder).write(FILES);

        assertThat(folder.resolve("factory.yaml")).hasContent("version: 1");
        assertThat(folder.resolve(".git")).isDirectory();
    }
}
