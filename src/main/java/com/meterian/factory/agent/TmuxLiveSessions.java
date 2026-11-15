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
 * Opens the session of the agent in charge in tmux, with Remote Control, on the tmux server of the factory.
 * The worker answers the trust prompt on the tmux screen and reads the Remote Control link there.
 */
public final class TmuxLiveSessions implements LiveSessions {

    public static final Duration DEFAULT_OPEN_TIMEOUT = Duration.ofSeconds(90);

    private static final Duration POLL_INTERVAL = Duration.ofMillis(500);
    private static final Pattern LINK = Pattern.compile("https://claude\\.ai/code/session_[A-Za-z0-9]+");
    private static final String LINK_LINE = "/remote-control is active";
    private static final String TRUST_PROMPT = "trust this folder";
    private static final String SELECTED_CHOICE_MARK = "\u276F";
    private static final int TRUST_PROMPT_LINES = 5;
    private static final Duration TRUST_PROMPT_SETTLE_TIME = Duration.ofSeconds(1);
    private static final Duration TRUST_ANSWER_RETRY_TIME = Duration.ofSeconds(3);
    private static final String TERMINAL_VARIABLE = "TERM";
    private static final String TERMINAL_TYPE = "tmux-256color";
    private static final String SCREEN_WIDTH = "200";
    private static final String SCREEN_HEIGHT = "50";
    private static final String ENDED_MESSAGE = "The live session ended before it showed a link.";

    private final ClaudeSettings settings;
    private final LiveFiles liveFiles;
    private final Map<String, String> parentEnvironment;
    private final Duration openTimeout;
    private final TmuxCommand tmux;
    private final TerminalWindow terminalWindow;

    public TmuxLiveSessions(ClaudeSettings settings, LiveFiles liveFiles, Map<String, String> parentEnvironment,
            Duration openTimeout, List<String> terminalCommand) {
        this(settings, liveFiles, parentEnvironment, openTimeout, new TmuxCommand(),
                new TerminalWindow(terminalCommand, TmuxCommand.FACTORY_SERVER, parentEnvironment));
    }

    /** Uses the private tmux server with this socket name, for the tests. */
    TmuxLiveSessions(ClaudeSettings settings, LiveFiles liveFiles, Map<String, String> parentEnvironment,
            Duration openTimeout, String socketName) {
        this(settings, liveFiles, parentEnvironment, openTimeout, new TmuxCommand(socketName),
                new TerminalWindow(List.of("false"), socketName, parentEnvironment));
    }

    TmuxLiveSessions(ClaudeSettings settings, LiveFiles liveFiles, Map<String, String> parentEnvironment,
            Duration openTimeout, TmuxCommand tmux, TerminalWindow terminalWindow) {
        this.settings = settings;
        this.liveFiles = liveFiles;
        this.parentEnvironment = Map.copyOf(parentEnvironment);
        this.openTimeout = openTimeout;
        this.tmux = tmux;
        this.terminalWindow = terminalWindow;
    }

    /** On any failure after the start, the session is closed: the record never knows of a session that is left. */
    /** Tells if the full text is a Remote Control link, with the same pattern that reads the link on the screen. */
    public static boolean isRemoteControlLink(String text) {
        return text != null && LINK.matcher(text).matches();
    }

