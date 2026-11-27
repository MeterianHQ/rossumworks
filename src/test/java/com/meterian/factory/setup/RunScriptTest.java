package com.meterian.factory.setup;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Runs .factory/run.sh with a fake curl, a fake uname, and a fake java. The fake GitHub is a folder of assets.
 * On macOS, the bash of the system has the version 3.2, and the tools are the BSD tools.
 */
class RunScriptTest {

    private static final String VERSION = "0.6.0";
    private static final String JAR = "rossumworks-" + VERSION + ".jar";
    private static final String X64_PROGRAM = "rossumworks-" + VERSION + "-linux-x64";
    private static final String ARM64_PROGRAM = "rossumworks-" + VERSION + "-linux-arm64";
    private static final String MAC_PROGRAM = "rossumworks-" + VERSION + "-macos-arm64";
    private static final String NATIVE_TEXT = "#!/bin/sh\necho \"native $*\"\n";
    private static final String OLD_GLIBC_TEXT = "#!/bin/sh\necho \"GLIBC_2.34 not found\" >&2\nexit 1\n";
    private static final long TIMEOUT_SECONDS = 30;
    /** macOS has sha256sum in /sbin. */
    private static final String SYSTEM_PATH = "/usr/bin:/bin:/sbin";

    private static final String FAKE_CURL = """
            #!/usr/bin/env bash
            if [[ -n "${FAKE_CURL_FAILS:-}" ]]; then exit 22; fi
            output=""
            while [[ $# -gt 1 ]]; do
                if [[ "$1" == "--output" ]]; then output="$2"; shift; fi
                shift
            done
            url="$1"
            if [[ "${url}" == */releases/latest ]]; then cat "${FAKE_GITHUB}/release.json"; exit 0; fi
            asset="${FAKE_GITHUB}/assets/${url##*/}"
            if [[ ! -f "${asset}" ]]; then exit 22; fi
            if [[ -n "${output}" ]]; then cp "${asset}" "${output}"; else cat "${asset}"; fi
            """;
    private static final String FAKE_UNAME = """
            #!/bin/sh
            if [ "$1" = "-s" ]; then echo "${FAKE_SYSTEM:-Linux}"; else echo "${FAKE_MACHINE}"; fi
            """;
    private static final String FAKE_JAVA = """
            #!/bin/sh
            echo "java $*"
            """;
    private static final String FAKE_GH_WITHOUT_LOGIN = """
            #!/bin/sh
            exit 1
            """;

    @TempDir
    Path temporaryDirectory;

    private Path home;
    private Path fakeGitHub;
    private Path fakeCommands;
    private Path script;
    private final List<String> assetNames = new ArrayList<>();

    /** The exit code and the text of a run. The text has the output and the errors together. */
    private record ScriptResult(int exitCode, String text) {
    }

    @BeforeEach
    void prepareFolders() throws IOException {
        home = Files.createDirectories(temporaryDirectory.resolve("home"));
        fakeGitHub = Files.createDirectories(temporaryDirectory.resolve("github/assets")).getParent();
        fakeCommands = Files.createDirectories(temporaryDirectory.resolve("bin"));
        writeExecutable(fakeCommands.resolve("curl"), FAKE_CURL);
        writeExecutable(fakeCommands.resolve("uname"), FAKE_UNAME);
        writeExecutable(fakeCommands.resolve("java"), FAKE_JAVA);
        script = Files.createDirectories(temporaryDirectory.resolve("clone/.factory")).resolve("run.sh");
        writeExecutable(script, readRunScript());
    }

    @Test
    void startsTheNativeProgramOfAnX64Machine() throws Exception {
        publishNativeProgramWithChecksum(X64_PROGRAM);
        publishNativeProgramWithChecksum(ARM64_PROGRAM);
        publishAsset(JAR, "jar");

        ScriptResult result = runScript("x86_64", "--config", "worker.yaml");

        assertThat(result.text()).contains("Download of " + X64_PROGRAM, "native run --config worker.yaml")
                .doesNotContain("java");
        assertThat(result.exitCode()).isEqualTo(0);
        assertThat(nativeCache().resolve(X64_PROGRAM)).isExecutable();
        assertThat(nativeCache().resolve(X64_PROGRAM + ".partial.sha256")).doesNotExist();
    }

