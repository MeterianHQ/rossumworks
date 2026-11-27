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

    /** One line without a final period, so that the reason fits in the sentence of a warning. */
    public static String describeReason(String message) {
        String trimmed = message.replaceAll("\\s*\\R\\s*", " ").strip();
        return trimmed.endsWith(".") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }

    private static String shortenText(String text) {
        return text.length() <= SHORT_LENGTH ? text : text.substring(0, SHORT_LENGTH);
    }
}
