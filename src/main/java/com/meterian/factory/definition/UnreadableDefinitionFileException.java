package com.meterian.factory.definition;

import java.io.IOException;

/** A file of a definition folder cannot be read. The content is not known, so the failure can go away. */
public final class UnreadableDefinitionFileException extends DefinitionException {

    public UnreadableDefinitionFileException(String message, IOException cause) {
        super(message, cause);
    }
}
