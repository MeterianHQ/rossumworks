package com.meterian.factory.ownership;

/** Another account owns the issue, so the step must stop before it changes GitHub. */
public final class OwnershipLostException extends RuntimeException {

    private final int issueNumber;

    public OwnershipLostException(int issueNumber) {
        super("Another account owns the issue #" + issueNumber + ".");
        this.issueNumber = issueNumber;
    }

    public int issueNumber() {
        return issueNumber;
    }
}
