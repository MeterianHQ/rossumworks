package com.meterian.factory.workflow;

import com.meterian.factory.state.StatusComment;
import java.util.Optional;

/** The text of the comment that tells the owner that the live conversation is open. */
public final class LiveComment {

    private LiveComment() {
    }

    /** The mention notifies the phone of the owner. Without a mention, the text ends after the last line. */
    static String render(String link, Optional<String> mentionedLogin) {
        String text = "The live conversation is open: " + StatusComment.sanitizeLine(link)
                + "\n\nType /factory:continue in the conversation when you are done.";
        return mentionedLogin.map(login -> text + "\n\n@" + login).orElse(text);
    }
}
