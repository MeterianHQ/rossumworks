package com.meterian.factory.definition;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.StreamSupport;

/** Lists the files of a definition folder. */
public final class DefinitionFolderEntries {

    private DefinitionFolderEntries() {
    }

    /**
     * The folder can be a link. An entry below the folder must be a file or a folder: a link is refused.
     * A hidden entry, which has a name that starts with a dot, is skipped with all that it contains.
     *
     * @return the files of the folder and of its subfolders, sorted by relative path
     * @throws IOException when the folder cannot be read, or when an entry is not a file or a folder
     */
    public static List<DefinitionFolderEntry> list(Path folder) throws IOException {
        Path root = folder.toRealPath();
        List<DefinitionFolderEntry> entries = new ArrayList<>();
        Files.walkFileTree(root, new EntryCollector(folder, root, entries));
        entries.sort(Comparator.comparing(DefinitionFolderEntry::relativePath));
        return entries;
    }

    private static boolean isHidden(Path path) {
        return path.getFileName().toString().startsWith(".");
    }

    private static String describeRelativePath(Path root, Path path) {
        return String.join("/", StreamSupport.stream(root.relativize(path).spliterator(), false)
                .map(Path::toString).toList());
    }

    /** Collects the files, skips the hidden entries, and refuses an entry that is not a file or a folder. */
    private static final class EntryCollector extends SimpleFileVisitor<Path> {

        private final Path folder;
        private final Path root;
        private final List<DefinitionFolderEntry> entries;

        private EntryCollector(Path folder, Path root, List<DefinitionFolderEntry> entries) {
            this.folder = folder;
            this.root = root;
            this.entries = entries;
        }

        @Override
        public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
            return isBelowRootAndHidden(directory) ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
            if (isHidden(file)) {
                return FileVisitResult.CONTINUE;
            }
            if (!attributes.isRegularFile()) {
                throw new IOException("The definition folder " + folder + " has the entry "
                        + describeRelativePath(root, file) + ", which is not a file or a folder.");
            }
            entries.add(new DefinitionFolderEntry(describeRelativePath(root, file), file));
            return FileVisitResult.CONTINUE;
        }

        private boolean isBelowRootAndHidden(Path directory) {
            return !directory.equals(root) && isHidden(directory);
        }
    }
}
