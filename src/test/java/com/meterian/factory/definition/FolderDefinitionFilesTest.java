package com.meterian.factory.definition;

import static com.meterian.factory.testing.DefinitionCommits.builtInMainFile;
import static com.meterian.factory.testing.DefinitionFolders.writeDefinitionFolder;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FolderDefinitionFilesTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void loadsTheDefinitionOfTheFolder() throws DefinitionException {
        Path folder = writeDefinitionFolder(temporaryDirectory.resolve("definition"), builtInMainFile());

        FactoryDefinition definition = DefinitionLoader.load(new FolderDefinitionFiles(folder));

        assertThat(definition.workflow().start()).isEqualTo(BuiltInDefinition.load().workflow().start());
    }

    @Test
    void findsNoMissingFile() throws DefinitionException {
        Path folder = temporaryDirectory.resolve("empty");

        assertThat(new FolderDefinitionFiles(folder).read("factory.yaml")).isEmpty();
    }

    @Test
    void refusesAPathThatLeavesTheFolder() {
        FolderDefinitionFiles files = new FolderDefinitionFiles(temporaryDirectory);

        assertThatThrownBy(() -> files.read("../secret.md")).isInstanceOf(DefinitionException.class);
    }
}
