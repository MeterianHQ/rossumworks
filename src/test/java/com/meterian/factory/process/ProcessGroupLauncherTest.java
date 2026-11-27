package com.meterian.factory.process;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

/** Linux has perl too, so the tests of the way of macOS run on the two systems. */
class ProcessGroupLauncherTest {

    private static final String PATH = System.getenv("PATH");
    private static final String MISSING_LOCALE = "C.utf8";
    private static final String PRINT_PROCESS_GROUP = "ps -o pgid= -p $$";
    private static final ProcessGroupLauncher LINUX_LAUNCHER = new ProcessGroupLauncher(OperatingSystem.LINUX);
    private static final ProcessGroupLauncher MACOS_LAUNCHER = new ProcessGroupLauncher(OperatingSystem.MACOS);

    private record EndedProcess(long processId, int exitCode, String output) {

        List<String> outputLines() {
            return output.lines().toList();
        }
    }

    @Test
    void linuxNeedsSetsidAndMacosNeedsPerl() {
        assertThat(LINUX_LAUNCHER.program()).isEqualTo("setsid");
        assertThat(MACOS_LAUNCHER.program()).isEqualTo("perl");
    }

    @Test
    void onLinuxSetsidStartsTheCommandWithTheGivenEnvironment() {
        ProcessBuilder builder = LINUX_LAUNCHER.prepare(List.of("sh", "-c", "true"), Map.of("LANG", MISSING_LOCALE));

        assertThat(builder.command()).containsExactly("setsid", "--wait", "sh", "-c", "true");
        assertThat(builder.environment()).isEqualTo(Map.of("LANG", MISSING_LOCALE));
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void onLinuxTheCommandLeadsItsProcessGroup() throws Exception {
        EndedProcess process = run(LINUX_LAUNCHER, List.of("sh", "-c", PRINT_PROCESS_GROUP), Map.of("PATH", PATH));

        assertThat(process.output().strip()).isEqualTo(Long.toString(process.processId()));
    }

    @Test
    void onMacosTheCommandLeadsItsProcessGroup() throws Exception {
        EndedProcess process = run(MACOS_LAUNCHER, List.of("sh", "-c", PRINT_PROCESS_GROUP), Map.of("PATH", PATH));

        assertThat(process.output().strip()).isEqualTo(Long.toString(process.processId()));
    }

    @Test
    void onMacosPerlStartsWithALocaleThatTheSystemDoesNotHave() throws Exception {
        EndedProcess process = run(MACOS_LAUNCHER, List.of("sh", "-c", "echo started"),
                Map.of("PATH", PATH, "LANG", MISSING_LOCALE));

        assertThat(process.exitCode()).isZero();
        assertThat(process.output()).isEqualTo("started\n");
    }

    @Test
    void onMacosTheCommandGetsTheLocaleOfTheEnvironment() throws Exception {
        EndedProcess process = run(MACOS_LAUNCHER, List.of("env"),
                Map.of("PATH", PATH, "LANG", MISSING_LOCALE, "LC_ALL", "en_US.UTF-8"));

        assertThat(process.outputLines())
                .containsExactlyInAnyOrder("PATH=" + PATH, "LANG=" + MISSING_LOCALE, "LC_ALL=en_US.UTF-8");
    }

    @Test
    void onMacosTheCommandGetsNoLocaleThatTheEnvironmentDoesNotHave() throws Exception {
        EndedProcess process = run(MACOS_LAUNCHER, List.of("env"), Map.of("PATH", PATH, "LANG", MISSING_LOCALE));

        assertThat(process.outputLines()).containsExactlyInAnyOrder("PATH=" + PATH, "LANG=" + MISSING_LOCALE);
    }

    @Test
    void onMacosTheExitCodeIsTheExitCodeOfTheCommand() throws Exception {
        EndedProcess process = run(MACOS_LAUNCHER, List.of("sh", "-c", "exit 7"), Map.of("PATH", PATH));

        assertThat(process.exitCode()).isEqualTo(7);
    }

    @Test
    void onMacosAMissingCommandGivesTheExitCodeOfTheShell() throws Exception {
        EndedProcess process = run(MACOS_LAUNCHER, List.of("no-such-command-of-the-factory"), Map.of("PATH", PATH));

        assertThat(process.exitCode()).isEqualTo(127);
        assertThat(process.output()).startsWith("Cannot run no-such-command-of-the-factory: ");
    }

    private static EndedProcess run(ProcessGroupLauncher launcher, List<String> command,
                                    Map<String, String> environment) throws IOException, InterruptedException {
        ProcessBuilder builder = launcher.prepare(command, environment);
        builder.redirectErrorStream(true);
        builder.redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null")));
        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        return new EndedProcess(process.pid(), process.waitFor(), output);
    }
}
