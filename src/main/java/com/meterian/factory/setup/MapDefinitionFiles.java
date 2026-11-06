package com.meterian.factory.setup;

import com.meterian.factory.definition.DefinitionException;
import com.meterian.factory.definition.DefinitionFiles;
import java.util.Map;
import java.util.Optional;

/** The definition files of a map whose keys are paths relative to the repository, for example .factory/factory.yaml. */
final class MapDefinitionFiles implements DefinitionFiles {

    private static final String FOLDER = ".factory/";

    private final Map<String, String> files;

    MapDefinitionFiles(Map<String, String> files) {
        this.files = files;
    }

    @Override
    public Optional<String> read(String relativePath) throws DefinitionException {
        DefinitionFiles.verifyPathStaysInFolder(relativePath);
        return Optional.ofNullable(files.get(FOLDER + relativePath));
    }
}
