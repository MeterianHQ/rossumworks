package com.meterian.factory.state;

public enum IssueStatus {
    QUEUED("Queued", "queued", "bfd4f2"),
    IN_PROGRESS("In progress", "in-progress", "0e8a16"),
    IN_REVIEW("In review", "in-review", "5319e7"),
    WAITS_FOR_YOU("Waits for you", "waits-for-you", "fbca04"),
    BLOCKED("Blocked", "blocked", "d93f0b"),
    DONE("Done", "done", "6e7781");

    private static final String LABEL_PREFIX = "factory:status:";

    private final String displayName;
    private final String slug;
    private final String labelColor;

    IssueStatus(String displayName, String slug, String labelColor) {
        this.displayName = displayName;
        this.slug = slug;
        this.labelColor = labelColor;
    }

    public String displayName() {
        return displayName;
    }

    public String labelName() {
        return LABEL_PREFIX + slug;
    }

    public String labelColor() {
        return labelColor;
    }

    public String labelDescription() {
        return "Factory status: " + displayName + ". The factory sets this label.";
    }

    /** Tells if the label name is the name of any status label. */
    public static boolean isStatusLabel(String labelName) {
        return labelName.startsWith(LABEL_PREFIX);
    }
}
