package com.meterian.factory.definition;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Reads the section workflow: the start state, the targets of on-any-state, and the states. */
final class WorkflowReader {

    private static final Set<String> WORKFLOW_KEYS = Set.of("start", "on-any-state", "states");
    private static final Set<String> ON_ANY_STATE_KEYS = Set.of("block", "enter", "issue-text-changed");

    private final StateReader stateReader;

    WorkflowReader(ActionReader actionReader) {
        this.stateReader = new StateReader(actionReader);
    }

    WorkflowDefinition read(DefinitionNode section) throws DefinitionException {
        section.requireOnlyKeys(WORKFLOW_KEYS);
        String start = section.requireText("start");
        OnAnyState onAnyState = readOnAnyState(section.requireObject("on-any-state"));
        return new WorkflowDefinition(start, onAnyState, readStates(section));
    }

    private static OnAnyState readOnAnyState(DefinitionNode section) throws DefinitionException {
        section.requireOnlyKeys(ON_ANY_STATE_KEYS);
        return new OnAnyState(
                section.requireText("block"),
                section.requireText("enter"),
                section.requireText("issue-text-changed"));
    }

    private Map<String, StateDefinition> readStates(DefinitionNode section) throws DefinitionException {
        Map<String, StateDefinition> states = new LinkedHashMap<>();
        for (Map.Entry<String, DefinitionNode> entry : section.findChildren("states").entrySet()) {
            verifyStateName(entry.getKey());
            states.put(entry.getKey(), stateReader.read(entry.getKey(), entry.getValue()));
        }
        if (states.isEmpty()) {
            throw DefinitionNode.createFailure("the workflow has no states");
        }
        return states;
    }

    private static void verifyStateName(String name) throws DefinitionException {
        if (Target.PREVIOUS.equals(name)) {
            throw DefinitionNode.createFailure("the state name previous is reserved");
        }
    }
}
