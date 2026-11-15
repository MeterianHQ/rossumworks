package com.meterian.factory.definition;

import com.meterian.factory.state.ActionType;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

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

    /**
     * The first state with the action type on the moves from the start state, in the order of a breadth-first walk.
     * The word previous adds no move.
     */
    public Optional<StateDefinition> findFirstStateFromStart(ActionType type) {
        Set<String> visited = new LinkedHashSet<>(List.of(start));
        Deque<String> pending = new ArrayDeque<>(List.of(start));
        while (!pending.isEmpty()) {
            StateDefinition state = state(pending.remove());
            if (state.actionType().equals(Optional.of(type))) {
                return Optional.of(state);
            }
            for (Target target : state.on().values()) {
                if (target instanceof Target.ToState toState && visited.add(toState.name())) {
                    pending.add(toState.name());
                }
            }
        }
        return Optional.empty();
    }

    /** The state where the event of the merge gate goes. Empty without a merge gate, or for the word previous. */
    public Optional<String> findMergeGateTarget(String event) {
        return findStateWithAction(ActionType.MERGE_GATE)
                .map(mergeGate -> mergeGate.on().get(event))
                .filter(Target.ToState.class::isInstance)
                .map(target -> ((Target.ToState) target).name());
    }

    /** The event of the merge gate that goes to the state. Empty when no event of the merge gate goes there. */
    public Optional<String> findMergeGateEventTo(String stateName) {
        return findStateWithAction(ActionType.MERGE_GATE).flatMap(mergeGate -> mergeGate.on().entrySet().stream()
                .filter(move -> move.getValue().equals(new Target.ToState(stateName)))
                .map(Map.Entry::getKey)
                .findFirst());
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
