package com.meterian.factory.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.meterian.factory.configuration.ClaudeSettings;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
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

        assertThat(run.get(5, java.util.concurrent.TimeUnit.SECONDS).success()).isFalse();
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(LIMIT.plusSeconds(1));
    }

    @Test
    void failsClosedWithoutACredentialSource() throws Exception {
        ClaudeAgentRunner runner = runnerFor("success-without-init.jsonl", "", "0");

        assertThatThrownBy(() -> runner.runPhase(requestOf(7)))
                .isInstanceOf(ApiKeyDetectedException.class)
                .hasMessage("The session did not report its credential source.");
    }

    private PhaseRequest requestOf(int issueNumber) {
        return new PhaseRequest(issueNumber, UUID.randomUUID(), true, directory, "Fix it");
    }

    private ClaudeAgentRunner runnerFor(String stream, String sleepSeconds, String exitCode)
            throws IOException, URISyntaxException {
        Path fake = Path.of(getClass().getResource("/fake-claude.sh").toURI());
        Path streamFile = Path.of(getClass().getResource("/stream/" + stream).toURI());
        Path wrapper = directory.resolve("claude-" + UUID.randomUUID() + ".sh");
        Files.writeString(wrapper, """
                #!/bin/bash
                export FAKE_CLAUDE_STREAM='%s'
                export FAKE_CLAUDE_SLEEP='%s'
                export FAKE_CLAUDE_EXIT='%s'
                export FAKE_CLAUDE_ARGUMENTS='%s'
                export FAKE_CLAUDE_ENVIRONMENT='%s'
                exec '%s' "$@"
                """.formatted(streamFile, sleepSeconds, exitCode,
                directory.resolve("arguments.txt"), directory.resolve("environment.txt"), fake));
        Files.setPosixFilePermissions(wrapper, PosixFilePermissions.fromString("rwxr-xr-x"));
        ClaudeSettings settings = new ClaudeSettings(wrapper.toString(), ClaudeSettings.DEFAULT_ALLOWED_TOOLS,
                ClaudeSettings.DEFAULT_DISALLOWED_TOOLS);
        return new ClaudeAgentRunner(settings, Clock.systemUTC());
    }
}
