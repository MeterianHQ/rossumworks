package com.meterian.factory.definition;

import java.util.Arrays;
import java.util.Optional;

/** How the merge gate decides between an automatic merge and a request for approval. */
public enum MergeMode {
    RISK("risk"),
    AUTO_MERGE("auto-merge"),
    ALWAYS_ASK("always-ask");

    private final String yamlName;

    MergeMode(String yamlName) {
        this.yamlName = yamlName;
    }

    public String yamlName() {
        return yamlName;
    }

    public static Optional<MergeMode> findByYamlName(String name) {
        return Arrays.stream(values()).filter(mode -> mode.yamlName.equals(name)).findFirst();
    }
}
