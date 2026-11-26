package com.meterian.factory.definition;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/** Lists the files of a definition folder. */
public final class DefinitionFolderEntries {

    private DefinitionFolderEntries() {
    }

    /**
     * The folder can be a link. An entry below the folder must be a file or a folder: a link is refused.
     *
     * @return the files of the folder and of its subfolders, sorted by relative path
     * @throws IOException when the folder cannot be read, or when an entry is not a file or a folder
     */
    public static List<DefinitionFolderEntry> list(Path folder) throws IOException {
        Path root = folder.toRealPath();
        try (Stream<Path> paths = Files.walk(root)) {
            List<Path> all = paths.toList();
            for (Path path : all) {
                verifyIsFileOrFolder(folder, root, path);
            }
            return all.stream()
                    .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .map(path -> new DefinitionFolderEntry(describeRelativePath(root, path), path))
                    .sorted(Comparator.comparing(DefinitionFolderEntry::relativePath))
                    .toList();
        }
    }

    private static void verifyIsFileOrFolder(Path folder, Path root, Path path) throws IOException {
        boolean isFile = Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS);
        boolean isFolder = Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS);
        if (!isFile && !isFolder) {
            throw new IOException("The definition folder " + folder + " has the entry "
                    + describeRelativePath(root, path) + ", which is not a file or a folder.");
        }
    }

    private static String describeRelativePath(Path root, Path path) {
        return String.join("/", StreamSupport.stream(root.relativize(path).spliterator(), false)
                .map(Path::toString).toList());
    }
}
