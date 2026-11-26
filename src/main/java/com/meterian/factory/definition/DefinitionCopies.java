package com.meterian.factory.definition;

import java.io.IOException;
import java.nio.file.FileSystemException;
import java.nio.file.LinkOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/** The definition copies in <work folder>/definitions: one folder for each version, with the hash as its name. */
public final class DefinitionCopies {

    private static final String COPIES_FOLDER_NAME = "definitions";
    private static final String TEMPORARY_PREFIX = ".partial-";

    private final Path copiesFolder;

    public DefinitionCopies(Path copiesFolder) {
        this.copiesFolder = copiesFolder;
    }

    public static DefinitionCopies inWorkFolder(Path workFolder) {
        return new DefinitionCopies(workFolder.resolve(COPIES_FOLDER_NAME));
    }

    public Path copiesFolder() {
        return copiesFolder;
    }

    /** @throws IllegalArgumentException when the text is not a valid hash, because the hash is a folder name */
    public Path folderOf(String hash) {
        if (!LocalVersion.isValidHash(hash)) {
            throw new IllegalArgumentException("The text " + hash + " is not a valid hash of a definition copy.");
        }
        return copiesFolder.resolve(hash);
    }

    public boolean exists(String hash) {
        return Files.isDirectory(folderOf(hash));
    }

    /** Copies the files of the source to a new temporary folder in the copies folder. */
    public Path copyToTemporaryFolder(Path source) throws IOException {
        Files.createDirectories(copiesFolder);
        Path temporaryFolder = Files.createTempDirectory(copiesFolder, TEMPORARY_PREFIX);
        try {
            for (DefinitionFolderEntry entry : DefinitionFolderEntries.list(source)) {
                Path target = temporaryFolder.resolve(entry.relativePath());
                Files.createDirectories(target.getParent());
                Files.copy(entry.file(), target, LinkOption.NOFOLLOW_LINKS);
            }
        } catch (IOException failure) {
            discard(temporaryFolder);
            throw failure;
        }
        return temporaryFolder;
    }

    /** The rename makes the copy complete in one step. A copy that exists stays, and the temporary folder goes. */
    public void keep(Path temporaryFolder, String hash) throws IOException {
        if (exists(hash)) {
            discard(temporaryFolder);
            return;
        }
        moveToCopyFolder(temporaryFolder, hash);
    }

    /** Another worker can keep the same version after the check of keep: the move then fails, and the copy stays. */
    void moveToCopyFolder(Path temporaryFolder, String hash) throws IOException {
        try {
            Files.move(temporaryFolder, folderOf(hash), StandardCopyOption.ATOMIC_MOVE);
        } catch (FileSystemException exception) {
            if (!exists(hash)) {
                throw exception;
            }
            discard(temporaryFolder);
        }
    }

    public void discard(Path temporaryFolder) throws IOException {
        if (!Files.exists(temporaryFolder)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(temporaryFolder)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }

    /** The hashes of the copies, the newest modification time first. Temporary folders do not count. */
    public List<String> listNewestFirst() throws IOException {
        if (!Files.isDirectory(copiesFolder)) {
            return List.of();
        }
        try (Stream<Path> folders = Files.list(copiesFolder)) {
            return folders.filter(DefinitionCopies::isCopyFolder)
                    .sorted(Comparator.comparing(DefinitionCopies::readModificationTime).reversed())
                    .map(folder -> folder.getFileName().toString())
                    .toList();
        }
    }

    private static boolean isCopyFolder(Path folder) {
        return Files.isDirectory(folder, LinkOption.NOFOLLOW_LINKS)
                && LocalVersion.isValidHash(folder.getFileName().toString());
    }

    private static FileTime readModificationTime(Path folder) {
        try {
            return Files.getLastModifiedTime(folder);
        } catch (IOException exception) {
            return FileTime.fromMillis(0);
        }
    }
}
