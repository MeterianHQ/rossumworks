package com.meterian.factory.agent;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

/** The live sessions: the owner talks to the session of the agent in charge through Remote Control. */
public interface LiveSessions {

    /**
     * Opens the live session of the issue and returns its Remote Control link.
     *
     * @param allowedTools the allowed tools of the definition of the issue
     */
    String open(int issueNumber, UUID sessionId, Path worktree, List<String> allowedTools)
            throws LiveSessionException;

    boolean isOpen(int issueNumber);

    void close(int issueNumber);

    /** Tells if the owner gave /factory:continue in the live session of the issue. */
    boolean isContinueRequested(int issueNumber);

    /** Opens a desktop terminal window that attaches to the live session of the issue. */
    void openTerminal(int issueNumber) throws LiveSessionException;

    void clearContinueRequest(int issueNumber);

    /** Acts as the hook: the owner asks for the continue from the web page. */
    void requestContinue(int issueNumber);
}
