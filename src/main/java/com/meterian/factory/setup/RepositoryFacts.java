package com.meterian.factory.setup;

import java.util.function.Predicate;

/** What the command knows about the newest commit of the default branch. */
public record RepositoryFacts(String defaultBranch, String baseCommit, boolean hasFactory, Predicate<String> hasFile) {
}
