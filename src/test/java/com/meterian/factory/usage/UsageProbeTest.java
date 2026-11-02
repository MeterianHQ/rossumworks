package com.meterian.factory.usage;

import static org.assertj.core.api.Assertions.assertThat;

import com.meterian.factory.agent.UsageWindow;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UsageProbeTest {

    private static final Instant NOW = Instant.parse("2026-11-02T10:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final Duration TIMEOUT = Duration.ofSeconds(20);
    private static final String INIT_WITHOUT_API_KEY =
            "{\"type\":\"system\",\"subtype\":\"init\",\"apiKeySource\":\"none\"}";
    private static final String RATE_LIMIT_EVENT = "{\"type\":\"rate_limit_event\",\"rate_limit_info\":"
            + "{\"unifiedWindows\":{\"seven_day\":{\"utilization\":0.48,\"resetsAt\":1793815200,\"status\":\"allowed\"}}}}";
    private static final String SUCCESS_RESULT =
            "{\"type\":\"result\",\"subtype\":\"success\",\"is_error\":false,\"result\":\"OK\"}";

    @TempDir
    Path temporaryDirectory;

    @Test
    void readsTheUsageWindowsOfAShortSession() throws IOException {
        Path claude = writeFakeClaude(printLines(INIT_WITHOUT_API_KEY, RATE_LIMIT_EVENT, SUCCESS_RESULT));

        List<UsageWindow> windows = probe(claude, Map.of()).readUsageWindows();

        assertThat(windows).containsExactly(
                new UsageWindow("seven_day", 0.48, Instant.ofEpochSecond(1793815200), "allowed", NOW));
    }

    @Test
    void startsASessionWithNoToolsAndNoSavedSession() throws IOException {
        Path arguments = temporaryDirectory.resolve("arguments.txt");
        Path prompt = temporaryDirectory.resolve("prompt.txt");
        Path claude = writeFakeClaude("printf '%s\\n' \"$@\" > '" + arguments + "'\n"
                + "cat > '" + prompt + "'\n"
                + printLines(INIT_WITHOUT_API_KEY, RATE_LIMIT_EVENT, SUCCESS_RESULT));

        probe(claude, Map.of()).readUsageWindows();

        assertThat(Files.readAllLines(arguments)).containsExactly("-p", "--output-format", "stream-json", "--verbose",
                "--no-session-persistence", "--tools", "", "--strict-mcp-config");
        assertThat(Files.readString(prompt)).isEqualTo(UsageProbe.PROMPT);
    }

    @Test
    void removesTheApiKeyFromTheEnvironmentOfTheSession() throws IOException {
        Path environment = temporaryDirectory.resolve("environment.txt");
        Path claude = writeFakeClaude("echo \"key=${ANTHROPIC_API_KEY:-absent} path=${PATH:+set}\" > '"
                + environment + "'\n" + printLines(INIT_WITHOUT_API_KEY, RATE_LIMIT_EVENT, SUCCESS_RESULT));

        probe(claude, Map.of("ANTHROPIC_API_KEY", "secret", "PATH", System.getenv("PATH"))).readUsageWindows();

        assertThat(Files.readString(environment)).isEqualTo("key=absent path=set\n");
    }

    @Test
    void keepsNoUsageWhenTheSessionUsesAnApiKey() throws IOException {
        Path claude = writeFakeClaude(printLines(
                "{\"type\":\"system\",\"subtype\":\"init\",\"apiKeySource\":\"ANTHROPIC_API_KEY\"}",
                RATE_LIMIT_EVENT, SUCCESS_RESULT));

        assertThat(probe(claude, Map.of()).readUsageWindows()).isEmpty();
    }

    @Test
    void keepsNoUsageWhenTheSessionDoesNotReportItsCredentialSource() throws IOException {
        Path claude = writeFakeClaude(printLines(RATE_LIMIT_EVENT, SUCCESS_RESULT));

        assertThat(probe(claude, Map.of()).readUsageWindows()).isEmpty();
    }

    @Test
    void keepsNoUsageWhenTheSessionFails() throws IOException {
        Path claude = writeFakeClaude(printLines(INIT_WITHOUT_API_KEY, RATE_LIMIT_EVENT) + "exit 1\n");

        assertThat(probe(claude, Map.of()).readUsageWindows()).isEmpty();
    }

    @Test
    void stopsTheSessionAfterTheTimeout() throws IOException {
        Path claude = writeFakeClaude(printLines(INIT_WITHOUT_API_KEY, RATE_LIMIT_EVENT) + "sleep 30\n"
                + printLines(SUCCESS_RESULT));
        UsageProbe probe = new UsageProbe(claude.toString(), Map.of(), CLOCK, Duration.ofSeconds(1));
        Instant start = Instant.now();

        List<UsageWindow> windows = probe.readUsageWindows();

        assertThat(windows).isEmpty();
        assertThat(Duration.between(start, Instant.now())).isLessThan(Duration.ofSeconds(10));
    }

    @Test
    void keepsNoUsageWhenTheCommandDoesNotExist() {
        Path missing = temporaryDirectory.resolve("no-claude-here");

        assertThat(probe(missing, Map.of()).readUsageWindows()).isEmpty();
    }

    @Test
    void recordsTheUsageWindowsInTheTrackerInTheBackground() throws Exception {
        Path claude = writeFakeClaude(printLines(INIT_WITHOUT_API_KEY, RATE_LIMIT_EVENT, SUCCESS_RESULT));
        UsageTracker tracker = new UsageTracker(0.90, CLOCK);

        Thread probeThread = probe(claude, Map.of()).startRecording(tracker);
        probeThread.join(TIMEOUT.toMillis());

        assertThat(probeThread.isVirtual()).isTrue();
        assertThat(tracker.windows()).extracting(UsageWindow::name).containsExactly("seven_day");
    }

    private UsageProbe probe(Path claude, Map<String, String> parentEnvironment) {
        return new UsageProbe(claude.toString(), parentEnvironment, CLOCK, TIMEOUT);
    }

    private Path writeFakeClaude(String body) throws IOException {
        Path script = temporaryDirectory.resolve("claude");
        Files.writeString(script, "#!/bin/sh\n" + body);
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwx------"));
        return script;
    }

    private static String printLines(String... lines) {
        StringBuilder script = new StringBuilder();
        for (String line : lines) {
            script.append("echo '").append(line).append("'\n");
        }
        return script.toString();
    }
}
