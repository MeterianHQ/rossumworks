package com.meterian.factory.definition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    private Path writeFolderWithHiddenEntries() throws IOException {
        Path folder = Files.createDirectories(temporaryDirectory.resolve("definition/prompts"))
                .getParent();
        Files.writeString(folder.resolve("factory.yaml"), "F");
        Files.writeString(folder.resolve("prompts/a.md"), "A");
        Files.writeString(folder.resolve(".hidden.md"), "H");
        Files.writeString(folder.resolve("prompts/.hidden.md"), "H");
        Files.createDirectories(folder.resolve(".git/objects"));
        Files.writeString(folder.resolve(".git/HEAD"), "ref");
        Files.writeString(folder.resolve(".git/objects/pack"), "pack");
        Files.createDirectories(folder.resolve("prompts/.cache"));
        Files.writeString(folder.resolve("prompts/.cache/b.md"), "B");
        return folder;
    }

    @Test
    void skipsTheHiddenEntriesAtEachLevel() throws IOException {
        Path folder = writeFolderWithHiddenEntries();

        List<DefinitionFolderEntry> entries = DefinitionFolderEntries.list(folder);

        assertThat(entries).extracting(DefinitionFolderEntry::relativePath)
                .containsExactly("factory.yaml", "prompts/a.md");
    }

    @Test
    void doesNotCheckTheTypeOfAHiddenEntry() throws IOException {
        Path folder = writeFolderWithHiddenEntries();
        Files.createSymbolicLink(folder.resolve(".git/link"), folder.resolve("factory.yaml"));
        Files.createSymbolicLink(folder.resolve(".something"), folder.resolve("factory.yaml"));

        List<DefinitionFolderEntry> entries = DefinitionFolderEntries.list(folder);

        assertThat(entries).extracting(DefinitionFolderEntry::relativePath)
                .containsExactly("factory.yaml", "prompts/a.md");
    }

    @Test
    void refusesALinkThatIsNotHidden() throws IOException {
        Path folder = writeFolderWithHiddenEntries();
        Files.createSymbolicLink(folder.resolve("link.md"), folder.resolve("factory.yaml"));

        assertThatThrownBy(() -> DefinitionFolderEntries.list(folder))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("link.md");
    }

    @Test
    void acceptsARootFolderWhoseNameStartsWithADot() throws IOException {
        Path folder = Files.createDirectories(temporaryDirectory.resolve(".definition"));
        Files.writeString(folder.resolve("factory.yaml"), "F");

        assertThat(DefinitionFolderEntries.list(folder)).extracting(DefinitionFolderEntry::relativePath)
                .containsExactly("factory.yaml");
    }
}
