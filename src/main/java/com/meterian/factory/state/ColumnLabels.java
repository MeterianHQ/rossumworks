package com.meterian.factory.state;

import com.meterian.factory.definition.ColumnNames;
import java.util.Map;

/** The GitHub label of each column of the board. The label is for display only: the record keeps the state. */
public final class ColumnLabels {

    private static final String LABEL_PREFIX = "factory:is:";
    /** The prefix of the labels of the earlier versions. The factory removes these labels. */
    private static final String OLD_LABEL_PREFIX = "factory:status:";
    private static final String OTHER_COLOR = "c5def5";
    private static final Map<String, String> COLORS = Map.of(
            "queued", "bfd4f2",
            "in-progress", "0e8a16",
            "in-review", "5319e7",
            "waits-for-you", "fbca04",
            "blocked", "d93f0b",
            "done", "6e7781");

    private ColumnLabels() {
    }

    public static String labelName(String column) {
        return LABEL_PREFIX + column;
    }

    /** Tells if the label name is the name of the label of any column, also of an earlier version. */
    public static boolean isColumnLabel(String label) {
        return label.startsWith(LABEL_PREFIX) || label.startsWith(OLD_LABEL_PREFIX);
    }

    public static String colorOf(String column) {
        return COLORS.getOrDefault(column, OTHER_COLOR);
    }

    public static String describe(String column) {
        return "Factory column: " + ColumnNames.displayName(column) + ". The factory sets this label.";
    }
}
