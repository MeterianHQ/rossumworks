package com.meterian.factory.definition;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DefinitionCopiesTest {

    @TempDir
    Path temporaryDirectory;

    private Path source;
    private DefinitionCopies copies;

    @BeforeEach
    void writeTheSource() throws IOException {
        source = Files.createDirectories(temporaryDirectory.resolve("definition/prompts")).getParent();
        Files.writeString(source.resolve("factory.yaml"), "version: 1\n");
        Files.writeString(source.resolve("prompts/implement.md"), "Implement.\n");
        copies = DefinitionCopies.inWorkFolder(temporaryDirectory.resolve("work"));
    }

    @Test
    void keepsACompleteCopyUnderItsHash() throws IOException {
        Path temporaryCopy = copies.copyToTemporaryFolder(source);
        String hash = FolderHash.calculate(temporaryCopy);

        copies.keep(temporaryCopy, hash);

        assertThat(copies.exists(hash)).isTrue();
        assertThat(FolderHash.calculate(copies.folderOf(hash))).isEqualTo(hash);
        assertThat(temporaryCopy).doesNotExist();
    }

    @Test
    void keepsTheFirstCopyOfAVersion() throws IOException {
        Path firstCopy = copies.copyToTemporaryFolder(source);
        String hash = FolderHash.calculate(firstCopy);
        copies.keep(firstCopy, hash);
        Path secondCopy = copies.copyToTemporaryFolder(source);

        copies.keep(secondCopy, hash);

        assertThat(copies.exists(hash)).isTrue();
        assertThat(secondCopy).doesNotExist();
    }

    @Test
    void listsTheCopiesNewestFirstAndSkipsTheTemporaryFolders() throws IOException {
        Path olderCopy = copies.copyToTemporaryFolder(source);
        String olderHash = FolderHash.calculate(olderCopy);
        copies.keep(olderCopy, olderHash);
        Files.writeString(source.resolve("factory.yaml"), "version: 1\n# new\n");
        Path newerCopy = copies.copyToTemporaryFolder(source);
        String newerHash = FolderHash.calculate(newerCopy);
        copies.keep(newerCopy, newerHash);
        Files.setLastModifiedTime(copies.folderOf(olderHash), FileTime.from(Instant.parse("2026-01-01T00:00:00Z")));
        copies.copyToTemporaryFolder(source);

        assertThat(copies.listNewestFirst()).containsExactly(newerHash, olderHash);
    }

    @Test
    void listsNothingWithoutAFolder() throws IOException {
        assertThat(copies.listNewestFirst()).isEmpty();
    }
}
