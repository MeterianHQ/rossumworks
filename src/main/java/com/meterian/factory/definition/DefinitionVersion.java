package com.meterian.factory.definition;

/** A definition that loads, and the commit of the default branch that holds it. */
public record DefinitionVersion(String commit, FactoryDefinition definition) {
}
