package com.meterian.factory.definition;

/** The maximum number of entries into a state, and the target at the entry after the maximum. */
public record LimitDefinition(int entries, Target then) {
}
