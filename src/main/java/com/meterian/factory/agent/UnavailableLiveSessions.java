package com.meterian.factory.agent;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

/** The live sessions of API key mode: no session opens, so an issue that needs the owner waits on GitHub. */
public final class UnavailableLiveSessions implements LiveSessions {

    private static final String UNAVAILABLE_MESSAGE =
            "The live mode is not available with the provider anthropic-api-key. Answer on GitHub.";

    @Override
    public String open(int issueNumber, UUID sessionId, Path worktree, List<String> allowedTools)
            throws LiveSessionException {
        throw new LiveSessionException(UNAVAILABLE_MESSAGE);
    }

    @Override
    public boolean isOpen(int issueNumber) {
        return false;
    }

    @Override
    public void close(int issueNumber) {
    }

    @Override
    public boolean isContinueRequested(int issueNumber) {
        return false;
    }

    @Override
    public void openTerminal(int issueNumber) throws LiveSessionException {
        throw new LiveSessionException(UNAVAILABLE_MESSAGE);
    }

    @Override
    public void clearContinueRequest(int issueNumber) {
    }

    @Override
    public void requestContinue(int issueNumber) {
    }
}
