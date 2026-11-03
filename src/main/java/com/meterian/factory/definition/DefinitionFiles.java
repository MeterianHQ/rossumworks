package com.meterian.factory.definition;

import java.util.Arrays;
import java.util.Optional;

/** The files of a definition folder, by their path relative to the folder .factory. */
public interface DefinitionFiles {

    Optional<String> read(String relativePath) throws DefinitionException;

    /** Refuses a path with a segment .. or a leading slash: a prompt path must stay in the folder .factory. */
    static void verifyPathStaysInFolder(String relativePath) throws DefinitionException {
        boolean hasParentSegment = Arrays.asList(relativePath.split("/", -1)).contains("..");
        if (hasParentSegment || relativePath.startsWith("/")) {
            throw new DefinitionException("The prompt path " + relativePath + " leaves the folder .factory.");
        }
    }
}
