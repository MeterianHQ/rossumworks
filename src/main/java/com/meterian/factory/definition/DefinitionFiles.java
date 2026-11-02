package com.meterian.factory.definition;

import java.util.Optional;

/** The files of a definition folder, by their path relative to the folder .factory. */
public interface DefinitionFiles {

    Optional<String> read(String relativePath) throws DefinitionException;
}
