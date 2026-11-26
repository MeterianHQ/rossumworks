package com.meterian.factory.definition;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** The definition copies in <work folder>/definitions: one folder for each version, with the hash as its name. */
public final class DefinitionCopies {

    private static final String FOLDER = "definitions";
    private static final String TEMPORARY_PREFIX = ".partial-";
    private static final Pattern HASH = Pattern.compile("[0-9a-f]{64}");

    private final Path directory;

    public DefinitionCopies(Path directory) {
        this.directory = directory;
    }

    public static DefinitionCopies inWorkFolder(Path workDirectory) {
        return new DefinitionCopies(workDirectory.resolve(FOLDER));
    }

    public Path directory() {
        return directory;
    }

    public Path folderOf(String hash) {
        return directory.resolve(hash);
    }

    public boolean exists(String hash) {
        return Files.isDirectory(folderOf(hash));
    }

    /** Copies the files of the source to a new temporary folder in the directory of the copies. */
    public Path copyToTemporaryFolder(Path source) throws IOException {
        Files.createDirectories(directory);
        Path temporaryFolder = Files.createTempDirectory(directory, TEMPORARY_PREFIX);
        try {
            for (Path file : FolderHash.listFiles(source)) {
                Path target = temporaryFolder.resolve(source.relativize(file).toString());
                Files.createDirectories(target.getParent());
                Files.copy(file, target);
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
        try {
            Files.move(temporaryFolder, folderOf(hash), StandardCopyOption.ATOMIC_MOVE);
        } catch (FileAlreadyExistsException exception) {
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
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        try (Stream<Path> folders = Files.list(directory)) {
            return folders.filter(folder -> HASH.matcher(folder.getFileName().toString()).matches())
                    .sorted(Comparator.comparing(DefinitionCopies::readModificationTime).reversed())
                    .map(folder -> folder.getFileName().toString())
                    .toList();
        }
    }

    private static FileTime readModificationTime(Path folder) {
        try {
            return Files.getLastModifiedTime(folder);
        } catch (IOException exception) {
            return FileTime.fromMillis(0);
        }
    }
}
