package com.meterian.factory.testing;

import com.meterian.factory.agent.LiveSessionException;
import com.meterian.factory.agent.LiveSessions;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** Live sessions in memory, for the workflow tests. The link of issue N is https://claude.ai/code/session_testN. */
public final class FakeLiveSessions implements LiveSessions {

    /** The arguments of one call of open. */
    public record OpenCall(int issueNumber, UUID sessionId, Path worktree) {
    }

    private final List<OpenCall> openCalls = new CopyOnWriteArrayList<>();
    private final List<Integer> closeCalls = new CopyOnWriteArrayList<>();
    private final Set<Integer> openIssues = ConcurrentHashMap.newKeySet();
    private final Set<Integer> continueRequests = ConcurrentHashMap.newKeySet();
    private volatile boolean failsTheNextOpen;
    private volatile boolean failsTheNextClose;

    @Override
    public String open(int issueNumber, UUID sessionId, Path worktree) throws LiveSessionException {
        openCalls.add(new OpenCall(issueNumber, sessionId, worktree));
        continueRequests.remove(issueNumber);
        if (failsTheNextOpen) {
            failsTheNextOpen = false;
            openIssues.remove(issueNumber);
            throw new LiveSessionException("The live session did not show a Remote Control link in 90 seconds.");
        }
        openIssues.add(issueNumber);
        return "https://claude.ai/code/session_test" + issueNumber;
    }

    @Override
    public boolean isOpen(int issueNumber) {
        return openIssues.contains(issueNumber);
    }

    @Override
    public void close(int issueNumber) {
        closeCalls.add(issueNumber);
        if (failsTheNextClose) {
            failsTheNextClose = false;
            throw new IllegalStateException("tmux cannot close the session of issue " + issueNumber);
        }
        openIssues.remove(issueNumber);
    }

    @Override
    public boolean isContinueRequested(int issueNumber) {
        return continueRequests.contains(issueNumber);
    }

    @Override
    public void clearContinueRequest(int issueNumber) {
        continueRequests.remove(issueNumber);
    }

    public List<OpenCall> openCalls() {
        return List.copyOf(openCalls);
    }

    public List<Integer> closeCalls() {
        return List.copyOf(closeCalls);
    }

    /** Acts as the hook: the owner gives /factory:continue in the live session. */
    public void requestContinue(int issueNumber) {
        continueRequests.add(issueNumber);
    }

    /** Acts as the owner who ends the live session without /factory:continue. */
    public void endSession(int issueNumber) {
        openIssues.remove(issueNumber);
    }

    public void failTheNextOpen() {
        failsTheNextOpen = true;
    }

    public void failTheNextClose() {
        failsTheNextClose = true;
    }
}
