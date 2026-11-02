package com.meterian.factory.agent;

import java.nio.file.Path;
import java.util.UUID;

/** The live sessions: the owner talks to the session of the agent in charge through Remote Control. */
public interface LiveSessions {

    /** Opens the live session of the issue and returns its Remote Control link. */
    String open(int issueNumber, UUID sessionId, Path worktree) throws LiveSessionException;

    boolean isOpen(int issueNumber);

    void close(int issueNumber);

    /** Tells if the owner gave /factory:continue in the live session of the issue. */
    boolean isContinueRequested(int issueNumber);

    void clearContinueRequest(int issueNumber);

    /** Acts as the hook: the owner asks for the continue from the web page. */
    void requestContinue(int issueNumber);
}
