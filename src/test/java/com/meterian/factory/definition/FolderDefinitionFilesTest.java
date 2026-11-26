package com.meterian.factory.definition;

import static com.meterian.factory.testing.DefinitionCommits.builtInMainFile;
import static com.meterian.factory.testing.DefinitionFolders.writeDefinitionFolder;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
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

    @Test
    void refusesALinkToAnOutsideFolderInTheMiddleOfThePath() throws IOException {
        Path folder = writeDefinitionFolder(temporaryDirectory.resolve("definition"), builtInMainFile());
        Path outside = Files.createDirectories(temporaryDirectory.resolve("outside"));
        Files.writeString(outside.resolve("secret.md"), "Secret.\n");
        Files.createSymbolicLink(folder.resolve("linked"), outside);

        assertThatThrownBy(() -> new FolderDefinitionFiles(folder).read("linked/secret.md"))
                .isInstanceOf(DefinitionException.class)
                .hasMessage("The path linked/secret.md leaves the folder of the definition through a link.");
    }

    @Test
    void refusesAnAbsolutePath() {
        FolderDefinitionFiles files = new FolderDefinitionFiles(temporaryDirectory);

        assertThatThrownBy(() -> files.read("/etc/passwd")).isInstanceOf(DefinitionException.class);
    }

    @Test
    void reportsAFileThatCannotBeRead() throws IOException {
        assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"),
                "The test needs POSIX permissions");
        Path prompt = Files.writeString(temporaryDirectory.resolve("prompt.md"), "Prompt.\n");
        FolderDefinitionFiles files = new FolderDefinitionFiles(temporaryDirectory);
        Files.setPosixFilePermissions(prompt, PosixFilePermissions.fromString("-w-------"));

        try {
            assumeFalse(Files.isReadable(prompt),
                    "The user can read a file without the read permission, for example root");
            assertThatThrownBy(() -> files.read("prompt.md"))
                    .isInstanceOf(UnreadableDefinitionFileException.class)
                    .hasMessageStartingWith("Cannot read the file prompt.md of the folder ")
                    .hasCauseInstanceOf(IOException.class);
        } finally {
            Files.setPosixFilePermissions(prompt, PosixFilePermissions.fromString("rw-------"));
        }
    }

    @Test
    void refusesAFileThatIsNotValidUtf8() throws IOException {
        Files.write(temporaryDirectory.resolve("prompt.md"), new byte[] {(byte) 0xff});
        FolderDefinitionFiles files = new FolderDefinitionFiles(temporaryDirectory);

        assertThatThrownBy(() -> files.read("prompt.md"))
                .isInstanceOf(DefinitionException.class)
                .isNotInstanceOf(UnreadableDefinitionFileException.class)
                .hasMessage("The file prompt.md of the folder " + temporaryDirectory + " is not valid UTF-8.");
    }
}
