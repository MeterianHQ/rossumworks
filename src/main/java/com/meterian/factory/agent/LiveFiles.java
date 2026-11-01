package com.meterian.factory.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.meterian.factory.file.AtomicFileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;

/** The files of live mode: the factory plugin, the continue hook, and the settings and marker of each issue. */
public final class LiveFiles {

    private static final String HOOK_FILE_NAME = "continue-hook.sh";
    private static final String FILE_PERMISSIONS = "rw-r--r--";
    private static final String EXECUTABLE_PERMISSIONS = "rwxr-xr-x";
    private static final String PRIVATE_DIRECTORY_PERMISSIONS = "rwx------";
    private static final String PRIVATE_EXECUTABLE_PERMISSIONS = "rwx------";

    private final Path liveDirectory;

    public LiveFiles(Path liveDirectory) {
        this.liveDirectory = liveDirectory.toAbsolutePath();
    }

    /** Fails closed when another user could replace a launch script in the live folder. */
    public void install() {
        prepareLiveDirectory();
        writeResource("plugin.json", pluginDirectory().resolve(".claude-plugin/plugin.json"), FILE_PERMISSIONS);
        writeResource("continue.md", pluginDirectory().resolve("commands/continue.md"), FILE_PERMISSIONS);
        writeResource(HOOK_FILE_NAME, hookFile(), EXECUTABLE_PERMISSIONS);
    }

    private void prepareLiveDirectory() {
        try {
            if (Files.exists(liveDirectory)) {
                verifyLiveDirectoryIsPrivate();
            } else {
                createPrivateDirectories(liveDirectory);
            }
        } catch (IOException exception) {
            throw new UncheckedIOException("Cannot prepare the live folder " + liveDirectory, exception);
        }
    }

    private void verifyLiveDirectoryIsPrivate() throws IOException {
        boolean isWritableByOthers = Files.getPosixFilePermissions(liveDirectory).stream()
                .anyMatch(permission -> permission == PosixFilePermission.GROUP_WRITE
                        || permission == PosixFilePermission.OTHERS_WRITE);
        boolean isOwnedByAnotherUser = !Files.getOwner(liveDirectory).getName()
                .equals(System.getProperty("user.name"));
        if (isWritableByOthers || isOwnedByAnotherUser) {
            throw new IllegalStateException("The live folder " + liveDirectory + " must belong to the current user"
                    + " and must not be writable by the group or by others. Run: chmod 700 " + liveDirectory);
        }
    }

    /** Each folder that does not exist yet gets the mode 0700, also the parents. */
    private static void createPrivateDirectories(Path directory) throws IOException {
        Path parent = directory.getParent();
        if (parent != null && !Files.exists(parent)) {
            createPrivateDirectories(parent);
        }
        Files.createDirectory(directory, PosixFilePermissions.asFileAttribute(
                PosixFilePermissions.fromString(PRIVATE_DIRECTORY_PERMISSIONS)));
    }

    public Path pluginDirectory() {
        return liveDirectory.resolve("plugin");
    }

    /** Writes the settings file of the issue and returns its path. */
    public Path settingsFile(int issueNumber) {
        Path file = liveDirectory.resolve("issue-" + issueNumber + ".settings.json");
        write(file, createSettings(issueNumber), FILE_PERMISSIONS);
        return file;
    }

    /** Writes the launch script of the issue and returns its path. Only the owner can read it: it holds the environment. */
    public Path writeLaunchScript(int issueNumber, String content) {
        Path file = liveDirectory.resolve("issue-" + issueNumber + ".launch.sh");
        write(file, content.getBytes(StandardCharsets.UTF_8), PRIVATE_EXECUTABLE_PERMISSIONS);
        return file;
    }

    /** The marker is outside the worktree, so the agent does not see it in its folder. */
    public Path continueMarker(int issueNumber) {
        return liveDirectory.resolve("issue-" + issueNumber + ".continue");
    }

    public boolean isContinueRequested(int issueNumber) {
        return Files.exists(continueMarker(issueNumber));
    }

    public void clearContinueRequest(int issueNumber) {
        try {
            Files.deleteIfExists(continueMarker(issueNumber));
        } catch (IOException exception) {
            throw new UncheckedIOException("Cannot delete the marker " + continueMarker(issueNumber), exception);
        }
    }

    private Path hookFile() {
        return liveDirectory.resolve(HOOK_FILE_NAME);
    }

    private byte[] createSettings(int issueNumber) {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode hook = mapper.createObjectNode();
        hook.put("type", "command");
        hook.put("command", ShellQuoting.join(List.of(hookFile().toString(), continueMarker(issueNumber).toString())));
        ObjectNode group = mapper.createObjectNode();
        group.set("hooks", mapper.createArrayNode().add(hook));
        ArrayNode groups = mapper.createArrayNode().add(group);
        ObjectNode root = mapper.createObjectNode();
        root.set("hooks", mapper.createObjectNode().set("UserPromptSubmit", groups));
        try {
            return mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(root);
        } catch (IOException exception) {
            throw new UncheckedIOException("Cannot create the settings of the issue " + issueNumber, exception);
        }
    }

    private void writeResource(String resourceName, Path file, String permissions) {
        write(file, readResource(resourceName), permissions);
    }

    private void write(Path file, byte[] content, String permissions) {
        try {
            AtomicFileWriter.write(file, content, permissions);
        } catch (IOException exception) {
            throw new UncheckedIOException("Cannot write the live file " + file, exception);
        }
    }

    private byte[] readResource(String resourceName) {
        try (InputStream source = LiveFiles.class.getResourceAsStream("/live/" + resourceName)) {
            if (source == null) {
                throw new IllegalStateException("The resource " + resourceName + " is missing");
            }
            return source.readAllBytes();
        } catch (IOException exception) {
            throw new UncheckedIOException("Cannot read the resource " + resourceName, exception);
        }
    }
}
