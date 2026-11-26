package com.meterian.factory.definition;

/** The short text of a version, as the board, the notes, and the log show it. */
public final class DefinitionVersionText {

    private static final int SHORT_LENGTH = 7;
    private static final String LOCAL_PREFIX = "local:";

    private DefinitionVersionText() {
    }

    /** The first 7 characters of a commit, or local: and the first 7 characters of the hash. */
    public static String shorten(String version) {
        if (version.startsWith(LOCAL_PREFIX)) {
            return LOCAL_PREFIX + shortenText(version.substring(LOCAL_PREFIX.length()));
        }
        return shortenText(version);
    }

    private static String shortenText(String text) {
        return text.length() <= SHORT_LENGTH ? text : text.substring(0, SHORT_LENGTH);
    }
}
