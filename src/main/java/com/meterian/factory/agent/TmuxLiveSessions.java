package com.meterian.factory.agent;

import com.meterian.factory.configuration.ClaudeSettings;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Opens the session of the agent in charge in tmux, with Remote Control.
 * The worker answers the trust prompt on the tmux screen and reads the Remote Control link there.
 */
public final class TmuxLiveSessions implements LiveSessions {

    public static final Duration DEFAULT_OPEN_TIMEOUT = Duration.ofSeconds(90);

    private static final Duration POLL_INTERVAL = Duration.ofMillis(500);
    private static final Pattern LINK = Pattern.compile("https://claude\\.ai/code/session_[A-Za-z0-9]+");
    private static final String TRUST_PROMPT = "trust this folder";
    private static final String SCREEN_WIDTH = "200";
    private static final String SCREEN_HEIGHT = "50";

    private final ClaudeSettings settings;
    private final LiveFiles liveFiles;
    private final Map<String, String> parentEnvironment;
    private final Duration openTimeout;
    private final TmuxCommand tmux;

    public TmuxLiveSessions(ClaudeSettings settings, LiveFiles liveFiles, Map<String, String> parentEnvironment,
            Duration openTimeout) {
        this(settings, liveFiles, parentEnvironment, openTimeout, new TmuxCommand());
    }

    /** Uses the private tmux server with this socket name, for the tests. */
    TmuxLiveSessions(ClaudeSettings settings, LiveFiles liveFiles, Map<String, String> parentEnvironment,
            Duration openTimeout, String socketName) {
        this(settings, liveFiles, parentEnvironment, openTimeout, new TmuxCommand(socketName));
    }

    private TmuxLiveSessions(ClaudeSettings settings, LiveFiles liveFiles, Map<String, String> parentEnvironment,
            Duration openTimeout, TmuxCommand tmux) {
        this.settings = settings;
        this.liveFiles = liveFiles;
        this.parentEnvironment = Map.copyOf(parentEnvironment);
        this.openTimeout = openTimeout;
        this.tmux = tmux;
    }

    @Override
    public String open(int issueNumber, UUID sessionId, Path worktree) throws LiveSessionException {
        clearContinueRequest(issueNumber);
        killSession(issueNumber);
        tmux.run("new-session", "-d", "-s", sessionName(issueNumber), "-x", SCREEN_WIDTH, "-y", SCREEN_HEIGHT,
                "-c", worktree.toString(), createCommandLine(issueNumber, sessionId));
        return waitForTheLink(issueNumber);
    }

    @Override
    public boolean isOpen(int issueNumber) {
        try {
            return hasSession(issueNumber);
        } catch (LiveSessionException exception) {
            throw new IllegalStateException(exception.getMessage(), exception);
        }
    }

    @Override
    public void close(int issueNumber) {
        try {
            killSession(issueNumber);
        } catch (LiveSessionException exception) {
            throw new IllegalStateException(exception.getMessage(), exception);
        }
    }

    @Override
    public boolean isContinueRequested(int issueNumber) {
        return liveFiles.isContinueRequested(issueNumber);
    }

    @Override
    public void clearContinueRequest(int issueNumber) {
        liveFiles.clearContinueRequest(issueNumber);
    }

    private String waitForTheLink(int issueNumber) throws LiveSessionException {
        long deadline = System.nanoTime() + openTimeout.toNanos();
        boolean trustPromptAnswered = false;
        while (true) {
            String screen = captureScreen(issueNumber);
            Matcher link = LINK.matcher(screen);
            if (link.find()) {
                return link.group();
            }
            if (!trustPromptAnswered && screen.contains(TRUST_PROMPT)) {
                answerTheTrustPrompt(issueNumber);
                trustPromptAnswered = true;
            }
            if (System.nanoTime() >= deadline) {
                throw closeAndFail(issueNumber, "The live session did not show a Remote Control link in "
                        + openTimeout.toSeconds() + " seconds.");
            }
            pauseBeforeTheNextPoll(issueNumber);
        }
    }

