package com.meterian.factory.definition;

import com.meterian.factory.state.ActionType;
import java.util.Map;

/**
 * The action of a state.
 *
 * @param promptPaths the path of each prompt, by its name, relative to .factory
 * @param mergeMode the mode of a merge-gate; RISK for each other type
 */
public record ActionDefinition(ActionType type, Map<String, String> promptPaths, MergeMode mergeMode) {
}
