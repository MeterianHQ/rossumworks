package com.meterian.factory.state;

public enum IssueStatus {
    QUEUED("Queued"),
    IN_PROGRESS("In progress"),
    IN_REVIEW("In review"),
    WAITS_FOR_YOU("Waits for you"),
    BLOCKED("Blocked"),
    DONE("Done");

    private final String displayName;

    IssueStatus(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }
}
