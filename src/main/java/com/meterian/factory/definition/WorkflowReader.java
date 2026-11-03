package com.meterian.factory.definition;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Reads the section workflow: the start state, the states, and the loops. */
final class WorkflowReader {

    private static final Set<String> WORKFLOW_KEYS = Set.of("start", "states", "loops");
    private static final Set<String> LOOP_KEYS = Set.of("to", "limit", "exit");
    private static final int SUPPORTED_LOOP_COUNT = 1;

    private final DefinitionFiles files;
    private final Map<String, String> promptTexts;
    private final Map<String, Integer> limits;

    WorkflowReader(DefinitionFiles files, Map<String, String> promptTexts, Map<String, Integer> limits) {
        this.files = files;
        this.promptTexts = promptTexts;
        this.limits = limits;
    }

    WorkflowDefinition read(DefinitionNode section) throws DefinitionException {
        section.requireOnlyKeys(WORKFLOW_KEYS);
        Map<String, DefinitionNode> loopNodes = section.findChildren("loops");
        if (loopNodes.size() > SUPPORTED_LOOP_COUNT) {
            throw DefinitionNode.createFailure("version 1 supports one loop");
        }
        TransitionReader transitionReader = new TransitionReader(loopNodes.keySet());
        Map<String, LoopDefinition> loops = readLoops(loopNodes, transitionReader);
        Map<String, StateDefinition> states = readStates(section, transitionReader);
        return new WorkflowDefinition(section.requireText("start"), states, loops);
    }

    private Map<String, StateDefinition> readStates(DefinitionNode section, TransitionReader transitionReader)
            throws DefinitionException {
        StateReader stateReader = new StateReader(new StepReader(files, promptTexts), transitionReader);
        Map<String, StateDefinition> states = new LinkedHashMap<>();
        for (Map.Entry<String, DefinitionNode> entry : section.findChildren("states").entrySet()) {
            states.put(entry.getKey(), stateReader.read(entry.getKey(), entry.getValue()));
        }
        if (states.isEmpty()) {
            throw DefinitionNode.createFailure("the workflow has no states");
        }
        return states;
    }

    private Map<String, LoopDefinition> readLoops(Map<String, DefinitionNode> loopNodes,
            TransitionReader transitionReader) throws DefinitionException {
        Map<String, LoopDefinition> loops = new LinkedHashMap<>();
        for (Map.Entry<String, DefinitionNode> entry : loopNodes.entrySet()) {
            loops.put(entry.getKey(), readLoop(entry.getKey(), entry.getValue(), transitionReader));
        }
        return loops;
    }

    private LoopDefinition readLoop(String name, DefinitionNode loop, TransitionReader transitionReader)
            throws DefinitionException {
        loop.requireOnlyKeys(LOOP_KEYS);
        String limitName = loop.findText("limit")
                .orElseThrow(() -> DefinitionNode.createFailure("loop " + name + " has no limit"));
        Integer limit = limits.get(limitName);
        if (limit == null) {
            throw DefinitionNode.createFailure(
                    "loop " + name + " names the limit " + limitName + ", which is not in limits");
        }
        Transition exit = transitionReader.read(loop.requireValue("exit"), "loop " + name + " exit");
        return new LoopDefinition(name, loop.requireText("to"), limit, exit);
    }
}
