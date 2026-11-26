package com.meterian.factory.setup;

import java.io.IOException;
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
        if (!isEmpty()) {
            throw new SetupException("The definition folder " + folder + " has files, but no factory.yaml. "
                    + "Correct the folder, or give an empty folder, and run create again.");
        }
        return true;
    }

    private boolean isEmpty() throws SetupException {
        try (Stream<Path> entries = Files.list(folder)) {
            return entries.findAny().isEmpty();
        } catch (IOException exception) {
            throw new SetupException("Cannot read the definition folder " + folder + ": " + exception.getMessage());
        }
    }

    /** Writes each file. A file that exists stops the write, so the command never overwrites a file. */
    public void write(Map<String, String> files) throws SetupException {
        try {
            for (Map.Entry<String, String> file : files.entrySet()) {
                Path target = folder.resolve(file.getKey());
                Files.createDirectories(target.getParent());
                Files.writeString(target, file.getValue(), StandardOpenOption.CREATE_NEW);
            }
        } catch (IOException exception) {
            throw new SetupException("Cannot write the definition folder " + folder + ": " + exception.getMessage());
        }
    }
}
