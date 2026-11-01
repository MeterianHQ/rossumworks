package com.meterian.factory.build;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.meterian.factory.configuration.TargetRepository;
import java.nio.file.Path;
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

    private TargetRepository target(String buildCommand, Map<String, String> buildEnvironment) {
        return new TargetRepository("owner", "name", worktree, buildCommand, buildEnvironment);
    }
}
