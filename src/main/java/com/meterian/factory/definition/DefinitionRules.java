package com.meterian.factory.definition;

import com.meterian.factory.state.ActionType;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Checks the rules 2 to 7 and 11 of the loader: start, finish, single actions, on-any-state, events, targets. */
final class DefinitionRules {

    private static final String PREPARE_EVENT = "done";
    private static final String CONTINUE_EVENT = "continue";

    private DefinitionRules() {
    }

    static void verify(WorkflowDefinition workflow) throws DefinitionException {
        verifyStart(workflow);
        verifyFinish(workflow);
        verifySingleActions(workflow);
        verifyOnAnyState(workflow);
        for (StateDefinition state : workflow.states().values()) {
            verifyEvents(state);
        }
        for (StateDefinition state : workflow.states().values()) {
            verifyTargets(workflow, state);
        }
        verifyResumePrompt(workflow);
    }

    private static void verifyStart(WorkflowDefinition workflow) throws DefinitionException {
        String start = workflow.start();
        if (!workflow.states().containsKey(start)) {
            throw DefinitionNode.createFailure("the start state " + start + " does not exist");
        }
        Optional<ActionType> startType = workflow.state(start).actionType();
        if (startType.isEmpty()) {
            throw DefinitionNode.createFailure(
                    "the start state " + start + " has no action; it needs the action prepare");
        }
        if (startType.get() != ActionType.PREPARE) {
            throw DefinitionNode.createFailure("the start state " + start + " has the action "
                    + startType.get().yamlName() + "; it needs the action prepare");
        }
    }

    private static void verifyFinish(WorkflowDefinition workflow) throws DefinitionException {
        if (workflow.findStateWithAction(ActionType.FINISH).isEmpty()) {
            throw DefinitionNode.createFailure("no state has the action finish");
        }
    }

    private static void verifySingleActions(WorkflowDefinition workflow) throws DefinitionException {
        for (StateDefinition state : workflow.states().values()) {
            Optional<ActionType> type = state.actionType();
            if (type.isEmpty() || type.get().allowsSeveralStates()) {
                continue;
            }
            StateDefinition first = workflow.findStateWithAction(type.get()).orElseThrow();
            if (!first.name().equals(state.name())) {
                throw DefinitionNode.createFailure("the states " + first.name() + " and " + state.name()
                        + " both have the action " + type.get().yamlName()
                        + "; an action other than approval is in one state at most");
            }
        }
    }

    private static void verifyOnAnyState(WorkflowDefinition workflow) throws DefinitionException {
        OnAnyState onAnyState = workflow.onAnyState();
        verifyOnAnyStateTargetExists(workflow, "block", onAnyState.block());
        verifyOnAnyStateTargetExists(workflow, "enter", onAnyState.enter());
        verifyOnAnyStateTargetExists(workflow, "issue-text-changed", onAnyState.issueTextChanged());
        verifyBlockTargetHasNoAction(workflow, onAnyState.block());
        verifyOnAnyStateAction(workflow, "issue-text-changed", onAnyState.issueTextChanged(), ActionType.APPROVAL);
        verifyOnAnyStateAction(workflow, "enter", onAnyState.enter(), ActionType.CONVERSATION);
    }

    private static void verifyOnAnyStateTargetExists(WorkflowDefinition workflow, String key, String target)
            throws DefinitionException {
        if (!workflow.states().containsKey(target)) {
            throw DefinitionNode.createFailure(
                    "on-any-state " + key + " goes to the state " + target + ", which does not exist");
        }
    }

    private static void verifyBlockTargetHasNoAction(WorkflowDefinition workflow, String target)
            throws DefinitionException {
        Optional<ActionType> type = workflow.state(target).actionType();
        if (type.isPresent()) {
            throw DefinitionNode.createFailure("the block target " + target + " has the action "
                    + type.get().yamlName() + "; the block target must have no action");
        }
    }

    private static void verifyOnAnyStateAction(WorkflowDefinition workflow, String key, String target,
            ActionType requiredType) throws DefinitionException {
        if (!workflow.state(target).actionType().equals(Optional.of(requiredType))) {
            throw DefinitionNode.createFailure("the " + key + " target " + target
                    + " needs the action " + requiredType.yamlName());
        }
    }