    @Test
    void startsTheNativeProgramOfAnArm64Machine() throws Exception {
        publishNativeProgramWithChecksum(X64_PROGRAM);
        publishNativeProgramWithChecksum(ARM64_PROGRAM);

        ScriptResult result = runScript("aarch64");

        assertThat(result.text()).contains("Download of " + ARM64_PROGRAM, "native run");
        assertThat(result.exitCode()).isEqualTo(0);
    }

    @Test
    void aWrongChecksumStopsTheScriptAndKeepsNoProgram() throws Exception {
        publishAsset(X64_PROGRAM, NATIVE_TEXT);
        publishAsset(X64_PROGRAM + ".sha256", "0000  " + X64_PROGRAM + "\n");
        publishAsset(JAR, "jar");

        ScriptResult result = runScript("x86_64");

        assertThat(result.text()).contains("The SHA-256 of " + X64_PROGRAM + " is not the SHA-256 of "
                + X64_PROGRAM + ".sha256.").doesNotContain("native run", "java");
        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(nativeCache()).isEmptyDirectory();
    }

    @Test
    void aNativeProgramWithoutAChecksumGivesTheJar() throws Exception {
        publishAsset(X64_PROGRAM, NATIVE_TEXT);
        publishAsset(JAR, "jar");

        ScriptResult result = runScript("x86_64");

        assertThat(result.text()).contains("The release has no checksum for " + X64_PROGRAM + ".",
                "java -jar " + jarCache().resolve(JAR) + " run");
        assertThat(result.exitCode()).isEqualTo(0);
    }

    @Test
    void aReleaseWithoutANativeProgramForTheMachineGivesTheJar() throws Exception {
        publishNativeProgramWithChecksum(X64_PROGRAM);
        publishAsset(JAR, "jar");

        ScriptResult result = runScript("riscv64");

        assertThat(result.text()).contains("Download of " + JAR, "java -jar " + jarCache().resolve(JAR) + " run")
                .doesNotContain(X64_PROGRAM);
        assertThat(result.exitCode()).isEqualTo(0);
    }

    @Test
    void startsTheNativeProgramOfAMacWithArm64() throws Exception {
        publishNativeProgramWithChecksum(ARM64_PROGRAM);
        publishNativeProgramWithChecksum(MAC_PROGRAM);
        publishAsset(JAR, "jar");

        ScriptResult result = runScript("arm64", Map.of("FAKE_SYSTEM", "Darwin"));

        assertThat(result.text()).contains("Download of " + MAC_PROGRAM, "native run")
                .doesNotContain(ARM64_PROGRAM, "java");
        assertThat(result.exitCode()).isEqualTo(0);
        assertThat(nativeCache().resolve(MAC_PROGRAM)).isExecutable();
    }

    @Test
    void aMacWithIntelGivesTheJar() throws Exception {
        publishNativeProgramWithChecksum(X64_PROGRAM);
        publishNativeProgramWithChecksum(MAC_PROGRAM);
        publishAsset(JAR, "jar");

        ScriptResult result = runScript("x86_64", Map.of("FAKE_SYSTEM", "Darwin"));

        assertThat(result.text()).contains("java -jar " + jarCache().resolve(JAR) + " run")
                .doesNotContain("native run");
        assertThat(result.exitCode()).isEqualTo(0);
    }

    @Test
    void aMachineWithoutATokenDownloadsTheJar() throws Exception {
        writeExecutable(fakeCommands.resolve("gh"), FAKE_GH_WITHOUT_LOGIN);
        publishAsset(JAR, "jar");

        ScriptResult result = runScript("arm64", Map.of("FAKE_SYSTEM", "Darwin", "FACTORY_GITHUB_TOKEN", ""));

        assertThat(result.text()).contains("Download of " + JAR, "java -jar " + jarCache().resolve(JAR) + " run");
        assertThat(result.exitCode()).isEqualTo(0);
    }

