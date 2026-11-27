package com.meterian.factory.testing;

import com.meterian.factory.definition.BuiltInDefinition;
import com.meterian.factory.definition.ClasspathDefinitionFiles;
import com.meterian.factory.definition.DefinitionException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Writes a definition folder for a test: a main file and the prompts of the built-in definition. */
public final class DefinitionFolders {

    private static final String MAIN_FILE = "factory.yaml";

    private DefinitionFolders() {
    }

    public static Path writeDefinitionFolder(Path folder, String mainFile) {
        try {
            Files.createDirectories(folder);
            Files.writeString(folder.resolve(MAIN_FILE), mainFile);
            for (String promptPath : BuiltInDefinition.load().prompts().keySet()) {
                Path target = folder.resolve(promptPath);
                Files.createDirectories(target.getParent());
                Files.writeString(target, new ClasspathDefinitionFiles().read(promptPath).orElseThrow());
            }
            return folder;
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        } catch (DefinitionException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
