package com.meterian.factory.definition;

import com.meterian.factory.state.IssueRecord;
import java.util.List;

/** Finds the workflow definition that an issue follows. */
@FunctionalInterface
public interface DefinitionLookup {

    /** @throws IllegalStateException when the definition of the record does not load */
    FactoryDefinition definitionOf(IssueRecord record);

    /** The state of the definition where the issue is now. */
    default StateDefinition currentStateOf(IssueRecord record) {
        return definitionOf(record).workflow().state(record.state());
    }

    /** The columns of the newest good version, in the order of the workflow. Empty when the lookup has no version. */
    default List<String> findCurrentColumns() {
        return List.of();
    }

    static DefinitionLookup builtInOnly(FactoryDefinition builtIn) {
        return new DefinitionLookup() {
            @Override
            public FactoryDefinition definitionOf(IssueRecord record) {
                return builtIn;
            }

            @Override
            public List<String> findCurrentColumns() {
                return builtIn.workflow().columns();
            }
        };
    }
}
