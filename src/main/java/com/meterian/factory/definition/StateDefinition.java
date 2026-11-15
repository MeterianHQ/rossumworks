package com.meterian.factory.definition;

import com.meterian.factory.state.ActionType;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** A state of the workflow: its column, its action, its limit, and the next state for each event. */
public record StateDefinition(String name, String column, Optional<ActionDefinition> action,
        Optional<LimitDefinition> limit, Map<String, Target> on) {

    public Optional<ActionType> actionType() {
        return action.map(ActionDefinition::type);
    }

    /** True for a state without an action, and for a state with an action that waits for you. */
    public boolean waitsForYou() {
        return actionType().map(ActionType::waitsForYou).orElse(true);
    }

    /** The events that the state can give: the events of its action, or continue without an action. */
    public Set<String> events() {
        return actionType().map(ActionType::events).orElse(ActionType.EVENTS_WITHOUT_ACTION);
    }
}
