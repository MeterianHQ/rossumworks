package com.meterian.factory.setup;

import java.util.function.Predicate;

/** What the command knows about the newest commit of the default branch. */
public record RepositoryFacts(String defaultBranch, String baseCommit, boolean hasFactory, boolean hasFactoryFolder,
                              Predicate<String> hasFile) {

    /** A folder .factory without factory.yaml is not a definition that the worker can load. */
    public void verifyFactoryFolderIsComplete() throws SetupException {
        if (hasFactoryFolder && !hasFactory) {
            throw new SetupException("The default branch has a folder .factory without factory.yaml. "
                    + "Correct the folder, and run create again.");
        }
    }
}
