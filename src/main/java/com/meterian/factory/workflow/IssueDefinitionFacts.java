package com.meterian.factory.workflow;

import com.meterian.factory.definition.DefinitionLookup;
import com.meterian.factory.definition.StateDefinition;
import com.meterian.factory.state.IssueRecord;
import com.meterian.factory.state.ActionType;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The facts of the definition of an issue that the status comment and the card show. */
public final class IssueDefinitionFacts {

    private final DefinitionLookup definitions;

    public IssueDefinitionFacts(DefinitionLookup definitions) {
        this.definitions = definitions;
    }

    /**
     * The limit of each state with a limit, by state name, in the order of the workflow. A definition that does
     * not load gives no limit: the note of the record tells why.
     */
    public Map<String, Integer> limitsOf(IssueRecord record) {
        Map<String, Integer> limits = new LinkedHashMap<>();
        try {
            for (StateDefinition state : definitions.definitionOf(record).workflow().states().values()) {
                state.limit().ifPresent(limit -> limits.put(state.name(), limit.entries()));
            }
        } catch (IllegalStateException exception) {
            return Map.of();
        }
        return limits;
    }

    /**
     * The names of the reviews of the state with the action reviews, in the order of the definition. Empty when the
     * workflow has no such state, or when the definition does not load.
     */
    public List<String> reviewNamesOf(IssueRecord record) {
        try {
            return definitions.definitionOf(record).workflow().findStateWithAction(ActionType.REVIEWS)
                    .flatMap(StateDefinition::action)
                    .map(action -> List.copyOf(action.promptPaths().keySet()))
                    .orElse(List.of());
        } catch (IllegalStateException exception) {
            return List.of();
        }
    }

    /** False when the definition does not load. */
    public boolean isInBlockTarget(IssueRecord record) {
        try {
            return record.state().equals(definitions.definitionOf(record).workflow().onAnyState().block());
        } catch (IllegalStateException exception) {
            return false;
        }
    }
}
