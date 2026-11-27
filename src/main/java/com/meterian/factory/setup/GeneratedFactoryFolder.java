package com.meterian.factory.setup;

import com.meterian.factory.definition.DefinitionException;
import com.meterian.factory.definition.DefinitionFiles;
import com.meterian.factory.definition.DefinitionLoader;
import java.util.Map;

/** The check of a generated definition: the worker must load it, from .factory or from a definition folder. */
public final class GeneratedFactoryFolder {

    private GeneratedFactoryFolder() {
    }

    /** Loads the files of .factory as the worker does, and stops when they do not load. */
    public static void verifyLoads(Map<String, String> files) throws SetupException {
        verifyLoads(MapDefinitionFiles.inRepository(files), "The generated .factory does not load: ");
    }

    /** Loads the files of a definition folder as the worker does, and stops when they do not load. */
    public static void verifyDefinitionLoads(Map<String, String> files) throws SetupException {
        verifyLoads(MapDefinitionFiles.inDefinitionFolder(files), "The generated definition does not load: ");
    }

    private static void verifyLoads(DefinitionFiles files, String problem) throws SetupException {
        try {
            DefinitionLoader.load(files);
        } catch (DefinitionException exception) {
            throw new SetupException(problem + exception.getMessage());
        }
    }
}
