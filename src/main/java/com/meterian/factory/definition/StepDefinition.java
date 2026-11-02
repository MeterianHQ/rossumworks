package com.meterian.factory.definition;

import com.meterian.factory.state.ReviewKind;
import com.meterian.factory.state.StepType;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public record StepDefinition(StepType type, Map<String, String> promptPaths) {

    /** The review kinds of a step with the type REVIEWS, in the order of the YAML. */
    public List<ReviewKind> reviewKinds() {
        return promptPaths.keySet().stream()
                .map(key -> ReviewKind.valueOf(key.toUpperCase(Locale.ROOT)))
                .toList();
    }
}
