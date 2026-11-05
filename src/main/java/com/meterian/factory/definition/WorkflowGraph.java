package com.meterian.factory.definition;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Checks the rules 8, 9, and 10 of the loader: reachability, a path to finish, and a limit in each cycle. */
final class WorkflowGraph {

    private WorkflowGraph() {
    }

    static void verify(WorkflowDefinition workflow) throws DefinitionException {
        verifyEveryStateIsReachable(workflow);
        verifyEveryStateHasPathToFinish(workflow);
        verifyEveryCycleHasLimit(workflow);
    }

    /** Rule 8. The word previous adds no node: it goes back to a state that the issue visited. */
    private static void verifyEveryStateIsReachable(WorkflowDefinition workflow) throws DefinitionException {
        Set<String> reachable = new HashSet<>();
        Deque<String> pending = new ArrayDeque<>();
        OnAnyState onAnyState = workflow.onAnyState();
        for (String name : List.of(workflow.start(), onAnyState.block(), onAnyState.enter(),
                onAnyState.issueTextChanged())) {
            if (reachable.add(name)) {
                pending.add(name);
            }
        }
        while (!pending.isEmpty()) {
            for (String name : findNamedTargets(workflow.state(pending.remove()))) {
                if (reachable.add(name)) {
                    pending.add(name);
                }
            }
        }
        for (String name : workflow.states().keySet()) {
            if (!reachable.contains(name)) {
                throw DefinitionNode.createFailure("no state goes to the state " + name);
            }
        }
    }

    /** Rule 9. The word previous goes to each state whose action does not wait for you. */
    private static void verifyEveryStateHasPathToFinish(WorkflowDefinition workflow) throws DefinitionException {
        String finish = workflow.finishState().name();
        Set<String> withPath = new HashSet<>(Set.of(finish));
        boolean grew = true;
        while (grew) {
            grew = false;
            for (StateDefinition state : workflow.states().values()) {
                if (!withPath.contains(state.name()) && goesTo(workflow, state, withPath)) {
                    withPath.add(state.name());
                    grew = true;
                }
            }
        }
        for (String name : workflow.states().keySet()) {
            if (!withPath.contains(name)) {
                throw DefinitionNode.createFailure("the state " + name + " has no path to the state " + finish);
            }
        }
    }

    private static boolean goesTo(WorkflowDefinition workflow, StateDefinition state, Set<String> targets) {
        return findTargetStates(workflow, state, collectTargets(state)).stream().anyMatch(targets::contains);
    }

    /**
     * Rule 10. The graph has no state that waits for you, and no move that a limit bounds. A move to a state
     * with a limit still gives the edge to the exhausted node of this state, which goes to the then of the limit.
     */
    private static void verifyEveryCycleHasLimit(WorkflowDefinition workflow) throws DefinitionException {
        Map<CycleNode, Set<CycleNode>> edges = createEdgesWithoutBoundedMoves(workflow);
        List<String> stateNames = new ArrayList<>(workflow.states().keySet());
        List<List<String>> traps = new ArrayList<>();
        for (List<CycleNode> component : StronglyConnectedComponents.find(edges)) {
            if (component.size() > 1 || edges.get(component.get(0)).contains(component.get(0))) {
                traps.add(component.stream().map(CycleNode::stateName).distinct()
                        .sorted((first, second) -> stateNames.indexOf(first) - stateNames.indexOf(second)).toList());
            }
        }
        traps.sort((first, second) -> stateNames.indexOf(first.get(0)) - stateNames.indexOf(second.get(0)));
        if (!traps.isEmpty()) {
            throw DefinitionNode.createFailure("the cycle " + String.join(", ", traps.get(0)) + " has no limit");
        }
    }

    private static Map<CycleNode, Set<CycleNode>> createEdgesWithoutBoundedMoves(WorkflowDefinition workflow) {
        Map<CycleNode, Set<CycleNode>> edges = new LinkedHashMap<>();
        for (StateDefinition state : workflow.states().values()) {
            if (state.waitsForYou()) {
                continue;
            }
            edges.put(new CycleNode(state.name(), false), createMoves(workflow, state.on().values()));
            state.limit().ifPresent(limit -> edges.put(new CycleNode(state.name(), true),
                    createMoves(workflow, List.of(limit.then()))));
        }
        return edges;
    }

    private static Set<CycleNode> createMoves(WorkflowDefinition workflow, Iterable<Target> targets) {
        Set<CycleNode> moves = new LinkedHashSet<>();
        for (Target target : targets) {
            for (String name : findTargetStates(workflow, target)) {
                StateDefinition destination = workflow.state(name);
                if (!destination.waitsForYou()) {
                    moves.add(new CycleNode(name, destination.limit().isPresent()));
                }
            }
        }
        return moves;
    }

    private static Set<String> findNamedTargets(StateDefinition state) {
        Set<String> names = new LinkedHashSet<>();
        for (Target target : collectTargets(state)) {
            if (target instanceof Target.ToState toState) {
                names.add(toState.name());
            }
        }
        return names;
    }

    private static List<Target> collectTargets(StateDefinition state) {
        List<Target> targets = new ArrayList<>(state.on().values());
        state.limit().ifPresent(limit -> targets.add(limit.then()));
        return targets;
    }

    private static Set<String> findTargetStates(WorkflowDefinition workflow, StateDefinition state,
            List<Target> targets) {
        Set<String> names = new LinkedHashSet<>();
        for (Target target : targets) {
            names.addAll(findTargetStates(workflow, target));
        }
        return names;
    }

    private static Set<String> findTargetStates(WorkflowDefinition workflow, Target target) {
        if (target instanceof Target.ToState toState) {
            return Set.of(toState.name());
        }
        Set<String> names = new LinkedHashSet<>();
        for (StateDefinition state : workflow.states().values()) {
            if (state.action().isPresent() && !state.waitsForYou()) {
                names.add(state.name());
            }
        }
        return names;
    }

    /** A state in the graph of rule 10; the exhausted node is the state after it refuses an entry at its limit. */
    private record CycleNode(String stateName, boolean exhausted) {
    }
}
