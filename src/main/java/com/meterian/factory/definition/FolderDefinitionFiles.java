package com.meterian.factory.definition;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Optional;

/** The definition files of a folder on the disk: a definition folder or a definition copy. */
public final class FolderDefinitionFiles implements DefinitionFiles {

    private final Path folder;

    public FolderDefinitionFiles(Path folder) {
        this.folder = folder;
    }

    @Override
    public Optional<String> read(String relativePath) throws DefinitionException {
        DefinitionFiles.verifyPathStaysInFolder(relativePath);
        Path file = folder.resolve(relativePath);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            return Optional.empty();
        }
        try {
            return Optional.of(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException exception) {
            throw new DefinitionException("Cannot read the file " + relativePath + " of the folder " + folder
                    + ": " + exception.getMessage(), exception);
        }
    }
}
