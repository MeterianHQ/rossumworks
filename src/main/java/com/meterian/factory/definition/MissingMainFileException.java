package com.meterian.factory.definition;

/** The commit has no file .factory/factory.yaml. */
public final class MissingMainFileException extends DefinitionException {

    public MissingMainFileException() {
        super(".factory/" + DefinitionNode.MAIN_FILE + " does not exist");
    }
}
