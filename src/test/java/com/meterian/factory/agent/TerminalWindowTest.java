package com.meterian.factory.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TerminalWindowTest {

    private static final String TMUX_SERVER = "factory-test";
    private static final String SESSION_NAME = "factory-issue-7";
    private static final String FAILURE_PREFIX = "The terminal window did not open: ";

    @TempDir
    Path temporaryDirectory;

    @Test
    void runsTheTerminalCommandWithTheAttachCommand() throws Exception {
        Path arguments = temporaryDirectory.resolve("arguments.txt");
        Path terminal = writeFakeTerminal("printf '%s\\n' \"$@\" > '" + arguments + "'\n");

        windowFor(List.of(terminal.toString(), "--")).open(SESSION_NAME);

        assertThat(Files.readAllLines(arguments))
                .containsExactly("--", "tmux", "-L", "factory-test", "attach", "-t", "factory-issue-7");
    }

    @Test
    void aMissingProgramGivesALiveSessionException() {
        TerminalWindow window = windowFor(List.of(temporaryDirectory.resolve("missing").toString()));

        assertThatThrownBy(() -> window.open(SESSION_NAME))
                .isInstanceOf(LiveSessionException.class)
                .hasMessageStartingWith(FAILURE_PREFIX);
    }

    @Test
    void anExitCodeThatIsNotZeroGivesALiveSessionException() throws Exception {
        TerminalWindow window = windowFor(List.of(writeFakeTerminal("exit 3\n").toString()));

        assertThatThrownBy(() -> window.open(SESSION_NAME))
                .isInstanceOf(LiveSessionException.class)
                .hasMessageStartingWith(FAILURE_PREFIX)
                .hasMessageContaining("3");
    }

    @Test
    void aTerminalThatStaysInTheForegroundCountsAsOpen() throws Exception {
        TerminalWindow window = windowFor(List.of(writeFakeTerminal("exec sleep 30\n").toString()));
        long start = System.nanoTime();

        assertThatCode(() -> window.open(SESSION_NAME)).doesNotThrowAnyException();

        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(10));
    }

    private TerminalWindow windowFor(List<String> terminalCommand) {
        return new TerminalWindow(terminalCommand, TMUX_SERVER, Map.of());
    }

    private Path writeFakeTerminal(String body) throws IOException {
        Path script = temporaryDirectory.resolve("terminal");
        Files.writeString(script, "#!/bin/sh\n" + body);
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwx------"));
        return script;
    }
}
