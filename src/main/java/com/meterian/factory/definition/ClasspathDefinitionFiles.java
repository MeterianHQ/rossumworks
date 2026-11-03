package com.meterian.factory.definition;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/** The definition folder that the jar contains, in the resource folder factory. */
public final class ClasspathDefinitionFiles implements DefinitionFiles {

    private static final String RESOURCE_FOLDER = "/factory/";

    @Override
    public Optional<String> read(String relativePath) throws DefinitionException {
        DefinitionFiles.verifyPathStaysInFolder(relativePath);
        try (InputStream stream = ClasspathDefinitionFiles.class.getResourceAsStream(RESOURCE_FOLDER + relativePath)) {
            if (stream == null) {
                return Optional.empty();
            }
            return Optional.of(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException exception) {
            throw new DefinitionException("Cannot read the built-in file " + relativePath + ".", exception);
        }
    }
}
