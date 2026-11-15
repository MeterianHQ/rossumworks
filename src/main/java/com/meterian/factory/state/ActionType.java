package com.meterian.factory.state;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Arrays;
import java.util.Optional;
import java.util.Set;

/** A kind of action that the worker can run, with the events that the action can give. */
public enum ActionType {
    PREPARE("prepare", Set.of("done")),
    CODING("coding", Set.of("changed", "no-change", "question", "conflict-left", "failed")),
    BUILD("build", Set.of("passed", "failed")),
    REVIEWS("reviews", Set.of("approved", "rejected")),
    RISK("risk", Set.of("assessed")),
    MERGE_GATE("merge-gate", Set.of("merged", "needs-approval", "needs-new-reviews", "conflict",
            "factory-changed")),
    APPROVAL("approval", Set.of("approved")),
    CONVERSATION("conversation", Set.of("continue", "changed")),
    FINISH("finish", Set.of());

    /** The events of a state without an action. */
    public static final Set<String> EVENTS_WITHOUT_ACTION = Set.of("continue");

    private final String yamlName;
    private final Set<String> events;

    ActionType(String yamlName, Set<String> events) {
        this.yamlName = yamlName;
        this.events = events;
    }

    @JsonValue
    public String yamlName() {
        return yamlName;
    }

    public Set<String> events() {
        return events;
    }

    /** True when the action waits for the owner, so it needs no limit in a cycle. */
    public boolean waitsForYou() {
        return this == APPROVAL || this == CONVERSATION;
    }

    /** True when the action reads the issue text: the text change check applies before it starts. */
    public boolean usesIssueText() {
        return this == CODING || this == REVIEWS || this == RISK;
    }

    /** True when more than one state of a workflow can have the action. */
    public boolean allowsSeveralStates() {
        return this == APPROVAL;
    }

    /**
     * True for the one event of the action that carries findings: build failed, reviews rejected, merge-gate
     * conflict, and coding conflict-left. A coding state that the event enters uses the prompt fix.
     */
    public boolean carriesFindings(String event) {
        return switch (this) {
            case BUILD -> event.equals("failed");
            case REVIEWS -> event.equals("rejected");
            case MERGE_GATE -> event.equals("conflict");
            case CODING -> event.equals("conflict-left");
            case PREPARE, RISK, APPROVAL, CONVERSATION, FINISH -> false;
        };
    }

    public boolean needsWorktree() {
        return switch (this) {
            case CODING, BUILD, REVIEWS, RISK, MERGE_GATE, CONVERSATION -> true;
            case PREPARE, APPROVAL, FINISH -> false;
        };
    }

    @JsonCreator
    public static ActionType readYamlName(String name) {
        return findByYamlName(name).orElseThrow(() -> new IllegalArgumentException("Unknown action type: " + name));
    }

    public static Optional<ActionType> findByYamlName(String name) {
        return Arrays.stream(values()).filter(type -> type.yamlName.equals(name)).findFirst();
    }
}
