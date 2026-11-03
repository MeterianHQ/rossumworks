package com.meterian.factory.state;

public enum WaitReason {
    QUESTION("The agent has a question."),
    MERGE_NEEDS_APPROVAL("The merge needs your approval."),
    FACTORY_CHANGE_NEEDS_APPROVAL("The pull request changes the folder .factory."
            + " The merge needs your approval."),
    BLOCKED_BY_OWNER("The owner blocked the issue."),
    FIX_LOOP_LIMIT("The fix loop limit is reached."),
    FAILURE("The factory stopped because of a failure."),
    ISSUE_TEXT_CHANGED("The issue text changed after the label factory."
            + " Add the label factory:approve to accept the new text."),
    LIVE_CONVERSATION("You are in a live conversation with the agent."
            + " Type /factory:continue when you are done.");

    private final String sentence;

    WaitReason(String sentence) {
        this.sentence = sentence;
    }

    public String sentence() {
        return sentence;
    }
}
