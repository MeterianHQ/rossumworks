package com.meterian.factory.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TmuxCommandTest {

    @TempDir
    Path directory;

    @Test
    void theFactoryServerIsNotTheDefaultServer() {
        assertThat(TmuxCommand.FACTORY_SERVER).isEqualTo("rossumworks");
    }

    @Test
    void eachCommandNamesTheServer() throws Exception {
        Path arguments = directory.resolve("arguments.txt");
        TmuxCommand tmux = new TmuxCommand(tmuxThatWritesItsArguments(arguments).toString(), TmuxCommand.FACTORY_SERVER);

        tmux.run("has-session", "-t", "=factory-issue-7");

        assertThat(Files.readAllLines(arguments))
                .containsExactly("-L", "rossumworks", "has-session", "-t", "=factory-issue-7");
    }

    /** A tmux that writes each argument on a line and does nothing else: no tmux server starts. */
    private Path tmuxThatWritesItsArguments(Path arguments) throws IOException {
        Path script = directory.resolve("tmux-that-writes-its-arguments.sh");
        Files.writeString(script, "#!/bin/bash\nprintf '%s\\n' \"$@\" > '" + arguments + "'\n");
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwxr-xr-x"));
        return script;
    }
}
