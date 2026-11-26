package com.meterian.factory.definition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FolderHashTest {

    @TempDir
    Path temporaryDirectory;

    private Path writeFolder(String name, String mainFile, String prompt) throws IOException {
        Path folder = Files.createDirectories(temporaryDirectory.resolve(name).resolve("prompts"))
                .getParent();
        Files.writeString(folder.resolve("factory.yaml"), mainFile);
        Files.writeString(folder.resolve("prompts/implement.md"), prompt);
        return folder;
    }

    @Test
    void givesTheSameHashForTheSameContent() throws IOException {
        Path first = writeFolder("first", "version: 1\n", "Implement.\n");
        Path second = writeFolder("second", "version: 1\n", "Implement.\n");

        assertThat(FolderHash.calculate(first)).isEqualTo(FolderHash.calculate(second)).hasSize(64);
    }

    @Test
    void givesANewHashForANewContent() throws IOException {
        Path first = writeFolder("first", "version: 1\n", "Implement.\n");
        Path second = writeFolder("second", "version: 1\n", "Implement it.\n");

        assertThat(FolderHash.calculate(first)).isNotEqualTo(FolderHash.calculate(second));
    }

    @Test
    void givesANewHashForANewPath() throws IOException {
        Path first = writeFolder("first", "version: 1\n", "Implement.\n");
        Path second = writeFolder("second", "version: 1\n", "Implement.\n");
        Files.move(second.resolve("prompts/implement.md"), second.resolve("prompts/fix.md"));

        assertThat(FolderHash.calculate(first)).isNotEqualTo(FolderHash.calculate(second));
    }

    @Test
    void givesANewHashForANewEmptyFile() throws IOException {
        Path folder = writeFolder("first", "version: 1\n", "Implement.\n");
        String before = FolderHash.calculate(folder);
        Files.writeString(folder.resolve("empty.md"), "");

        assertThat(FolderHash.calculate(folder)).isNotEqualTo(before);
    }

    @Test
    void givesANewHashWhenTextMovesFromOneFileToTheNext() throws IOException {
        Path first = writeFolder("first", "version: 1\n", "Implement.\n");
        Path second = writeFolder("second", "version: 1\nImp", "lement.\n");

        assertThat(FolderHash.calculate(first)).isNotEqualTo(FolderHash.calculate(second));
    }

    @Test
    void refusesASymbolicLink() throws IOException {
        Path folder = writeFolder("first", "version: 1\n", "Implement.\n");
        Files.createSymbolicLink(folder.resolve("link.md"), folder.resolve("factory.yaml"));

        assertThatThrownBy(() -> FolderHash.calculate(folder))
                .isInstanceOf(IOException.class)
                .hasMessage("The definition folder " + folder + " has the entry link.md, "
                        + "which is not a file or a folder.");
    }

    @Test
    void givesTheHashOfTheRealFolderForALinkToTheFolder() throws IOException {
        Path folder = writeFolder("real", "version: 1\n", "Implement.\n");
        Path link = Files.createSymbolicLink(temporaryDirectory.resolve("link"), folder);

        assertThat(FolderHash.calculate(link)).isEqualTo(FolderHash.calculate(folder));
    }

    @Test
    void refusesALinkToAFolderInTheMiddleOfAPath() throws IOException {
        Path folder = writeFolder("first", "version: 1\n", "Implement.\n");
        Path outside = Files.createDirectories(temporaryDirectory.resolve("outside"));
        Files.writeString(outside.resolve("secret.md"), "Secret.\n");
        Files.createSymbolicLink(folder.resolve("linked"), outside);

        assertThatThrownBy(() -> FolderHash.calculate(folder))
                .isInstanceOf(IOException.class)
                .hasMessage("The definition folder " + folder + " has the entry linked, "
                        + "which is not a file or a folder.");
    }
}
