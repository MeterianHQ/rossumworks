package com.meterian.factory.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.meterian.factory.testing.TestTools;
import com.meterian.factory.configuration.ClaudeSettings;
import com.meterian.factory.configuration.FactoryConfiguration;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TmuxLiveSessionsTest {

    private static final int ISSUE_NUMBER = 7;
    private static final Duration SUCCESS_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration FAILURE_TIMEOUT = Duration.ofSeconds(3);
    private static final String FAKE_LINK = "https://claude.ai/code/session_FAKE123";

    @TempDir
    Path directory;

    private final String socketName = "factory-test-" + UUID.randomUUID();
    private LiveFiles liveFiles;
    private Path worktree;

    @BeforeEach
    void createTheLiveFiles() throws IOException {
        liveFiles = new LiveFiles(directory.resolve("live"));
        liveFiles.install();
        worktree = Files.createDirectories(directory.resolve("worktree"));
    }

    @AfterEach
    void killThePrivateServer() throws Exception {
        runTmux("kill-server");
        Files.deleteIfExists(socketFile());
    }

    /** The path that tmux uses for the option -L. */
    private Path socketFile() throws IOException {
        String socketRoot = System.getenv().getOrDefault("TMUX_TMPDIR", "/tmp");
        Object userId = Files.getAttribute(directory, "unix:uid");
        return Path.of(socketRoot, "tmux-" + userId, socketName);
    }

    @Test
    void openAnswersTheTrustPromptAndReturnsTheLink() throws Exception {
        String link = sessionsFor(Map.of(), System.getenv(), SUCCESS_TIMEOUT)
                .open(ISSUE_NUMBER, UUID.randomUUID(), worktree, TestTools.ALLOWED_TOOLS);

        assertThat(link).isEqualTo(FAKE_LINK);
    }

    @Test
    void openResumesTheSameSessionWithTheFactoryFiles() throws Exception {
        UUID sessionId = UUID.randomUUID();

        sessionsFor(Map.of(), System.getenv(), SUCCESS_TIMEOUT).open(ISSUE_NUMBER, sessionId, worktree, TestTools.ALLOWED_TOOLS);

        List<String> arguments = Files.readAllLines(directory.resolve("arguments.txt"));
        assertThat(arguments).containsSubsequence("--resume", sessionId.toString());
        assertThat(arguments).containsSubsequence("--remote-control", "Fix #7");
        assertThat(arguments).containsSubsequence("--settings",
                directory.resolve("live/issue-7.settings.json").toString());
        assertThat(arguments).containsSubsequence("--plugin-dir", liveFiles.pluginDirectory().toString());
        assertThat(arguments).containsSubsequence("--allowedTools", "Read", "Edit");
        assertThat(arguments).containsSubsequence("--disallowedTools", "Bash(git push*)");
        assertThat(arguments).containsSubsequence("--append-system-prompt", ClaudeCommand.HUMAN_LANGUAGE_RULE);
    }

    @Test
    void openStartsTheSessionInTheWorktree() throws Exception {
        sessionsFor(Map.of(), System.getenv(), SUCCESS_TIMEOUT).open(ISSUE_NUMBER, UUID.randomUUID(), worktree, TestTools.ALLOWED_TOOLS);

        assertThat(Files.readString(directory.resolve("directory.txt")).strip())
                .isEqualTo(worktree.toRealPath().toString());
    }

    @Test
    void theSessionGetsNoCredentialVariable() throws Exception {
        Map<String, String> parent = new HashMap<>(System.getenv());
        parent.put("ANTHROPIC_API_KEY", "a");
        parent.put("GH_TOKEN", "b");
        runTmux("new-session", "-d", "-s", "keeper", "sleep 60");
        runTmux("set-environment", "-g", "ANTHROPIC_API_KEY", "x");

        sessionsFor(Map.of(), parent, SUCCESS_TIMEOUT).open(ISSUE_NUMBER, UUID.randomUUID(), worktree, TestTools.ALLOWED_TOOLS);

        assertThat(Files.readString(directory.resolve("environment.txt")))
                .contains("PATH=")
                .doesNotContain("ANTHROPIC_API_KEY", "GH_TOKEN");
    }

    @Test
    void theSessionGetsAValueWithASingleQuoteUnchanged() throws Exception {
        Map<String, String> parent = new HashMap<>(System.getenv());
        parent.put("FACTORY_TEST_VALUE", "it's a 'test' $HOME");

        sessionsFor(Map.of(), parent, SUCCESS_TIMEOUT).open(ISSUE_NUMBER, UUID.randomUUID(), worktree, TestTools.ALLOWED_TOOLS);

        assertThat(Files.readAllLines(directory.resolve("environment.txt")))
                .contains("FACTORY_TEST_VALUE=it's a 'test' $HOME");
    }

    @Test
    void openFailsAndClosesWhenNoLinkAppears() throws Exception {
        TmuxLiveSessions sessions = sessionsFor(Map.of("FAKE_CLAUDE_LIVE_NO_LINK", "1"), System.getenv(),
                FAILURE_TIMEOUT);

        assertThatThrownBy(() -> sessions.open(ISSUE_NUMBER, UUID.randomUUID(), worktree, TestTools.ALLOWED_TOOLS))
                .isInstanceOf(LiveSessionException.class)
                .hasMessage("The live session did not show a Remote Control link in 3 seconds.");

        assertThat(sessions.isOpen(ISSUE_NUMBER)).isFalse();
    }

    @Test
    void openFailsWhenTheSessionEndsBeforeTheLink() throws Exception {
        TmuxLiveSessions sessions = sessionsFor(Map.of("FAKE_CLAUDE_LIVE_EXIT", "1"), System.getenv(),
                FAILURE_TIMEOUT);

        assertThatThrownBy(() -> sessions.open(ISSUE_NUMBER, UUID.randomUUID(), worktree, TestTools.ALLOWED_TOOLS))
                .isInstanceOf(LiveSessionException.class)
                .hasMessage("The live session ended before it showed a link.");

        assertThat(sessions.isOpen(ISSUE_NUMBER)).isFalse();
    }

    @Test
    void openReplacesAnOldSessionOfTheIssue() throws Exception {
        TmuxLiveSessions sessions = sessionsFor(Map.of(), System.getenv(), SUCCESS_TIMEOUT);
        sessions.open(ISSUE_NUMBER, UUID.randomUUID(), worktree, TestTools.ALLOWED_TOOLS);
        String oldProcessId = runTmux("display-message", "-p", "-t", "=factory-issue-7:", "#{pane_pid}");

        sessions.open(ISSUE_NUMBER, UUID.randomUUID(), worktree, TestTools.ALLOWED_TOOLS);

        String newProcessId = runTmux("display-message", "-p", "-t", "=factory-issue-7:", "#{pane_pid}");
        assertThat(newProcessId).isNotEqualTo(oldProcessId);
        assertThat(runTmux("list-sessions", "-F", "#{session_name}").lines()).containsExactly("factory-issue-7");
    }

    @Test
    void openClearsAnOldContinueRequest() throws Exception {
        Files.writeString(liveFiles.continueMarker(ISSUE_NUMBER), "old");
        TmuxLiveSessions sessions = sessionsFor(Map.of(), System.getenv(), SUCCESS_TIMEOUT);

        sessions.open(ISSUE_NUMBER, UUID.randomUUID(), worktree, TestTools.ALLOWED_TOOLS);

        assertThat(sessions.isContinueRequested(ISSUE_NUMBER)).isFalse();
    }

    @Test
    void closeEndsTheSession() throws Exception {
        TmuxLiveSessions sessions = sessionsFor(Map.of(), System.getenv(), SUCCESS_TIMEOUT);
        sessions.open(ISSUE_NUMBER, UUID.randomUUID(), worktree, TestTools.ALLOWED_TOOLS);

        sessions.close(ISSUE_NUMBER);

        assertThat(sessions.isOpen(ISSUE_NUMBER)).isFalse();
        sessions.close(ISSUE_NUMBER);
        sessions.close(8);
    }

    @Test
    void isOpenDoesNotMatchTheSessionOfAnotherIssue() throws Exception {
        TmuxLiveSessions sessions = sessionsFor(Map.of(), System.getenv(), SUCCESS_TIMEOUT);
        sessions.open(12, UUID.randomUUID(), worktree, TestTools.ALLOWED_TOOLS);

        assertThat(sessions.isOpen(1)).isFalse();
        assertThat(sessions.isOpen(12)).isTrue();
    }

    @Test
    void openClosesTheSessionWhenATmuxCommandFailsAfterTheStart() throws Exception {
        TmuxLiveSessions sessions = new TmuxLiveSessions(settingsFor(Map.of()), liveFiles, System.getenv(),
                SUCCESS_TIMEOUT, new TmuxCommand(tmuxThatFailsOnCapture().toString(), socketName),
                new TerminalWindow(FactoryConfiguration.DEFAULT_TERMINAL_COMMAND, socketName, System.getenv()));

        assertThatThrownBy(() -> sessions.open(ISSUE_NUMBER, UUID.randomUUID(), worktree, TestTools.ALLOWED_TOOLS))
                .isInstanceOf(LiveSessionException.class)
                .hasMessageContaining("tmux capture-pane");

        assertThat(sessions.isOpen(ISSUE_NUMBER)).isFalse();
    }

    @Test
    void anInterruptDuringTheOpenClosesTheSessionAndKeepsTheFlag() throws Exception {
        TmuxLiveSessions sessions = sessionsFor(Map.of("FAKE_CLAUDE_LIVE_NO_LINK", "1"), System.getenv(),
                SUCCESS_TIMEOUT);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicBoolean stillInterrupted = new AtomicBoolean();
        Thread opener = new Thread(() -> {
            try {
                sessions.open(ISSUE_NUMBER, UUID.randomUUID(), worktree, TestTools.ALLOWED_TOOLS);
            } catch (Throwable exception) {
                failure.set(exception);
            }
            stillInterrupted.set(Thread.currentThread().isInterrupted());
        });
        opener.start();
        Thread.sleep(1500);

        opener.interrupt();
        opener.join(TimeUnit.SECONDS.toMillis(15));

        assertThat(failure.get()).isInstanceOf(LiveSessionException.class);
        assertThat(stillInterrupted).isTrue();
        assertThat(sessions.isOpen(ISSUE_NUMBER)).isFalse();
    }

    @Test
    void openTakesTheLinkOfTheStatusLineAndNotAnOldLink() throws Exception {
        String link = sessionsFor(Map.of("FAKE_CLAUDE_LIVE_OLD_LINK", "1"), System.getenv(), SUCCESS_TIMEOUT)
                .open(ISSUE_NUMBER, UUID.randomUUID(), worktree, TestTools.ALLOWED_TOOLS);

        assertThat(link).isEqualTo(FAKE_LINK);
    }

    @Test
    void oldTextAboutTheTrustPromptSendsNoKeys() throws Exception {
        TmuxLiveSessions sessions = sessionsFor(Map.of("FAKE_CLAUDE_LIVE_OLD_TRUST", "1"), System.getenv(),
                FAILURE_TIMEOUT);

        assertThatThrownBy(() -> sessions.open(ISSUE_NUMBER, UUID.randomUUID(), worktree, TestTools.ALLOWED_TOOLS))
                .isInstanceOf(LiveSessionException.class);

        assertThat(directory.resolve("answer.txt")).doesNotExist();
    }

    @Test
    void aVeryLargeEnvironmentStillOpens() throws Exception {
        Map<String, String> parent = new HashMap<>(System.getenv());
        for (int index = 0; index < 20; index++) {
            parent.put("FACTORY_TEST_LARGE_" + index, "x".repeat(1000));
        }

        String link = sessionsFor(Map.of(), parent, SUCCESS_TIMEOUT).open(ISSUE_NUMBER, UUID.randomUUID(), worktree, TestTools.ALLOWED_TOOLS);

        assertThat(link).isEqualTo(FAKE_LINK);
        assertThat(Files.readAllLines(directory.resolve("environment.txt")))
                .contains("FACTORY_TEST_LARGE_19=" + "x".repeat(1000));
    }

    @Test
    void theSessionGetsTheTerminalTypeOfTmux() throws Exception {
        Map<String, String> parent = new HashMap<>(System.getenv());
        parent.put("TERM", "dumb");

        sessionsFor(Map.of(), parent, SUCCESS_TIMEOUT).open(ISSUE_NUMBER, UUID.randomUUID(), worktree, TestTools.ALLOWED_TOOLS);

        assertThat(Files.readAllLines(directory.resolve("environment.txt")))
                .contains("TERM=tmux-256color")
                .doesNotContain("TERM=dumb");
    }

    @Test
    void aSessionThatEndsIsNotKeptByRemainOnExit() throws Exception {
        runTmux("new-session", "-d", "-s", "keeper", "sleep 60");
        runTmux("set-option", "-g", "remain-on-exit", "on");
        TmuxLiveSessions sessions = sessionsFor(Map.of("FAKE_CLAUDE_LIVE_EXIT", "1"), System.getenv(),
                SUCCESS_TIMEOUT);

        assertThatThrownBy(() -> sessions.open(ISSUE_NUMBER, UUID.randomUUID(), worktree, TestTools.ALLOWED_TOOLS))
                .isInstanceOf(LiveSessionException.class)
                .hasMessage("The live session ended before it showed a link.");
    }

    @Test
    void isContinueRequestedReadsTheMarkerOfTheHook() throws Exception {
        TmuxLiveSessions sessions = sessionsFor(Map.of(), System.getenv(), SUCCESS_TIMEOUT);
        Files.writeString(liveFiles.continueMarker(ISSUE_NUMBER), "now");

        assertThat(sessions.isContinueRequested(ISSUE_NUMBER)).isTrue();

        sessions.clearContinueRequest(ISSUE_NUMBER);

        assertThat(sessions.isContinueRequested(ISSUE_NUMBER)).isFalse();
    }

    @Test
    void openTerminalRefusesASessionThatIsNotOpenAndRunsNoTerminal() throws Exception {
        Path marker = directory.resolve("terminal-ran.txt");
        TmuxLiveSessions sessions = sessionsWithTerminal(writeFakeTerminal(marker));

        assertThatThrownBy(() -> sessions.openTerminal(ISSUE_NUMBER))
                .isInstanceOf(LiveSessionException.class)
                .hasMessage("The live session of the issue #7 is not open.");

        assertThat(marker).doesNotExist();
    }

    @Test
    void openTerminalAttachesTheTerminalToTheSessionOfTheIssue() throws Exception {
        Path arguments = directory.resolve("terminal-arguments.txt");
        TmuxLiveSessions sessions = sessionsWithTerminal(writeFakeTerminal(arguments));
        sessions.open(ISSUE_NUMBER, UUID.randomUUID(), worktree, TestTools.ALLOWED_TOOLS);

        sessions.openTerminal(ISSUE_NUMBER);

        assertThat(Files.readAllLines(arguments))
                .containsExactly("tmux", "-L", socketName, "attach", "-t", "factory-issue-7");
    }

    private TmuxLiveSessions sessionsWithTerminal(Path terminal) throws IOException, URISyntaxException {
        return new TmuxLiveSessions(settingsFor(Map.of()), liveFiles, System.getenv(), SUCCESS_TIMEOUT,
                new TmuxCommand(socketName),
                new TerminalWindow(List.of(terminal.toString()), socketName, System.getenv()));
    }

    /** A terminal that writes its arguments to the output file and ends. */
    private Path writeFakeTerminal(Path outputFile) throws IOException {
        Path script = directory.resolve("terminal.sh");
        Files.writeString(script, "#!/bin/sh\nprintf '%s\\n' \"$@\" > '" + outputFile + "'\n");
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwxr-xr-x"));
        return script;
    }

    private TmuxLiveSessions sessionsFor(Map<String, String> options, Map<String, String> parent,
            Duration openTimeout) throws IOException, URISyntaxException {
        return new TmuxLiveSessions(settingsFor(options), liveFiles, parent, openTimeout, socketName);
    }

    private ClaudeSettings settingsFor(Map<String, String> options) throws IOException, URISyntaxException {
        Path fake = Path.of(getClass().getResource("/fake-claude-live.sh").toURI());
        Path wrapper = directory.resolve("claude-" + UUID.randomUUID() + ".sh");
        StringBuilder script = new StringBuilder("#!/bin/bash\n");
        Map<String, String> variables = new TreeMap<>(options);
        variables.put("FAKE_CLAUDE_ARGUMENTS", directory.resolve("arguments.txt").toString());
        variables.put("FAKE_CLAUDE_ENVIRONMENT", directory.resolve("environment.txt").toString());
        variables.put("FAKE_CLAUDE_DIRECTORY", directory.resolve("directory.txt").toString());
        variables.put("FAKE_CLAUDE_ANSWER", directory.resolve("answer.txt").toString());
        variables.forEach((name, value) -> script.append("export ").append(name).append("='").append(value).append("'\n"));
        script.append("exec /bin/bash '").append(fake).append("' \"$@\"\n");
        Files.writeString(wrapper, script);
        Files.setPosixFilePermissions(wrapper, PosixFilePermissions.fromString("rwxr-xr-x"));
        return new ClaudeSettings(wrapper.toString(), ClaudeSettings.DEFAULT_DISALLOWED_TOOLS);
    }

    /** A tmux that fails on capture-pane and runs the real tmux for the other commands. */
    private Path tmuxThatFailsOnCapture() throws IOException {
        Path script = directory.resolve("tmux-that-fails-on-capture.sh");
        Files.writeString(script, """
                #!/bin/bash
                if [ "$3" = capture-pane ]; then
                  echo "capture failed" >&2
                  exit 1
                fi
                exec tmux "$@"
                """);
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwxr-xr-x"));
        return script;
    }

    private String runTmux(String... arguments) throws Exception {
        List<String> command = new ArrayList<>(List.of("tmux", "-L", socketName));
        command.addAll(List.of(arguments));
        Process process = new ProcessBuilder(command)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        process.waitFor(10, TimeUnit.SECONDS);
        return output.strip();
    }
}
