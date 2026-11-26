package com.meterian.factory.nativeprogram;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** Starts the native program target/rossumworks. Only the Maven profile native runs this test. */
class NativeProgramIT {

    private static final Path PROGRAM = Path.of(System.getProperty("native.program", "target/rossumworks"));
    private static final String PROJECT_VERSION = System.getProperty("project.version");
    private static final long TIMEOUT_SECONDS = 60;

    /** The exit code and the text of a run. The text has the output and the errors together. */
    private record ProgramResult(int exitCode, String text) {
    }

    @Test
    void showsTheVersionOfTheProject() throws Exception {
        ProgramResult result = runProgram("--version");

        assertThat(result.exitCode()).isEqualTo(0);
        assertThat(result.text()).isEqualTo("RossumWorks " + PROJECT_VERSION + "\n");
    }

    @Test
    void passesTheSelfCheck() throws Exception {
        ProgramResult result = runProgram("self-check");

        assertThat(result.text()).contains("ok: status comment", "ok: findings", "ok: password", "ok: board")
                .endsWith("The self-check passed.\n").doesNotContain("failed:");
        assertThat(result.exitCode()).isEqualTo(0);
    }

    @Test
    void withoutACommandShowsTheUsage() throws Exception {
        ProgramResult result = runProgram();

        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.text()).startsWith("Usage: rossumworks run [--config <file>]");
    }

    @Test
    void readsTheConfigurationFile() throws Exception {
        ProgramResult result = runProgram("run", "--config", "/no/such/worker.yaml");

        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.text()).contains("/no/such/worker.yaml");
    }

    private static ProgramResult runProgram(String... arguments) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add(PROGRAM.toAbsolutePath().toString());
        command.addAll(List.of(arguments));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        process.getOutputStream().close();
        String text = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new AssertionError("The native program did not end in " + TIMEOUT_SECONDS + " seconds.");
        }
        return new ProgramResult(process.exitValue(), text);
    }
}
