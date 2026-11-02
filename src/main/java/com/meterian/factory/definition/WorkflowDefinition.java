package com.meterian.factory.definition;

import com.meterian.factory.state.StepType;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public record WorkflowDefinition(String start, Map<String, StateDefinition> states,
        Map<String, LoopDefinition> loops) {

    public StateDefinition state(String name) {
        StateDefinition state = states.get(name);
        if (state == null) {
            throw new IllegalArgumentException("The workflow has no state " + name + ".");
        }
        return state;
    }

    /** Returns the one state with the step type. It refuses if there are none or several. */
    public StateDefinition stateWithStep(StepType type) {
        List<StateDefinition> matches = states.values().stream()
                .filter(state -> state.step().type() == type)
                .toList();
        if (matches.size() != 1) {
            throw new IllegalArgumentException("The workflow needs exactly one state with the step type "
                    + type.yamlName() + ", but it has " + matches.size() + ".");
        }
        return matches.get(0);
    }

    public Optional<LoopDefinition> loop(String name) {
        return Optional.ofNullable(loops.get(name));
    }
}
