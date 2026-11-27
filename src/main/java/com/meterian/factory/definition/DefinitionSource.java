package com.meterian.factory.definition;

import java.util.Optional;

/** A source of definition versions. The newest version that loads is the current version. */
public interface DefinitionSource extends DefinitionLookup {

    /** Reads the source again. A new version that loads becomes the current version. */
    void refresh();

    /** The version that a new issue gets. Empty when no version loads. */
    Optional<DefinitionVersion> current();

    /** The reason why the newest version does not load, for the top of the board. */
    Optional<String> warning();
}
