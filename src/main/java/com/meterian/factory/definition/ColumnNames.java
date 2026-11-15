package com.meterian.factory.definition;

import java.util.regex.Pattern;

/** The rules of the column names of the board. */
public final class ColumnNames {

    /** The first column, for new issues. No state can use it. */
    public static final String QUEUED = "queued";

    /** A GitHub label has 50 characters or less, and the label of a column is factory:is: and the column. */
    public static final int MAXIMUM_LENGTH = 39;

    private static final Pattern NAME = Pattern.compile("[a-z0-9]+(-[a-z0-9]+)*");

    private ColumnNames() {
    }

    /** The name that the page shows, for example "Waits for you" for waits-for-you. */
    public static String displayName(String column) {
        String words = column.replace('-', ' ');
        return Character.toUpperCase(words.charAt(0)) + words.substring(1);
    }

    public static boolean isValid(String column) {
        return NAME.matcher(column).matches();
    }

    public static boolean fitsInALabel(String column) {
        return column.length() <= MAXIMUM_LENGTH;
    }
}
