package com.meterian.factory.agent;

import java.util.List;
import java.util.stream.Collectors;

/** Quotes the words of a command that a POSIX shell reads. */
public final class ShellQuoting {

    private ShellQuoting() {
    }

    /** Puts the value in single quotes. A single quote in the value becomes '\''. */
    public static String quote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    /** Quotes each value and joins them with a space, in one command string. */
    public static String join(List<String> values) {
        return values.stream().map(ShellQuoting::quote).collect(Collectors.joining(" "));
    }
}
