package com.meterian.factory.definition;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DefinitionFolderEntriesTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void listsTheFilesSortedByRelativePathWithSlashes() throws IOException {
        Path folder = Files.createDirectories(temporaryDirectory.resolve("definition/prompts"))
                .getParent();
        Files.writeString(folder.resolve("prompts/b.md"), "B");
        Files.writeString(folder.resolve("prompts/a.md"), "A");
        Files.writeString(folder.resolve("factory.yaml"), "F");

        List<DefinitionFolderEntry> entries = DefinitionFolderEntries.list(folder);

        assertThat(entries).extracting(DefinitionFolderEntry::relativePath)
                .containsExactly("factory.yaml", "prompts/a.md", "prompts/b.md");
        assertThat(entries.get(1).file()).isRegularFile();
    }
}