    private String captureScreen(int issueNumber) throws LiveSessionException {
        Optional<String> screen = Optional.empty();
        if (hasSession(issueNumber)) {
            screen = captureScreenOfAnOpenSession(issueNumber);
        }
        return screen.orElseThrow(() -> closeAndFail(issueNumber, "The live session ended before it showed a link."));
    }

    /** The session can end between the check and the capture: then the screen is empty. */
    private Optional<String> captureScreenOfAnOpenSession(int issueNumber) throws LiveSessionException {
        try {
            return Optional.of(tmux.run("capture-pane", "-p", "-t", paneTarget(issueNumber)));
        } catch (LiveSessionException exception) {
            if (hasSession(issueNumber)) {
                throw exception;
            }
            return Optional.empty();
        }
    }

    /** Claude Code 2.1.316 accepts the trust prompt with Down, then Enter. */
    private void answerTheTrustPrompt(int issueNumber) throws LiveSessionException {
        tmux.run("send-keys", "-t", paneTarget(issueNumber), "Down");
        tmux.run("send-keys", "-t", paneTarget(issueNumber), "Enter");
    }

    private void pauseBeforeTheNextPoll(int issueNumber) throws LiveSessionException {
        try {
            Thread.sleep(POLL_INTERVAL.toMillis());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw closeAndFail(issueNumber, "The open of the live session was interrupted.");
        }
    }

    private LiveSessionException closeAndFail(int issueNumber, String message) {
        LiveSessionException failure = new LiveSessionException(message);
        try {
            killSession(issueNumber);
        } catch (LiveSessionException exception) {
            failure.addSuppressed(exception);
        }
        return failure;
    }

    private boolean hasSession(int issueNumber) throws LiveSessionException {
        return tmux.succeeds("has-session", "-t", sessionTarget(issueNumber));
    }

    /** A kill of a session that does not exist is not an error. */
    private void killSession(int issueNumber) throws LiveSessionException {
        try {
            tmux.run("kill-session", "-t", sessionTarget(issueNumber));
        } catch (LiveSessionException exception) {
            if (hasSession(issueNumber)) {
                throw exception;
            }
        }
    }

    private String createCommandLine(int issueNumber, UUID sessionId) {
        List<String> words = new ArrayList<>();
        words.addAll(List.of("env", "-i"));
        words.addAll(createEnvironmentAssignments());
        words.addAll(createClaudeArguments(issueNumber, sessionId));
        return ShellQuoting.join(words);
    }

    /** The same filter as a phase: no credential variable reaches the live session. */
    private List<String> createEnvironmentAssignments() {
        List<String> assignments = new ArrayList<>();
        ClaudeCommand.environment(parentEnvironment).forEach((name, value) -> assignments.add(name + "=" + value));
        return assignments;
    }

    private List<String> createClaudeArguments(int issueNumber, UUID sessionId) {
        List<String> arguments = new ArrayList<>();
        arguments.add(settings.command());
        arguments.addAll(List.of("--resume", sessionId.toString()));
        arguments.addAll(List.of("--remote-control", "Fix #" + issueNumber));
        arguments.addAll(List.of("--settings", liveFiles.settingsFile(issueNumber).toString()));
        arguments.addAll(List.of("--plugin-dir", liveFiles.pluginDirectory().toString()));
        arguments.add("--allowedTools");
        arguments.addAll(settings.allowedTools());
        arguments.add("--disallowedTools");
        arguments.addAll(settings.disallowedTools());
        return arguments;
    }

    private static String sessionName(int issueNumber) {
        return "factory-issue-" + issueNumber;
    }

    /** The "=" asks tmux for the exact name: without it, "factory-issue-1" also finds "factory-issue-12". */
    private static String sessionTarget(int issueNumber) {
        return "=" + sessionName(issueNumber);
    }

    private static String paneTarget(int issueNumber) {
        return sessionTarget(issueNumber) + ":";
    }
}
