package com.meterian.factory.definition;

/** The next state of an event or of a limit: a named state, or the word previous. */
public sealed interface Target {

    String PREVIOUS = "previous";

    record ToState(String name) implements Target {
    }

    /** The last state with an action that does not wait for you. */
    record ToPrevious() implements Target {
    }

    static Target read(String text) {
        return PREVIOUS.equals(text) ? new ToPrevious() : new ToState(text);
    }
}
