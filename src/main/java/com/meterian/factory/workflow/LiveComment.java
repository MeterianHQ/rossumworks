package com.meterian.factory.workflow;

import com.meterian.factory.state.StatusComment;

/** The text of the comment that tells the owner that the live conversation is open. */
public final class LiveComment {

    private LiveComment() {
    }

    public static String render(String link) {
        return "The live conversation is open: " + StatusComment.sanitizeLine(link)
                + "\n\nType /factory:continue in the conversation when you are done.";
    }
}
