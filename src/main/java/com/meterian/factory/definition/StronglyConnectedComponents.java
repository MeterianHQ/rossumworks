package com.meterian.factory.definition;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Finds the strongly connected components of a directed graph with the algorithm of Tarjan. */
final class StronglyConnectedComponents<Node> {

    private final Map<Node, Set<Node>> edges;
    private final Map<Node, Integer> indexes = new HashMap<>();
    private final Map<Node, Integer> lowestIndexes = new HashMap<>();
    private final Deque<Node> openNodes = new ArrayDeque<>();
    private final Set<Node> openNodeSet = new HashSet<>();
    private final List<List<Node>> components = new ArrayList<>();

    private StronglyConnectedComponents(Map<Node, Set<Node>> edges) {
        this.edges = edges;
    }

    /** The edges map each node of the graph to its successors. A successor is also a key of the map. */
    static <Node> List<List<Node>> find(Map<Node, Set<Node>> edges) {
        StronglyConnectedComponents<Node> search = new StronglyConnectedComponents<>(edges);
        for (Node node : edges.keySet()) {
            if (!search.indexes.containsKey(node)) {
                search.visit(node);
            }
        }
        return search.components;
    }

    private void visit(Node node) {
        indexes.put(node, indexes.size());
        lowestIndexes.put(node, indexes.get(node));
        openNodes.push(node);
        openNodeSet.add(node);
        for (Node successor : edges.get(node)) {
            if (!indexes.containsKey(successor)) {
                visit(successor);
                lowestIndexes.put(node, Math.min(lowestIndexes.get(node), lowestIndexes.get(successor)));
            } else if (openNodeSet.contains(successor)) {
                lowestIndexes.put(node, Math.min(lowestIndexes.get(node), indexes.get(successor)));
            }
        }
        if (lowestIndexes.get(node).equals(indexes.get(node))) {
            components.add(popComponent(node));
        }
    }

    private List<Node> popComponent(Node root) {
        List<Node> component = new ArrayList<>();
        Node member;
        do {
            member = openNodes.pop();
            openNodeSet.remove(member);
            component.add(member);
        } while (!member.equals(root));
        return component;
    }
}
