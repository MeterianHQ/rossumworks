package com.meterian.factory.definition;

import java.nio.file.Path;

/** A file of a definition folder: its path relative to the folder, with the separator "/", and the file itself. */
public record DefinitionFolderEntry(String relativePath, Path file) {
}
