package com.meterian.factory.definition;

import com.meterian.factory.state.IssueRecord;

/** Finds the workflow definition that an issue follows. */
@FunctionalInterface
public interface DefinitionLookup {

    FactoryDefinition definitionOf(IssueRecord record);

    /** The state of the definition where the issue is now. */
    default StateDefinition currentStateOf(IssueRecord record) {
        return definitionOf(record).workflow().state(record.state());
    }

    static DefinitionLookup builtInOnly(FactoryDefinition builtIn) {
        return record -> builtIn;
    }
}
