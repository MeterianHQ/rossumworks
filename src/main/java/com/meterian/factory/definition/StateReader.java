package com.meterian.factory.definition;

import com.meterian.factory.state.IssueStatus;
import com.meterian.factory.state.StepType;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Reads a state of the workflow and checks the outcomes of its step. */
final class StateReader {

    private static final Set<String> STATE_KEYS = Set.of("status", "step", "on");

    private final StepReader stepReader;
    private final TransitionReader transitionReader;

    StateReader(StepReader stepReader, TransitionReader transitionReader) {
        this.stepReader = stepReader;
        this.transitionReader = transitionReader;
    }

    StateDefinition read(String name, DefinitionNode state) throws DefinitionException {
        if (!state.isObject()) {
            throw DefinitionNode.createFailure("state " + name + " must be a section");
        }
        state.requireOnlyKeys(STATE_KEYS);
        IssueStatus status = readStatus(name, state.requireText("status"));
        StepDefinition step = stepReader.read(state.requireObject("step"), name);
        Map<String, Transition> transitions = readTransitions(name, state);
        verifyOutcomes(name, step.type(), transitions);
        return new StateDefinition(name, status, step, transitions);
    }

    private Map<String, Transition> readTransitions(String stateName, DefinitionNode state)
            throws DefinitionException {
        Map<String, Transition> transitions = new LinkedHashMap<>();
        for (Map.Entry<String, DefinitionNode> entry : state.findChildren("on").entrySet()) {
            String owner = "state " + stateName + " outcome " + entry.getKey();
            transitions.put(entry.getKey(), transitionReader.read(entry.getValue(), owner));
        }
        return transitions;
    }

    private static IssueStatus readStatus(String stateName, String status) throws DefinitionException {
        return switch (status) {
            case "in-progress" -> IssueStatus.IN_PROGRESS;
            case "in-review" -> IssueStatus.IN_REVIEW;
            case "done" -> IssueStatus.DONE;
            default -> throw DefinitionNode.createFailure("state " + stateName + " has the unknown status " + status);
        };
    }

    private static void verifyOutcomes(String stateName, StepType type, Map<String, Transition> transitions)
            throws DefinitionException {
        if (type == StepType.FINISH && !transitions.isEmpty()) {
            throw DefinitionNode.createFailure(
                    "state " + stateName + " has the step type finish and cannot have transitions");
        }
        for (String outcome : transitions.keySet()) {
            if (!type.outcomes().contains(outcome)) {
                throw DefinitionNode.createFailure("state " + stateName + " has the outcome " + outcome
                        + ", but the step type " + type.yamlName() + " has no such outcome");
            }
        }
        for (String outcome : type.outcomes().stream().sorted().toList()) {
            if (!transitions.containsKey(outcome)) {
                throw DefinitionNode.createFailure(
                        "state " + stateName + " has no transition for the outcome " + outcome);
            }
        }
    }
}
