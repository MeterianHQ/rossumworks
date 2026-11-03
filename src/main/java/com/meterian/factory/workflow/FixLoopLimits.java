package com.meterian.factory.workflow;

import com.meterian.factory.definition.DefinitionLookup;
import com.meterian.factory.definition.LoopDefinition;
import com.meterian.factory.state.IssueRecord;

/** The fix-loop limit that the status comment and the card show: the loop limit of the definition of the record. */
public final class FixLoopLimits {

    private final DefinitionLookup definitions;

    public FixLoopLimits(DefinitionLookup definitions) {
        this.definitions = definitions;
    }

    /**
     * The record counts the entries of all loops, so the highest loop limit is the limit to show. A workflow
     * without a loop shows 0. A definition that does not load also shows 0: the note of the record tells why.
     */
    public int limitOf(IssueRecord record) {
        try {
            return definitions.definitionOf(record).workflow().loops().values().stream()
                    .mapToInt(LoopDefinition::limit)
                    .max()
                    .orElse(0);
        } catch (IllegalStateException exception) {
            return 0;
        }
    }
}
