package com.meterian.factory.setup;

import com.meterian.factory.definition.DefinitionException;
import com.meterian.factory.definition.DefinitionLoader;
import java.util.Map;

/** The check of the generated .factory: the worker must load it after the merge of the PR. */
public final class GeneratedFactoryFolder {

    private GeneratedFactoryFolder() {
    }

    /** Loads the files as the worker does, and stops when they do not load. */
    public static void verifyLoads(Map<String, String> files) throws SetupException {
        try {
            DefinitionLoader.load(new MapDefinitionFiles(files));
        } catch (DefinitionException exception) {
            throw new SetupException("The generated .factory does not load: " + exception.getMessage());
        }
    }
}
