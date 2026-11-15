package com.meterian.factory.definition;

/** The definition that the jar ships: today's workflow and prompts. */
public final class BuiltInDefinition {

    private BuiltInDefinition() {
    }

    public static FactoryDefinition load() {
        try {
            return DefinitionLoader.load(new ClasspathDefinitionFiles());
        } catch (DefinitionException exception) {
            throw new IllegalStateException("The built-in definition is broken: " + exception.getMessage(), exception);
        }
    }
}
