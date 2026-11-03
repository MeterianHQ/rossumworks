package com.meterian.factory.state;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Arrays;
import java.util.Optional;
import java.util.Set;

/** A kind of step that the worker can run, with the outcomes that the step can give. */
public enum StepType {
    PREPARE("prepare", Set.of("done")),
    AGENT("agent", Set.of("changed", "no-change", "question", "conflict-left", "failed")),
    BUILD("build", Set.of("passed", "failed")),
    REVIEWS("reviews", Set.of("approved", "rejected")),
    RISK("risk", Set.of("assessed")),
    MERGE_GATE("merge-gate", Set.of("merged", "needs-approval", "needs-new-reviews", "conflict",
            "factory-changed")),
    FINISH("finish", Set.of());

    private final String yamlName;
    private final Set<String> outcomes;

    StepType(String yamlName, Set<String> outcomes) {
        this.yamlName = yamlName;
        this.outcomes = outcomes;
    }

    @JsonValue
    public String yamlName() {
        return yamlName;
    }

    public Set<String> outcomes() {
        return outcomes;
    }

    @JsonCreator
    public static StepType readYamlName(String name) {
        return findByYamlName(name).orElseThrow(() -> new IllegalArgumentException("Unknown step type: " + name));
    }

    public static Optional<StepType> findByYamlName(String name) {
        return Arrays.stream(values()).filter(type -> type.yamlName.equals(name)).findFirst();
    }
}
