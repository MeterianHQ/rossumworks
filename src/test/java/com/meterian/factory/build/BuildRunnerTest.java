package com.meterian.factory.build;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;

import com.meterian.factory.configuration.TargetRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BuildRunnerTest {

    @TempDir
    Path worktree;

    @Test
    void passesOnExitZero() {
        BuildOutcome outcome = new BuildRunner().run(worktree, target("true", Map.of()));

        assertTrue(outcome.passed());
    }

    @Test
    void failsOnANonZeroExitWithTheTail() {
        BuildOutcome outcome = new BuildRunner().run(worktree, target("seq 1 500; exit 1", Map.of()));

        assertFalse(outcome.passed());
        assertTrue(outcome.outputTail().startsWith("301\n"));
        assertEquals(200, outcome.outputTail().lines().count());
    }

    @Test
    void addsTheBuildEnvironment() {
        BuildOutcome outcome = new BuildRunner().run(worktree,
                target("test \"$LANG\" = C.utf8", Map.of("LANG", "C.utf8")));

        assertTrue(outcome.passed());
    }

    @Test
    void removesTheCredentialVariables() {
        BuildRunner runner = new BuildRunner(Map.of("PATH", System.getenv("PATH"), "GH_TOKEN", "secret",
                "ANTHROPIC_API_KEY", "secret"));

        BuildOutcome outcome = runner.run(worktree,
                target("test -z \"$GH_TOKEN\" && test -z \"$ANTHROPIC_API_KEY\"", Map.of()));

        assertTrue(outcome.passed());
    }

    @Test
    void doesNotWaitForABackgroundProcess() {
        BuildOutcome outcome = new BuildRunner().run(worktree, target("sleep 30 & echo done", Map.of()));

        assertTrue(outcome.passed());
        assertTrue(outcome.outputTail().contains("done"));
    }

    @Test
    void removesCredentialVariablesGivenByTheBuildEnvironment() {
        BuildOutcome outcome = new BuildRunner().run(worktree,
                target("test -z \"$GH_TOKEN\" && test -z \"$ANTHROPIC_API_KEY\"",
                        Map.of("GH_TOKEN", "x", "ANTHROPIC_API_KEY", "y")));

        assertTrue(outcome.passed());
    }

    @Test
    void endsTheBuildWhenTheCallingThreadIsInterrupted() throws IOException, InterruptedException {
        Path pidFile = worktree.resolve("build.pid");
        Thread.currentThread().interrupt();
        long start = System.nanoTime();

        BuildOutcome outcome = new BuildRunner().run(worktree,
                target("echo $$ > build.pid; sleep 30", Map.of()));

        Thread.interrupted();
        assertFalse(outcome.passed());
        assertTrue(Duration.ofNanos(System.nanoTime() - start).toSeconds() < 5);
        long pid = Long.parseLong(Files.readString(pidFile).trim());
        for (int attempt = 0; attempt < 40 && ProcessHandle.of(pid).isPresent(); attempt++) {
            Thread.sleep(50);
        }
        assertFalse(ProcessHandle.of(pid).isPresent());
    }

    @Test
    void capsAnEndlessLine() {
        BuildOutcome outcome = new BuildRunner().run(worktree,
                target("head -c 1000000 /dev/zero | tr '\\0' x", Map.of()));

        assertTrue(outcome.passed());
        assertEquals(10_000, outcome.outputTail().length());
    }

    private TargetRepository target(String buildCommand, Map<String, String> buildEnvironment) {
        return new TargetRepository("owner", "name", worktree, buildCommand, buildEnvironment);
    }
}
