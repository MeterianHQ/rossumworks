package com.meterian.factory.definition;

import com.meterian.factory.state.StepType;
import java.util.List;
import java.util.Set;

/** Checks the references inside a workflow: the start state, the required states, and the targets. */
final class DefinitionValidator {

    private static final List<StepType> SINGLE_STATE_TYPES = List.of(StepType.PREPARE, StepType.BUILD, StepType.FINISH);

    private DefinitionValidator() {
    }

    static void validate(WorkflowDefinition workflow) throws DefinitionException {
        if (!workflow.states().containsKey(workflow.start())) {
            throw DefinitionNode.createFailure("start state " + workflow.start() + " is not a state");
        }
        verifySingleStates(workflow);
        verifyTargets(workflow);
    }

    private static void verifySingleStates(WorkflowDefinition workflow) throws DefinitionException {
        for (StepType type : SINGLE_STATE_TYPES) {
            long count = workflow.states().values().stream().filter(state -> state.step().type() == type).count();
            if (count != 1) {
                throw DefinitionNode.createFailure("the workflow needs exactly one state with the step type "
                        + type.yamlName() + ", but it has " + count);
            }
        }
    }

    private static void verifyTargets(WorkflowDefinition workflow) throws DefinitionException {
        Set<String> stateNames = workflow.states().keySet();
        for (StateDefinition state : workflow.states().values()) {
            for (Transition transition : state.transitions().values()) {
                verifyTransition(transition, stateNames, "state " + state.name());
            }
        }
        for (LoopDefinition loop : workflow.loops().values()) {
            if (!stateNames.contains(loop.to())) {
                throw DefinitionNode.createFailure(
                        "loop " + loop.name() + " goes to " + loop.to() + ", which is not a state");
            }
            verifyTransition(loop.exit(), stateNames, "loop " + loop.name() + " exit");
        }
    }

    private static void verifyTransition(Transition transition, Set<String> stateNames, String owner)
            throws DefinitionException {
        if (transition instanceof Transition.GoTo goTo && !stateNames.contains(goTo.state())) {
            throw DefinitionNode.createFailure(
                    owner + " goes to " + goTo.state() + ", which is not a state or a loop");
        }
    }
}
