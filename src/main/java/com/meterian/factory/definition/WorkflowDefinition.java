package com.meterian.factory.definition;

import com.meterian.factory.state.ActionType;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** The state machine of the workflow. The map of the states keeps the order of the YAML. */
public record WorkflowDefinition(String start, OnAnyState onAnyState, Map<String, StateDefinition> states) {

    public StateDefinition state(String name) {
        StateDefinition state = states.get(name);
        if (state == null) {
            throw new IllegalArgumentException("The workflow has no state " + name + ".");
        }
        return state;
    }

    /** The first state with the action type, in the order of the YAML. */
    public Optional<StateDefinition> findStateWithAction(ActionType type) {
        return states.values().stream()
                .filter(state -> state.actionType().equals(Optional.of(type)))
                .findFirst();
    }

    public StateDefinition finishState() {
        return findStateWithAction(ActionType.FINISH)
                .orElseThrow(() -> new IllegalStateException("The workflow has no state with the action finish."));
    }

    /** The distinct columns, in the order of their first use in the YAML. */
    public List<String> columns() {
        return states.values().stream().map(StateDefinition::column).distinct().toList();
    }
}
