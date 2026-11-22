package com.meterian.factory.events;

/** The destination of the event lines: the file events.jsonl, or a list in a test. */
@FunctionalInterface
public interface EventLines {

    /** Appends one complete JSON line, without its newline. */
    void append(String line);
}
