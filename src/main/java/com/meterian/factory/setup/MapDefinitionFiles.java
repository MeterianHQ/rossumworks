package com.meterian.factory.setup;

import com.meterian.factory.definition.DefinitionException;
import com.meterian.factory.definition.DefinitionFiles;
import java.util.Map;
import java.util.Optional;

/** The definition files of a map whose keys start with a folder prefix, for example .factory/factory.yaml. */
final class MapDefinitionFiles implements DefinitionFiles {

    private static final String REPOSITORY_FOLDER = ".factory/";
    private static final String NO_FOLDER = "";

    private final Map<String, String> files;
    private final String folderPrefix;

    private MapDefinitionFiles(Map<String, String> files, String folderPrefix) {
        this.files = files;
        this.folderPrefix = folderPrefix;
    }

    /** The keys are paths relative to the repository, for example .factory/factory.yaml. */
    static MapDefinitionFiles inRepository(Map<String, String> files) {
        return new MapDefinitionFiles(files, REPOSITORY_FOLDER);
    }

    /** The keys are paths relative to the definition folder, for example factory.yaml. */
    static MapDefinitionFiles inDefinitionFolder(Map<String, String> files) {
        return new MapDefinitionFiles(files, NO_FOLDER);
    }

    @Override
    public Optional<String> read(String relativePath) throws DefinitionException {
        DefinitionFiles.verifyPathStaysInFolder(relativePath);
        return Optional.ofNullable(files.get(folderPrefix + relativePath));
    }
}
