package com.meterian.factory.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.meterian.factory.configuration.ClaudeSettings;
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
                .open(ISSUE_NUMBER, UUID.randomUUID(), worktree);

        assertThat(link).isEqualTo(FAKE_LINK);
    }

    @Test
    void openResumesTheSameSessionWithTheFactoryFiles() throws Exception {
        UUID sessionId = UUID.randomUUID();

        sessionsFor(Map.of(), System.getenv(), SUCCESS_TIMEOUT).open(ISSUE_NUMBER, sessionId, worktree);

        List<String> arguments = Files.readAllLines(directory.resolve("arguments.txt"));
        assertThat(arguments).containsSubsequence("--resume", sessionId.toString());
        assertThat(arguments).containsSubsequence("--remote-control", "Fix #7");
        assertThat(arguments).containsSubsequence("--settings",
                directory.resolve("live/issue-7.settings.json").toString());
        assertThat(arguments).containsSubsequence("--plugin-dir", liveFiles.pluginDirectory().toString());
        assertThat(arguments).containsSubsequence("--allowedTools", "Read", "Edit");
        assertThat(arguments).containsSubsequence("--disallowedTools", "Bash(git push*)");
    }

    @Test
    void openStartsTheSessionInTheWorktree() throws Exception {
        sessionsFor(Map.of(), System.getenv(), SUCCESS_TIMEOUT).open(ISSUE_NUMBER, UUID.randomUUID(), worktree);

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

        sessionsFor(Map.of(), parent, SUCCESS_TIMEOUT).open(ISSUE_NUMBER, UUID.randomUUID(), worktree);

        assertThat(Files.readString(directory.resolve("environment.txt")))
                .contains("PATH=")
                .doesNotContain("ANTHROPIC_API_KEY", "GH_TOKEN");
    }

    @Test
    void theSessionGetsAValueWithASingleQuoteUnchanged() throws Exception {
        Map<String, String> parent = new HashMap<>(System.getenv());
        parent.put("FACTORY_TEST_VALUE", "it's a 'test' $HOME");

        sessionsFor(Map.of(), parent, SUCCESS_TIMEOUT).open(ISSUE_NUMBER, UUID.randomUUID(), worktree);

        assertThat(Files.readAllLines(directory.resolve("environment.txt")))
                .contains("FACTORY_TEST_VALUE=it's a 'test' $HOME");
    }

    @Test
    void openFailsAndClosesWhenNoLinkAppears() throws Exception {
        TmuxLiveSessions sessions = sessionsFor(Map.of("FAKE_CLAUDE_LIVE_NO_LINK", "1"), System.getenv(),
                FAILURE_TIMEOUT);

        assertThatThrownBy(() -> sessions.open(ISSUE_NUMBER, UUID.randomUUID(), worktree))
                .isInstanceOf(LiveSessionException.class)
                .hasMessage("The live session did not show a Remote Control link in 3 seconds.");

        assertThat(sessions.isOpen(ISSUE_NUMBER)).isFalse();
    }

    @Test
    void openFailsWhenTheSessionEndsBeforeTheLink() throws Exception {
        TmuxLiveSessions sessions = sessionsFor(Map.of("FAKE_CLAUDE_LIVE_EXIT", "1"), System.getenv(),
                FAILURE_TIMEOUT);

        assertThatThrownBy(() -> sessions.open(ISSUE_NUMBER, UUID.randomUUID(), worktree))
                .isInstanceOf(LiveSessionException.class)
                .hasMessage("The live session ended before it showed a link.");

        assertThat(sessions.isOpen(ISSUE_NUMBER)).isFalse();
    }

    @Test
    void openReplacesAnOldSessionOfTheIssue() throws Exception {
        TmuxLiveSessions sessions = sessionsFor(Map.of(), System.getenv(), SUCCESS_TIMEOUT);
        sessions.open(ISSUE_NUMBER, UUID.randomUUID(), worktree);
        String oldProcessId = runTmux("display-message", "-p", "-t", "=factory-issue-7:", "#{pane_pid}");

        sessions.open(ISSUE_NUMBER, UUID.randomUUID(), worktree);

        String newProcessId = runTmux("display-message", "-p", "-t", "=factory-issue-7:", "#{pane_pid}");
        assertThat(newProcessId).isNotEqualTo(oldProcessId);
        assertThat(runTmux("list-sessions", "-F", "#{session_name}").lines()).containsExactly("factory-issue-7");
    }

    @Test
    void openClearsAnOldContinueRequest() throws Exception {
        Files.writeString(liveFiles.continueMarker(ISSUE_NUMBER), "old");
        TmuxLiveSessions sessions = sessionsFor(Map.of(), System.getenv(), SUCCESS_TIMEOUT);

        sessions.open(ISSUE_NUMBER, UUID.randomUUID(), worktree);

        assertThat(sessions.isContinueRequested(ISSUE_NUMBER)).isFalse();
    }

    @Test
    void closeEndsTheSession() throws Exception {
        TmuxLiveSessions sessions = sessionsFor(Map.of(), System.getenv(), SUCCESS_TIMEOUT);
        sessions.open(ISSUE_NUMBER, UUID.randomUUID(), worktree);

        sessions.close(ISSUE_NUMBER);

        assertThat(sessions.isOpen(ISSUE_NUMBER)).isFalse();
        sessions.close(ISSUE_NUMBER);
        sessions.close(8);
    }

    @Test
    void isOpenDoesNotMatchTheSessionOfAnotherIssue() throws Exception {
        TmuxLiveSessions sessions = sessionsFor(Map.of(), System.getenv(), SUCCESS_TIMEOUT);
        sessions.open(12, UUID.randomUUID(), worktree);

        assertThat(sessions.isOpen(1)).isFalse();
        assertThat(sessions.isOpen(12)).isTrue();
    }

    @Test
    void isContinueRequestedReadsTheMarkerOfTheHook() throws Exception {
        TmuxLiveSessions sessions = sessionsFor(Map.of(), System.getenv(), SUCCESS_TIMEOUT);
        Files.writeString(liveFiles.continueMarker(ISSUE_NUMBER), "now");

        assertThat(sessions.isContinueRequested(ISSUE_NUMBER)).isTrue();

        sessions.clearContinueRequest(ISSUE_NUMBER);

        assertThat(sessions.isContinueRequested(ISSUE_NUMBER)).isFalse();
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
        variables.forEach((name, value) -> script.append("export ").append(name).append("='").append(value).append("'\n"));
        script.append("exec /bin/bash '").append(fake).append("' \"$@\"\n");
        Files.writeString(wrapper, script);
        Files.setPosixFilePermissions(wrapper, PosixFilePermissions.fromString("rwxr-xr-x"));
        return new ClaudeSettings(wrapper.toString(), ClaudeSettings.DEFAULT_ALLOWED_TOOLS,
                ClaudeSettings.DEFAULT_DISALLOWED_TOOLS);
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
