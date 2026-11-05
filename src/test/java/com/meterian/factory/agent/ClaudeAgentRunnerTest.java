package com.meterian.factory.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.meterian.factory.testing.TestTools;
import com.meterian.factory.configuration.ClaudeSettings;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ClaudeAgentRunnerTest {

    private static final Duration LIMIT = Duration.ofSeconds(5);

    @TempDir
    Path directory;

    @Test
    void returnsTheReportOfTheRun() throws Exception {
        SessionReport report = runnerFor("success.jsonl", "", "0").runPhase(requestOf(7));

        assertThat(report.success()).isTrue();
        assertThat(report.resultText()).isEqualTo("ok");
    }

    @Test
    void givesThePromptOnTheStandardInput() throws Exception {
        runnerFor("success.jsonl", "", "0").runPhase(requestOf(7));

        assertThat(Files.readString(directory.resolve("prompt.txt"))).isEqualTo("Fix it");
    }

    @Test
    void stopsAtOnceOnAnApiKey() throws Exception {
        ClaudeAgentRunner runner = runnerFor("api-key-init-first.jsonl", "30", "0");
        long start = System.nanoTime();

        assertThatThrownBy(() -> runner.runPhase(requestOf(7))).isInstanceOf(ApiKeyDetectedException.class);

        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(LIMIT);
    }

    @Test
    void reportsANonZeroExitAsFailure() throws Exception {
        SessionReport report = runnerFor("success.jsonl", "", "3").runPhase(requestOf(7));

        assertThat(report.success()).isFalse();
    }

    @Test
    void stopPhaseEndsTheRunningProcess() throws Exception {
        ClaudeAgentRunner runner = runnerFor("success.jsonl", "30", "0");
        long start = System.nanoTime();
        CompletableFuture<SessionReport> run = CompletableFuture.supplyAsync(() -> {
            try {
                return runner.runPhase(requestOf(7));
            } catch (Exception exception) {
                throw new IllegalStateException(exception);
            }
        });
        Thread.sleep(1000);

        runner.stopPhase(7);

        SessionReport report = run.get(5, TimeUnit.SECONDS);
        assertThat(report.success()).isFalse();
        assertThat(report.stopped()).isTrue();
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(LIMIT.plusSeconds(1));
    }

    @Test
    void failsClosedWithoutACredentialSource() throws Exception {
        ClaudeAgentRunner runner = runnerFor("success-without-init.jsonl", "", "0");

        assertThatThrownBy(() -> runner.runPhase(requestOf(7)))
                .isInstanceOf(ApiKeyDetectedException.class)
                .hasMessage("The session did not report its credential source.");
    }

    @Test
    void endsADescendantThatKeepsTheOutputOpen() throws Exception {
        ClaudeAgentRunner runner = runnerFor("success.jsonl", Map.of("FAKE_CLAUDE_SLEEP", "30",
                "FAKE_CLAUDE_BACKGROUND_CHILD", "1"), System.getenv());
        CompletableFuture<SessionReport> run = startInBackground(runner, 7);
        Thread.sleep(1000);

        runner.stopPhase(7);

        assertThat(run.get(5, TimeUnit.SECONDS).success()).isFalse();
        long childPid = Long.parseLong(Files.readString(directory.resolve("child.pid")).trim());
        assertThat(waitUntilDead(childPid)).isTrue();
    }

    @Test
    void endsAnOrphanThatKeepsTheOutputOpenAfterTheParentExits() throws Exception {
        ClaudeAgentRunner runner = runnerFor("success.jsonl", Map.of("FAKE_CLAUDE_SLEEP", "2",
                "FAKE_CLAUDE_BACKGROUND_CHILD", "1"), System.getenv());
        CompletableFuture<SessionReport> run = startInBackground(runner, 7);
        Thread.sleep(3000);

        runner.stopPhase(7);

        run.get(5, TimeUnit.SECONDS);
        long childPid = Long.parseLong(Files.readString(directory.resolve("child.pid")).trim());
        assertThat(waitUntilDead(childPid)).isTrue();
    }

    @Test
    void honorsAStopRequestedBeforeTheProcessStarts() throws Exception {
        ClaudeSettings settings = settingsFor("success.jsonl", Map.of("FAKE_CLAUDE_SLEEP", "30"));
        ClaudeAgentRunner[] holder = new ClaudeAgentRunner[1];
        holder[0] = new ClaudeAgentRunner(settings, Clock.systemUTC(), directory, System.getenv(),
                issueNumber -> holder[0].stopPhase(issueNumber));
        long start = System.nanoTime();

        SessionReport report = holder[0].runPhase(requestOf(7));

        assertThat(report.success()).isFalse();
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(LIMIT);
    }

    @Test
    void aSessionFileInTheFolderOfTheWorktreeIsASavedSession() throws Exception {
        UUID sessionId = UUID.randomUUID();
        Path sessionFile = directory.resolve("projects/-work-issue-7/" + sessionId + ".jsonl");
        Files.createDirectories(sessionFile.getParent());
        Files.writeString(sessionFile, "{}\n");

        boolean isSaved = runnerFor("success.jsonl", "", "0").hasSavedSession(sessionId, Path.of("/work/issue_7"));

        assertThat(isSaved).isTrue();
    }

    @Test
    void noSessionFileIsNoSavedSession() throws Exception {
        boolean isSaved = runnerFor("success.jsonl", "", "0").hasSavedSession(UUID.randomUUID(), Path.of("/work/issue_7"));

        assertThat(isSaved).isFalse();
    }

    @Test
    void refusesNewPhasesAfterTheStop() throws Exception {
        ClaudeAgentRunner runner = runnerFor("success.jsonl", "", "0");

        runner.refuseNewPhases();

        assertThatThrownBy(() -> runner.runPhase(requestOf(7)))
                .isInstanceOf(AgentException.class)
                .hasMessage("The factory is stopping.");
    }

    @Test
    void refusesASecondRunOfTheSameIssue() throws Exception {
        ClaudeAgentRunner runner = runnerFor("success.jsonl", Map.of("FAKE_CLAUDE_SLEEP", "30"), System.getenv());
        CompletableFuture<SessionReport> first = startInBackground(runner, 7);
        Thread.sleep(1000);

        assertThatThrownBy(() -> runner.runPhase(requestOf(7))).isInstanceOf(AgentException.class);

        runner.stopPhase(7);
        first.get(5, TimeUnit.SECONDS);
    }

    @Test
    void givesTheProcessNoCredentialVariable() throws Exception {
        Map<String, String> parent = Map.of("ANTHROPIC_API_KEY", "a", "ANTHROPIC_BASE_URL", "b",
                "CLAUDE_CODE_USE_BEDROCK", "c", "GH_TOKEN", "d", "GITHUB_TOKEN", "e",
                "FACTORY_GITHUB_TOKEN", "f", "PATH", System.getenv("PATH"));

        runnerFor("success.jsonl", Map.of(), parent).runPhase(requestOf(7));

        String environment = Files.readString(directory.resolve("environment.txt"));
        assertThat(environment).contains("PATH=")
                .doesNotContain("ANTHROPIC_", "CLAUDE_CODE_USE_", "GH_TOKEN", "GITHUB_TOKEN");
    }

    @Test
    void endsTheGroupWhenTheParentExitsByItself() throws Exception {
        ClaudeAgentRunner runner = runnerFor("success.jsonl", Map.of("FAKE_CLAUDE_BACKGROUND_CHILD", "1"),
                System.getenv());

        CompletableFuture<SessionReport> run = startInBackground(runner, 7);

        assertThat(run.get(5, TimeUnit.SECONDS).success()).isTrue();
        assertThat(waitUntilDead(readChildPid())).isTrue();
    }

    @Test
    void endsAChildThatStartsAfterTheLastOutputLine() throws Exception {
        ClaudeAgentRunner runner = runnerFor("success.jsonl", Map.of("FAKE_CLAUDE_LATE_CHILD", "1"),
                System.getenv());
        CompletableFuture<SessionReport> run = startInBackground(runner, 7);
        Thread.sleep(1500);

        runner.stopPhase(7);

        run.get(5, TimeUnit.SECONDS);
        assertThat(waitUntilDead(readChildPid())).isTrue();
    }

    private long readChildPid() throws IOException {
        return Long.parseLong(Files.readString(directory.resolve("child.pid")).trim());
    }

    private boolean waitUntilDead(long pid) throws InterruptedException {
        for (int attempt = 0; attempt < 20; attempt++) {
            if (ProcessHandle.of(pid).filter(ProcessHandle::isAlive).isEmpty()) {
                return true;
            }
            Thread.sleep(100);
        }
        return false;
    }

    private CompletableFuture<SessionReport> startInBackground(ClaudeAgentRunner runner, int issueNumber) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return runner.runPhase(requestOf(issueNumber));
            } catch (Exception exception) {
                throw new IllegalStateException(exception);
            }
        });
    }

    private PhaseRequest requestOf(int issueNumber) {
        return new PhaseRequest(issueNumber, UUID.randomUUID(), true, directory, "Fix it",
                TestTools.ALLOWED_TOOLS);
    }

    private ClaudeAgentRunner runnerFor(String stream, String sleepSeconds, String exitCode)
            throws IOException, URISyntaxException {
        Map<String, String> options = new HashMap<>();
        options.put("FAKE_CLAUDE_EXIT", exitCode);
        if (!sleepSeconds.isEmpty()) {
            options.put("FAKE_CLAUDE_SLEEP", sleepSeconds);
        }
        return runnerFor(stream, options, System.getenv());
    }

    private ClaudeAgentRunner runnerFor(String stream, Map<String, String> options, Map<String, String> parent)
            throws IOException, URISyntaxException {
        return new ClaudeAgentRunner(settingsFor(stream, options), Clock.systemUTC(), directory, parent,
                issueNumber -> { });
    }

    private ClaudeSettings settingsFor(String stream, Map<String, String> options)
            throws IOException, URISyntaxException {
        Path fake = Path.of(getClass().getResource("/fake-claude.sh").toURI());
        Path streamFile = Path.of(getClass().getResource("/stream/" + stream).toURI());
        Path wrapper = directory.resolve("claude-" + UUID.randomUUID() + ".sh");
        StringBuilder script = new StringBuilder("#!/bin/bash\n");
        Map<String, String> variables = new TreeMap<>(options);
        variables.put("FAKE_CLAUDE_STREAM", streamFile.toString());
        variables.put("FAKE_CLAUDE_ARGUMENTS", directory.resolve("arguments.txt").toString());
        variables.put("FAKE_CLAUDE_ENVIRONMENT", directory.resolve("environment.txt").toString());
        variables.put("FAKE_CLAUDE_PROMPT", directory.resolve("prompt.txt").toString());
        variables.put("FAKE_CLAUDE_CHILD_PID_FILE", directory.resolve("child.pid").toString());
        variables.forEach((name, value) -> script.append("export ").append(name).append("='").append(value).append("'\n"));
        script.append("exec '").append(fake).append("' \"$@\"\n");
        Files.writeString(wrapper, script);
        Files.setPosixFilePermissions(wrapper, PosixFilePermissions.fromString("rwxr-xr-x"));
        return new ClaudeSettings(wrapper.toString(), ClaudeSettings.DEFAULT_DISALLOWED_TOOLS);
    }
}
