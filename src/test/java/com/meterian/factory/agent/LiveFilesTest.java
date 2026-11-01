package com.meterian.factory.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LiveFilesTest {

    private static final int ISSUE_NUMBER = 7;

    @TempDir
    Path temporaryDirectory;

    private LiveFiles liveFiles;

    @BeforeEach
    void createLiveFiles() {
        liveFiles = new LiveFiles(temporaryDirectory.resolve("live"));
        liveFiles.install();
    }

    @Test
    void installWritesThePluginAndAnExecutableHook() throws IOException {
        Path pluginDirectory = liveFiles.pluginDirectory();
        JsonNode manifest = new ObjectMapper().readTree(
                pluginDirectory.resolve(".claude-plugin/plugin.json").toFile());

        assertThat(manifest.get("name").asText()).isEqualTo("factory");
        assertThat(manifest.get("version").asText()).isEqualTo("1.0.0");
        assertThat(Files.readString(pluginDirectory.resolve("commands/continue.md")))
                .contains("description: Give the issue back to the factory");
        assertThat(temporaryDirectory.resolve("live/continue-hook.sh")).isExecutable();
    }

    @Test
    void installCreatesANewLiveDirectoryWithOwnerOnlyPermissions() throws IOException {
        assertThat(permissionsOf(temporaryDirectory.resolve("live"))).isEqualTo("rwx------");
    }

    @Test
    void installCreatesTheParentsOfANewLiveDirectoryWithOwnerOnlyPermissions() throws IOException {
        new LiveFiles(temporaryDirectory.resolve("parent/child/live")).install();

        assertThat(permissionsOf(temporaryDirectory.resolve("parent"))).isEqualTo("rwx------");
        assertThat(permissionsOf(temporaryDirectory.resolve("parent/child/live"))).isEqualTo("rwx------");
    }

    @Test
    void installRefusesALiveDirectoryThatTheGroupCanWrite() throws IOException {
        Path directory = Files.createDirectories(temporaryDirectory.resolve("shared"));
        Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwxrwx---"));

        assertThatThrownBy(() -> new LiveFiles(directory).install())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(directory.toString())
                .hasMessageContaining("chmod 700");
    }

    @Test
    void installRefusesALiveDirectoryThatEveryoneCanWrite() throws IOException {
        Path directory = Files.createDirectories(temporaryDirectory.resolve("open"));
        Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwxrwxrwx"));

        assertThatThrownBy(() -> new LiveFiles(directory).install())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(directory.toString())
                .hasMessageContaining("chmod 700");
    }

    @Test
    void installRefusesASymbolicLinkAsTheLiveDirectory() throws IOException {
        Path target = Files.createDirectories(temporaryDirectory.resolve("target"));
        Path link = Files.createSymbolicLink(temporaryDirectory.resolve("link"), target);

        assertThatThrownBy(() -> new LiveFiles(link).install())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("The live folder " + link + " is a symbolic link. Use a real folder.");
    }

    @Test
    void installRefusesAParentThatEveryoneCanWrite() throws IOException {
        Path parent = Files.createDirectories(temporaryDirectory.resolve("public-parent"));
        Files.setPosixFilePermissions(parent, PosixFilePermissions.fromString("rwxrwxrwx"));

        assertThatThrownBy(() -> new LiveFiles(parent.resolve("live")).install())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(parent.toString())
                .hasMessageContaining("live-directory in a private folder");
    }

    @Test
    void installRefusesAnExistingLiveDirectoryInAParentThatTheGroupCanWrite() throws IOException {
        Path parent = Files.createDirectories(temporaryDirectory.resolve("group-parent"));
        Files.createDirectory(parent.resolve("live"), PosixFilePermissions.asFileAttribute(
                PosixFilePermissions.fromString("rwx------")));
        Files.setPosixFilePermissions(parent, PosixFilePermissions.fromString("rwxrwx---"));

        assertThatThrownBy(() -> new LiveFiles(parent.resolve("live")).install())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(parent.toString());
    }

    @Test
    void installAcceptsAnExistingLiveDirectoryOfTheCurrentUser() throws IOException {
        Path directory = Files.createDirectory(temporaryDirectory.resolve("mine"), PosixFilePermissions
                .asFileAttribute(PosixFilePermissions.fromString("rwx------")));

        new LiveFiles(directory).install();

        assertThat(directory.resolve("continue-hook.sh")).isExecutable();
    }

    @Test
    void installTwiceKeepsTheFiles() {
        liveFiles.install();

        assertThat(temporaryDirectory.resolve("live/continue-hook.sh")).isExecutable();
    }

    @Test
    void theHookWritesTheMarkerAndBlocksTheContinueCommand() throws Exception {
        int exitCode = runHook("{\"prompt\":\"/factory:continue\"}");

        assertThat(exitCode).isEqualTo(2);
        assertThat(liveFiles.isContinueRequested(ISSUE_NUMBER)).isTrue();
    }

    @Test
    void theHookAcceptsASpaceAfterTheCommand() throws Exception {
        int exitCode = runHook("{\"prompt\":\"/factory:continue \"}");

        assertThat(exitCode).isEqualTo(2);
        assertThat(liveFiles.isContinueRequested(ISSUE_NUMBER)).isTrue();
    }

    @Test
    void theHookAcceptsBlanksBeforeTheCommand() throws Exception {
        int exitCode = runHook("{\"prompt\":\"  /factory:continue\"}");

        assertThat(exitCode).isEqualTo(2);
        assertThat(liveFiles.isContinueRequested(ISSUE_NUMBER)).isTrue();
    }

    @Test
    void theHookLetsAnotherPromptPass() throws Exception {
        int exitCode = runHook("{\"prompt\":\"What is the plan?\"}");

        assertThat(exitCode).isZero();
        assertThat(liveFiles.isContinueRequested(ISSUE_NUMBER)).isFalse();
    }

    @Test
    void theHookIgnoresTheCommandInsideASentence() throws Exception {
        int exitCode = runHook("{\"prompt\":\"please /factory:continue\"}");

        assertThat(exitCode).isZero();
        assertThat(liveFiles.isContinueRequested(ISSUE_NUMBER)).isFalse();
    }

    @Test
    void theHookLetsAnInputThatIsNotJsonPass() throws Exception {
        int exitCode = runHook("this is not JSON");

        assertThat(exitCode).isZero();
        assertThat(liveFiles.isContinueRequested(ISSUE_NUMBER)).isFalse();
    }

    @Test
    void theSettingsFileNamesTheHookAndTheMarkerOfTheIssue() throws IOException {
        Path settingsFile = liveFiles.settingsFile(ISSUE_NUMBER);

        JsonNode settings = new ObjectMapper().readTree(settingsFile.toFile());
        JsonNode hook = settings.at("/hooks/UserPromptSubmit/0/hooks/0");
        assertThat(hook.get("type").asText()).isEqualTo("command");
        assertThat(hook.get("command").asText()).isEqualTo("'" + temporaryDirectory.resolve("live/continue-hook.sh")
                + "' '" + liveFiles.continueMarker(ISSUE_NUMBER) + "'");
        assertThat(liveFiles.continueMarker(ISSUE_NUMBER).getFileName().toString())
                .isEqualTo("issue-7.continue");
    }

    @Test
    void theHookCommandOfTheSettingsWorksInADirectoryWithASpace() throws Exception {
        LiveFiles spacedLiveFiles = new LiveFiles(temporaryDirectory.resolve("live files"));
        spacedLiveFiles.install();
        JsonNode settings = new ObjectMapper().readTree(spacedLiveFiles.settingsFile(ISSUE_NUMBER).toFile());
        String hookCommand = settings.at("/hooks/UserPromptSubmit/0/hooks/0/command").asText();

        int exitCode = runInShell(hookCommand, "{\"prompt\":\"/factory:continue\"}");

        assertThat(exitCode).isEqualTo(2);
        assertThat(spacedLiveFiles.isContinueRequested(ISSUE_NUMBER)).isTrue();
    }

    @Test
    void theLaunchScriptOfTheIssueIsPrivateToTheOwner() throws IOException {
        Path script = liveFiles.writeLaunchScript(ISSUE_NUMBER, "#!/bin/bash\nexit 0\n");

        assertThat(script).isEqualTo(temporaryDirectory.resolve("live/issue-7.launch.sh"));
        assertThat(script).hasContent("#!/bin/bash\nexit 0");
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(script))).isEqualTo("rwx------");
    }

    @Test
    void clearContinueRequestDeletesTheMarker() throws Exception {
        runHook("{\"prompt\":\"/factory:continue\"}");

        liveFiles.clearContinueRequest(ISSUE_NUMBER);

        assertThat(liveFiles.isContinueRequested(ISSUE_NUMBER)).isFalse();
    }

    private static String permissionsOf(Path directory) throws IOException {
        return PosixFilePermissions.toString(Files.getPosixFilePermissions(directory));
    }

    private int runHook(String standardInput) throws Exception {
        return runProcess(new ProcessBuilder("bash",
                temporaryDirectory.resolve("live/continue-hook.sh").toString(),
                liveFiles.continueMarker(ISSUE_NUMBER).toString()), standardInput);
    }

    private int runInShell(String command, String standardInput) throws Exception {
        return runProcess(new ProcessBuilder("sh", "-c", command), standardInput);
    }

    private int runProcess(ProcessBuilder builder, String standardInput) throws Exception {
        Process process = builder.redirectErrorStream(true).start();
        process.getOutputStream().write(standardInput.getBytes(StandardCharsets.UTF_8));
        process.getOutputStream().close();
        process.getInputStream().readAllBytes();
        process.waitFor(30, TimeUnit.SECONDS);
        return process.exitValue();
    }
}