    @Override
    public String open(int issueNumber, UUID sessionId, Path worktree, List<String> allowedTools)
            throws LiveSessionException {
        clearContinueRequest(issueNumber);
        killSession(issueNumber);
        Path settingsFile = liveFiles.writeSettingsFile(issueNumber);
        Path launchScript = liveFiles.writeLaunchScript(issueNumber,
                createLaunchScript(issueNumber, sessionId, settingsFile, allowedTools));
        try {
            startSession(issueNumber, worktree, launchScript);
            return waitForTheLink(issueNumber);
        } catch (LiveSessionException | RuntimeException exception) {
            killSessionAfterAFailure(issueNumber, exception);
            throw exception;
        }
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
    public void openTerminal(int issueNumber) throws LiveSessionException {
        if (!hasSession(issueNumber)) {
            throw new LiveSessionException("The live session of the issue #" + issueNumber + " is not open.");
        }
        terminalWindow.open(sessionName(issueNumber));
    }

    @Override
    public boolean isContinueRequested(int issueNumber) {
        return liveFiles.isContinueRequested(issueNumber);
    }

    @Override
    public void clearContinueRequest(int issueNumber) {
        liveFiles.clearContinueRequest(issueNumber);
    }

    @Override
    public void requestContinue(int issueNumber) {
        liveFiles.requestContinue(issueNumber);
    }

    /** The argument form runs bash directly: the shell of the user and the length limit of tmux do not apply. */
    private void startSession(int issueNumber, Path worktree, Path launchScript) throws LiveSessionException {
        tmux.run("new-session", "-d", "-s", sessionName(issueNumber), "-x", SCREEN_WIDTH, "-y", SCREEN_HEIGHT,
                "-c", worktree.toString(), "--", "/bin/bash", launchScript.toString());
        turnOffRemainOnExit(issueNumber);
        turnOnTheMouse(issueNumber);
    }

    /** A configuration with remain-on-exit on must not keep a session that ended. */
    private void turnOffRemainOnExit(int issueNumber) throws LiveSessionException {
        setSessionOption(issueNumber, "remain-on-exit", "off");
    }

    /** Without the mouse, the wheel does not scroll the history of the terminal. */
    private void turnOnTheMouse(int issueNumber) throws LiveSessionException {
        setSessionOption(issueNumber, "mouse", "on");
    }

    private void setSessionOption(int issueNumber, String option, String value) throws LiveSessionException {
        try {
            tmux.run("set-option", "-t", windowTarget(issueNumber), option, value);
        } catch (LiveSessionException exception) {
            if (hasSession(issueNumber)) {
                throw exception;
            }
            throw new LiveSessionException(ENDED_MESSAGE);
        }
    }

    /**
     * Claude shows the trust prompt before it reads the keys, and it loses the keys of that time. So the worker
     * answers only a prompt that stays on the screen, and it answers again when the prompt does not go away.
     */
    private String waitForTheLink(int issueNumber) throws LiveSessionException {
        long deadline = System.nanoTime() + openTimeout.toNanos();
        long promptSeenSince = 0;
        long lastAnswerAt = 0;
        while (true) {
            String screen = captureScreen(issueNumber);
            Optional<String> link = findTheLink(screen);
            if (link.isPresent()) {
                return link.get();
            }
            long now = System.nanoTime();
            if (!showsTheTrustPrompt(screen)) {
                promptSeenSince = 0;
            } else if (promptSeenSince == 0) {
                promptSeenSince = now;
            } else if (isTimeToAnswer(now, promptSeenSince, lastAnswerAt)) {
                answerTheTrustPrompt(issueNumber, screen);
                lastAnswerAt = now;
            }
            if (now >= deadline) {
                throw new LiveSessionException("The live session did not show a Remote Control link in "
                        + openTimeout.toSeconds() + " seconds.");
            }
            pauseBeforeTheNextPoll();
        }
    }

    /** Only the status line of Remote Control counts: an old link in the conversation does not. */
    private Optional<String> findTheLink(String screen) {
        Optional<String> link = Optional.empty();
        for (String line : screen.lines().filter(line -> line.contains(LINK_LINE)).toList()) {
            Matcher matcher = LINK.matcher(line);
            if (matcher.find()) {
                link = Optional.of(matcher.group());
            }
        }
        return link;
    }

    /** Only the last lines count: old text about the trust prompt does not. */
    private boolean showsTheTrustPrompt(String screen) {
        List<String> lines = screen.lines().filter(line -> !line.isBlank()).toList();
        List<String> lastLines = lines.subList(Math.max(0, lines.size() - TRUST_PROMPT_LINES), lines.size());
        return lastLines.stream().anyMatch(line -> line.contains(TRUST_PROMPT));
    }

    private String captureScreen(int issueNumber) throws LiveSessionException {
        Optional<String> screen = Optional.empty();
        if (hasSession(issueNumber)) {
            screen = captureScreenOfAnOpenSession(issueNumber);
        }
        return screen.orElseThrow(() -> new LiveSessionException(ENDED_MESSAGE));
    }

    /** The session can end between the check and the capture: then the screen is empty. */
    private Optional<String> captureScreenOfAnOpenSession(int issueNumber) throws LiveSessionException {
        try {
            return Optional.of(tmux.run("capture-pane", "-p", "-J", "-t", windowTarget(issueNumber)));
        } catch (LiveSessionException exception) {
            if (hasSession(issueNumber)) {
                throw exception;
            }
            return Optional.empty();
        }
    }

    private static boolean isTimeToAnswer(long now, long promptSeenSince, long lastAnswerAt) {
        boolean promptSettled = now - promptSeenSince >= TRUST_PROMPT_SETTLE_TIME.toNanos();
        boolean answerLost = lastAnswerAt == 0 || now - lastAnswerAt >= TRUST_ANSWER_RETRY_TIME.toNanos();
        return promptSettled && answerLost;
    }

    /** Claude Code 2.1.319 accepts the trust prompt with Down, then Enter. Down is not sent again on the yes. */
    private void answerTheTrustPrompt(int issueNumber, String screen) throws LiveSessionException {
        if (!isTheYesSelected(screen)) {
            tmux.run("send-keys", "-t", windowTarget(issueNumber), "Down");
        }
        tmux.run("send-keys", "-t", windowTarget(issueNumber), "Enter");
    }

    private static boolean isTheYesSelected(String screen) {
        return screen.lines().anyMatch(line -> line.contains(SELECTED_CHOICE_MARK) && line.contains(TRUST_PROMPT));
    }

    private void pauseBeforeTheNextPoll() throws LiveSessionException {
        try {
            Thread.sleep(POLL_INTERVAL.toMillis());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new LiveSessionException("The open of the live session was interrupted.", exception);
        }
    }

    /** The interrupt flag makes every tmux command fail at once, so the kill runs without it. */
    private void killSessionAfterAFailure(int issueNumber, Exception failure) {
        boolean interrupted = Thread.interrupted();
        try {
            killSession(issueNumber);
        } catch (LiveSessionException | RuntimeException killFailure) {
            failure.addSuppressed(killFailure);
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
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

    private String createLaunchScript(int issueNumber, UUID sessionId, Path settingsFile,
                                      List<String> allowedTools) {
        List<String> words = new ArrayList<>();
        words.addAll(List.of("exec", "env", "-i"));
        words.addAll(createEnvironmentAssignments());
        words.addAll(createClaudeArguments(issueNumber, sessionId, settingsFile, allowedTools));
        return "#!/bin/bash\n" + ShellQuoting.join(words) + "\n";
    }

    /** The same filter as a phase: no credential variable reaches the live session. */
    private List<String> createEnvironmentAssignments() {
        List<String> assignments = new ArrayList<>();
        ClaudeCommand.environment(parentEnvironment).forEach((name, value) -> {
            if (!name.equals(TERMINAL_VARIABLE)) {
                assignments.add(name + "=" + value);
            }
        });
        assignments.add(TERMINAL_VARIABLE + "=" + TERMINAL_TYPE);
        return assignments;
    }

    private List<String> createClaudeArguments(int issueNumber, UUID sessionId, Path settingsFile,
                                               List<String> allowedTools) {
        List<String> arguments = new ArrayList<>();
        arguments.add(settings.command());
        arguments.addAll(List.of("--resume", sessionId.toString()));
        arguments.addAll(List.of("--remote-control", "Fix #" + issueNumber));
        arguments.addAll(List.of("--settings", settingsFile.toString()));
        arguments.addAll(List.of("--plugin-dir", liveFiles.pluginDirectory().toString()));
        arguments.addAll(ClaudeCommand.humanLanguageArguments());
        arguments.add("--allowedTools");
        arguments.addAll(allowedTools);
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

    private static String windowTarget(int issueNumber) {
        return sessionTarget(issueNumber) + ":";
    }
}