    @Test
    void aNativeProgramThatDoesNotStartGivesTheJar() throws Exception {
        publishAsset(X64_PROGRAM, OLD_GLIBC_TEXT);
        publishAsset(X64_PROGRAM + ".sha256", computeSha256(OLD_GLIBC_TEXT) + "  " + X64_PROGRAM + "\n");
        publishAsset(JAR, "jar");

        ScriptResult result = runScript("x86_64");

        assertThat(result.text()).contains("The native program " + nativeCache().resolve(X64_PROGRAM)
                + " does not start on this machine. The script uses the jar.",
                "java -jar " + jarCache().resolve(JAR) + " run").doesNotContain("GLIBC");
        assertThat(result.exitCode()).isEqualTo(0);
    }

    @Test
    void aCachedNativeProgramThatDoesNotStartGivesTheCachedJar() throws Exception {
        writeExecutable(Files.createDirectories(nativeCache()).resolve(X64_PROGRAM), OLD_GLIBC_TEXT);
        Files.writeString(Files.createDirectories(jarCache()).resolve("rossumworks-0.5.0.jar"), "jar");

        ScriptResult result = runScript("x86_64", Map.of("FAKE_CURL_FAILS", "yes"));

        assertThat(result.text()).contains("does not start on this machine",
                "java -jar " + jarCache().resolve("rossumworks-0.5.0.jar") + " run");
        assertThat(result.exitCode()).isEqualTo(0);
    }

    @Test
    void aFailedDownloadUsesTheNewestCachedNativeProgram() throws Exception {
        writeExecutable(Files.createDirectories(nativeCache()).resolve("rossumworks-0.5.9-linux-x64"), NATIVE_TEXT);
        writeExecutable(nativeCache().resolve("rossumworks-0.5.10-linux-x64"), "#!/bin/sh\necho \"newest $*\"\n");
        writeExecutable(nativeCache().resolve("rossumworks-0.5.11-linux-arm64"), NATIVE_TEXT);

        ScriptResult result = runScript("x86_64", Map.of("FAKE_CURL_FAILS", "yes"));

        assertThat(result.text()).contains("Cannot download the latest release.", "newest run");
        assertThat(result.exitCode()).isEqualTo(0);
    }

    @Test
    void aFailedDownloadWithoutACachedNativeProgramUsesTheNewestCachedJar() throws Exception {
        Files.writeString(Files.createDirectories(jarCache()).resolve("rossumworks-0.5.0.jar"), "jar");
        Files.writeString(jarCache().resolve("rossumworks-0.5.1.jar"), "jar");

        ScriptResult result = runScript("x86_64", Map.of("FAKE_CURL_FAILS", "yes"));

        assertThat(result.text()).contains("java -jar " + jarCache().resolve("rossumworks-0.5.1.jar") + " run");
        assertThat(result.exitCode()).isEqualTo(0);
    }

    @Test
    void anEmptyCacheAndAFailedDownloadStopTheScript() throws Exception {
        ScriptResult result = runScript("x86_64", Map.of("FAKE_CURL_FAILS", "yes"));

        assertThat(result.text()).contains("has no program. Download a program from "
                + "https://github.com/MeterianHQ/rossumworks/releases.");
        assertThat(result.exitCode()).isEqualTo(1);
    }

    @Test
    void aCachedNativeProgramNeedsNoNewDownload() throws Exception {
        publishNativeProgramWithChecksum(X64_PROGRAM);
        writeExecutable(Files.createDirectories(nativeCache()).resolve(X64_PROGRAM), "#!/bin/sh\necho \"cached $*\"\n");

        ScriptResult result = runScript("x86_64");

        assertThat(result.text()).contains("cached run").doesNotContain("Download of");
        assertThat(result.exitCode()).isEqualTo(0);
    }

