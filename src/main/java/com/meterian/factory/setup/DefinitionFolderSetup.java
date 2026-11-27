package com.meterian.factory.setup;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.stream.Stream;

/** The definition folder of create --definition: the checks before the summary, and the write after it. */
public final class DefinitionFolderSetup {

    private static final String MAIN_FILE = "factory.yaml";

    private final Path folder;

    public DefinitionFolderSetup(Path folder) {
        this.folder = folder;
    }

    public Path folder() {
        return folder;
    }

    /**
     * A hidden entry, which has a name that starts with a dot, does not count.
     *
     * @return true when the folder is missing or empty, false when it has factory.yaml
     * @throws SetupException when the path is a file, or when the folder has other files but no factory.yaml
     */
    public boolean needsDefinition() throws SetupException {
        if (!Files.exists(folder)) {
            return true;
        }
        if (!Files.isDirectory(folder)) {
            throw new SetupException("The definition folder " + folder + " is not a folder.");
        }
        if (Files.isRegularFile(folder.resolve(MAIN_FILE))) {
            return false;
        }
        if (!hasOnlyHiddenEntries()) {
            throw new SetupException("The definition folder " + folder + " has files, but no factory.yaml. "
                    + "Correct the folder, or give an empty folder, and run create again.");
        }
        return true;
    }

    private boolean hasOnlyHiddenEntries() throws SetupException {
        try (Stream<Path> entries = Files.list(folder)) {
            return entries.allMatch(entry -> entry.getFileName().toString().startsWith("."));
        } catch (IOException exception) {
            throw new SetupException("Cannot read the definition folder " + folder + ": " + exception.getMessage());
        }
    }

    /**
     * Writes each file, and factory.yaml last. A failed write then leaves no factory.yaml, so the next create stops.
     * A file that exists stops the write, so the command never overwrites a file.
     */
    public void write(Map<String, String> files) throws SetupException {
        try {
            for (Map.Entry<String, String> file : files.entrySet()) {
                if (!file.getKey().equals(MAIN_FILE)) {
                    writeFile(file.getKey(), file.getValue());
                }
            }
            if (files.containsKey(MAIN_FILE)) {
                writeFile(MAIN_FILE, files.get(MAIN_FILE));
            }
        } catch (FileAlreadyExistsException exception) {
            throw new SetupException("Cannot write the definition folder " + folder + ": the path "
                    + exception.getFile() + " exists.");
        } catch (IOException exception) {
            throw new SetupException("Cannot write the definition folder " + folder + ": " + exception.getMessage());
        }
    }

    private void writeFile(String relativePath, String text) throws IOException {
        Path target = folder.resolve(relativePath);
        Files.createDirectories(target.getParent());
        Files.writeString(target, text, StandardOpenOption.CREATE_NEW);
    }
}