    private static void verifyEvents(StateDefinition state) throws DefinitionException {
        Set<String> events = state.events();
        for (String event : state.on().keySet()) {
            if (!events.contains(event)) {
                throw DefinitionNode.createFailure("the state " + state.name() + " has the event " + event
                        + ", but " + describeAction(state) + " has no such event");
            }
        }
        for (String event : events.stream().sorted().toList()) {
            if (!state.on().containsKey(event)) {
                throw DefinitionNode.createFailure(
                        "the state " + state.name() + " has no next state for the event " + event);
            }
        }
    }

    private static String describeAction(StateDefinition state) {
        return state.actionType()
                .map(type -> "the action " + type.yamlName())
                .orElse("a state without an action");
    }

    private static void verifyTargets(WorkflowDefinition workflow, StateDefinition state)
            throws DefinitionException {
        for (Map.Entry<String, Target> entry : state.on().entrySet()) {
            if (entry.getValue() instanceof Target.ToState target && !workflow.states().containsKey(target.name())) {
                throw DefinitionNode.createFailure("the state " + state.name() + " goes to the state "
                        + target.name() + " for the event " + entry.getKey() + ", but " + target.name()
                        + " does not exist");
            }
        }
        Optional<Target> limitTarget = state.limit().map(LimitDefinition::then);
        if (limitTarget.isPresent() && limitTarget.get() instanceof Target.ToState target
                && !workflow.states().containsKey(target.name())) {
            throw DefinitionNode.createFailure("the state " + state.name() + " goes to the state "
                    + target.name() + " at its limit, but " + target.name() + " does not exist");
        }
    }

    /** Rule 11: the coding state needs resume when an entry into it has no findings. */
    private static void verifyResumePrompt(WorkflowDefinition workflow) throws DefinitionException {
        Optional<StateDefinition> codingState = workflow.findStateWithAction(ActionType.CODING);
        if (codingState.isEmpty() || hasResumePrompt(codingState.get())) {
            return;
        }
        for (StateDefinition state : workflow.states().values()) {
            for (Map.Entry<String, Target> entry : state.on().entrySet()) {
                verifyEntryNeedsNoResume(workflow, codingState.get(), state, entry.getKey(), entry.getValue());
            }
            verifyLimitNeedsNoResume(codingState.get(), state);
        }
    }

    /** A limit that sends the issue to the coding state gives an entry without findings. */
    private static void verifyLimitNeedsNoResume(StateDefinition codingState, StateDefinition state)
            throws DefinitionException {
        Optional<Target> limitTarget = state.limit().map(LimitDefinition::then);
        if (limitTarget.isPresent() && limitTarget.get() instanceof Target.ToState target
                && target.name().equals(codingState.name())) {
            throw DefinitionNode.createFailure("the state " + codingState.name() + " needs the prompt resume,"
                    + " because the state " + state.name() + " goes to it at the limit of " + state.name());
        }
    }

    private static boolean hasResumePrompt(StateDefinition codingState) {
        return codingState.action().orElseThrow().promptPaths().containsKey(ActionReader.RESUME_PROMPT);
    }

    private static void verifyEntryNeedsNoResume(WorkflowDefinition workflow, StateDefinition codingState,
            StateDefinition state, String event, Target target) throws DefinitionException {
        if (isExemptFromResume(workflow, state, event)) {
            return;
        }
        if (target instanceof Target.ToState toState && toState.name().equals(codingState.name())) {
            throw createResumeFailure(codingState, state, "goes to it", event);
        }
        // previous is the last state with an action that does not wait for you, so it can be the coding state
        if (target instanceof Target.ToPrevious && !state.waitsForYou()) {
            throw createResumeFailure(codingState, state, "goes to previous", event);
        }
    }

    private static DefinitionException createResumeFailure(StateDefinition codingState, StateDefinition state,
            String move, String event) {
        return DefinitionNode.createFailure("the state " + codingState.name() + " needs the prompt resume,"
                + " because the state " + state.name() + " " + move + " with the event " + event);
    }

    /** The entries that use another prompt: the first entry, an event with findings, or a return with continue. */
    private static boolean isExemptFromResume(WorkflowDefinition workflow, StateDefinition state, String event) {
        Optional<ActionType> type = state.actionType();
        boolean isFirstEntry = type.equals(Optional.of(ActionType.PREPARE)) && event.equals(PREPARE_EVENT);
        boolean hasFindings = type.filter(actionType -> actionType.carriesFindings(event)).isPresent();
        OnAnyState onAnyState = workflow.onAnyState();
        boolean isReturnWithContinue = event.equals(CONTINUE_EVENT)
                && (state.name().equals(onAnyState.block()) || state.name().equals(onAnyState.enter()));
        return isFirstEntry || hasFindings || isReturnWithContinue;
    }
}
