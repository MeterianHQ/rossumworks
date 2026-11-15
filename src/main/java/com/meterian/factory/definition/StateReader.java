package com.meterian.factory.definition;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Reads a state of the workflow: its column, its action, its limit, and its next states. */
final class StateReader {

    private static final Set<String> STATE_KEYS = Set.of("column", "action", "limit", "on");
    private static final Set<String> LIMIT_KEYS = Set.of("entries", "then");
    private static final int MINIMUM_ENTRIES = 1;

    private final ActionReader actionReader;

    StateReader(ActionReader actionReader) {
        this.actionReader = actionReader;
    }

    StateDefinition read(String name, DefinitionNode state) throws DefinitionException {
        if (!state.isObject()) {
            throw DefinitionNode.createFailure("the state " + name + " must be a section");
        }
        refuseUnknownKey(name, state, STATE_KEYS, "");
        String column = readColumn(name, state);
        Optional<ActionDefinition> action = readAction(name, state);
        Optional<LimitDefinition> limit = readLimit(name, state);
        return new StateDefinition(name, column, action, limit, readNextStates(state));
    }

    /** Refuses a key outside the allowed keys. The message names the key relative to the state. */
    static void refuseUnknownKey(String stateName, DefinitionNode section, Set<String> allowedKeys, String keyPrefix)
            throws DefinitionException {
        Optional<String> unknownKey = section.findUnknownKey(allowedKeys);
        if (unknownKey.isPresent()) {
            throw DefinitionNode.createFailure(
                    "the state " + stateName + " has the unknown key " + keyPrefix + unknownKey.get());
        }
    }

    private static String readColumn(String stateName, DefinitionNode state) throws DefinitionException {
        String column = state.findText("column")
                .orElseThrow(() -> DefinitionNode.createFailure("the state " + stateName + " has no column"));
        if (ColumnNames.QUEUED.equals(column)) {
            throw DefinitionNode.createFailure("the state " + stateName
                    + " has the column queued, which the factory keeps for new issues");
        }
        if (!ColumnNames.isValid(column)) {
            throw DefinitionNode.createFailure("the state " + stateName + " has the column " + column
                    + "; a column has only small letters, digits, and hyphens");
        }
        if (!ColumnNames.fitsInALabel(column)) {
            throw DefinitionNode.createFailure("the state " + stateName + " has the column " + column
                    + "; a column has " + ColumnNames.MAXIMUM_LENGTH
                    + " characters or less, because a GitHub label has 50 characters or less");
        }
        return column;
    }

    private Optional<ActionDefinition> readAction(String stateName, DefinitionNode state)
            throws DefinitionException {
        Optional<DefinitionNode> action = state.findObject("action");
        if (action.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(actionReader.read(stateName, action.get()));
    }

    private static Optional<LimitDefinition> readLimit(String stateName, DefinitionNode state)
            throws DefinitionException {
        Optional<DefinitionNode> limit = state.findObject("limit");
        if (limit.isEmpty()) {
            return Optional.empty();
        }
        refuseUnknownKey(stateName, limit.get(), LIMIT_KEYS, "limit.");
        int entries = limit.get().findInteger("entries")
                .filter(value -> value >= MINIMUM_ENTRIES)
                .orElseThrow(() -> limit.get().createInvalidKeyFailure("entries", "must be an integer of 1 or more"));
        return Optional.of(new LimitDefinition(entries, readLimitTarget(stateName, limit.get())));
    }

    /** The previous state of a state at its limit is the state itself, so a limit names a state. */
    private static Target readLimitTarget(String stateName, DefinitionNode limit) throws DefinitionException {
        Target target = Target.read(limit.requireText("then"));
        if (target instanceof Target.ToPrevious) {
            throw DefinitionNode.createFailure("the state " + stateName
                    + " has the limit then previous; the then of a limit must be a state name");
        }
        return target;
    }

    private static Map<String, Target> readNextStates(DefinitionNode state) throws DefinitionException {
        Map<String, Target> nextStates = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : state.findTextMap("on").entrySet()) {
            nextStates.put(entry.getKey(), Target.read(entry.getValue()));
        }
        return nextStates;
    }
}