    @Test
    void aMachineWithoutSha256sumChecksTheProgramWithShasum() throws Exception {
        publishNativeProgramWithChecksum(MAC_PROGRAM);
        writeExecutable(fakeCommands.resolve("shasum"), createFakeShasum(computeSha256(NATIVE_TEXT)));

        ScriptResult result = runScript("arm64", Map.of("FAKE_SYSTEM", "Darwin",
                "PATH", fakeCommands + ":" + linkSystemCommandsWithoutSha256sum()));

        assertThat(result.text()).contains("shasum -a 256 ", "native run");
        assertThat(result.exitCode()).isEqualTo(0);
    }

    private Path nativeCache() {
        return home.resolve(".rossumworks/native");
    }

    private Path jarCache() {
        return home.resolve(".rossumworks/jars");
    }

    /** A folder with a link to each command of SYSTEM_PATH, except sha256sum. Old macOS versions have no sha256sum. */
    private Path linkSystemCommandsWithoutSha256sum() throws IOException {
        Path systemCommands = Files.createDirectories(temporaryDirectory.resolve("system-without-sha256sum"));
        for (String directory : SYSTEM_PATH.split(":")) {
            try (Stream<Path> commands = Files.list(Path.of(directory))) {
                for (Path command : commands.toList()) {
                    linkCommand(systemCommands, command);
                }
            }
        }
        return systemCommands;
    }

    private static void linkCommand(Path systemCommands, Path command) throws IOException {
        String name = command.getFileName().toString();
        Path link = systemCommands.resolve(name);
        boolean isUsable = Files.isExecutable(command) && !Files.isDirectory(command);
        if (isUsable && !name.equals("sha256sum") && Files.notExists(link, LinkOption.NOFOLLOW_LINKS)) {
            Files.createSymbolicLink(link, command);
        }
    }

    /** A fake shasum shows its arguments, and prints the SHA-256 in the format of shasum. */
    private static String createFakeShasum(String sha256) {
        return "#!/bin/sh\necho \"shasum $*\" >&2\necho \"" + sha256 + "  $3\"\n";
    }

    private void publishNativeProgramWithChecksum(String name) throws IOException, NoSuchAlgorithmException {
        publishAsset(name, NATIVE_TEXT);
        publishAsset(name + ".sha256", computeSha256(NATIVE_TEXT) + "  " + name + "\n");
    }

    /** The fake GitHub serves the asset at a URL that ends with its name, and lists it in the latest release. */
    private void publishAsset(String name, String text) throws IOException {
        Files.writeString(fakeGitHub.resolve("assets").resolve(name), text);
        assetNames.add(name);
        String assets = String.join(",", assetNames.stream()
                .map(asset -> "{\"name\": \"" + asset + "\", \"url\": \"https://api.github.com/assets/" + asset + "\"}")
                .toList());
        Files.writeString(fakeGitHub.resolve("release.json"), "{\"assets\": [" + assets + "]}");
    }

    private ScriptResult runScript(String machine, String... arguments) throws Exception {
        return runScript(machine, Map.of(), arguments);
    }

    private ScriptResult runScript(String machine, Map<String, String> variables, String... arguments)
            throws Exception {
        List<String> command = new ArrayList<>(List.of("bash", script.toString()));
        command.addAll(List.of(arguments));
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        Map<String, String> environment = builder.environment();
        environment.clear();
        environment.put("PATH", fakeCommands + ":" + SYSTEM_PATH);
        environment.put("HOME", home.toString());
        environment.put("FAKE_GITHUB", fakeGitHub.toString());
        environment.put("FAKE_MACHINE", machine);
        environment.put("FACTORY_GITHUB_TOKEN", "token");
        environment.putAll(variables);
        Process process = builder.start();
        process.getOutputStream().close();
        String text = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new AssertionError("run.sh did not end in " + TIMEOUT_SECONDS + " seconds.");
        }
        return new ScriptResult(process.exitValue(), text);
    }

    private static String readRunScript() throws IOException {
        try (InputStream stream = RunScriptTest.class.getResourceAsStream("/setup/run.sh")) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static void writeExecutable(Path file, String text) throws IOException {
        Files.writeString(file, text);
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwxr-xr-x"));
    }

    private static String computeSha256(String text) throws NoSuchAlgorithmException {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(digest);
    }
}
