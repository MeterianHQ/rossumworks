package com.meterian.factory.definition;

/** A definition that loads, and its version: a commit of the default branch, or a local version. */
public record DefinitionVersion(String identifier, FactoryDefinition definition) {
}
