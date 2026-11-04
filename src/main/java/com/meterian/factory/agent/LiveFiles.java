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
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.LocalDate;
import java.util.List;
import java.util.regex.Pattern;

/** The files of live mode: the factory plugin, the continue hook, and the settings and marker of each issue. */
public final class LiveFiles {

    private static final String HOOK_FILE_NAME = "continue-hook.sh";
    private static final Pattern CONTINUE_MARKER_NAME = Pattern.compile("issue-\\d+\\.continue");
    private static final String FILE_PERMISSIONS = "rw-r--r--";
    private static final String EXECUTABLE_PERMISSIONS = "rwxr-xr-x";
    private static final int STICKY_BIT = 01000;
    private static final int GROUP_WRITE_BIT = 0020;
    private static final int OTHERS_WRITE_BIT = 0002;
    private static final int ROOT_USER_ID = 0;
    private static final String PRIVATE_FILE_PERMISSIONS = "rw-------";
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
            if (Files.isSymbolicLink(liveDirectory)) {
                throw new IllegalStateException(
                        "The live folder " + liveDirectory + " is a symbolic link. Use a real folder.");
            }
            verifyParentsArePrivate();
            if (Files.exists(liveDirectory, LinkOption.NOFOLLOW_LINKS)) {
                verifyLiveDirectoryIsPrivate();
            } else {
                createPrivateDirectories(liveDirectory);
            }
        } catch (IOException exception) {
            throw new UncheckedIOException("Cannot prepare the live folder " + liveDirectory, exception);
        }
    }

    /** Another user must not rename or replace the live folder, or a folder above it. A link is judged by its target. */
    private void verifyParentsArePrivate() throws IOException {
        ProcessIdentity identity = ProcessIdentity.current();
        for (Path parent = findNearestExistingParent().toRealPath(); parent != null; parent = parent.getParent()) {
            int mode = (Integer) Files.getAttribute(parent, "unix:mode", LinkOption.NOFOLLOW_LINKS);
            int ownerId = (Integer) Files.getAttribute(parent, "unix:uid", LinkOption.NOFOLLOW_LINKS);
            int groupId = (Integer) Files.getAttribute(parent, "unix:gid", LinkOption.NOFOLLOW_LINKS);
            String groupName = Files.readAttributes(parent, PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS)
                    .group().getName();
            if (!isSafeParent(mode, ownerId, groupId, groupName, identity)) {
                throw new IllegalStateException(describeUnsafeParent(parent, mode, groupName));
            }
        }
    }

    private Path findNearestExistingParent() {
        Path parent = liveDirectory.getParent();
        while (!Files.exists(parent)) {
            parent = parent.getParent();
        }
        return parent;
    }

    private static String describeUnsafeParent(Path parent, int mode, String groupName) {
        String writer = (mode & OTHERS_WRITE_BIT) != 0 ? "others" : "the group " + groupName;
        return "The folder " + parent + " can be written by " + writer + ". Run: chmod go-w " + parent
                + ", or choose a live-directory in a private folder.";
    }

    /**
     * A folder passes when the group and others cannot write to it. It also passes when it is sticky and belongs
     * to root or to the user, or when only the private group of the user can write to it.
     */
    static boolean isSafeParent(int mode, int ownerId, int groupId, String groupName, ProcessIdentity identity) {
        boolean isWritableByOthers = (mode & OTHERS_WRITE_BIT) != 0;
        boolean isWritableByGroup = (mode & GROUP_WRITE_BIT) != 0;
        if (!isWritableByOthers && !isWritableByGroup) {
            return true;
        }
        boolean isSticky = (mode & STICKY_BIT) != 0;
        if (isSticky && (ownerId == ROOT_USER_ID || ownerId == identity.userId())) {
            return true;
        }
        boolean hasPrivateGroup = ownerId == identity.userId() && groupId == identity.groupId()
                && groupName.equals(identity.userName());
        return !isWritableByOthers && hasPrivateGroup;
    }

    /** The user and the group of the process, read once from a file that the process creates. */
    record ProcessIdentity(int userId, int groupId, String userName) {

        private static ProcessIdentity current;

        static synchronized ProcessIdentity current() throws IOException {
            if (current == null) {
                Path probe = Files.createTempFile("factory-owner-probe", ".tmp");
                try {
                    current = new ProcessIdentity((Integer) Files.getAttribute(probe, "unix:uid"),
                            (Integer) Files.getAttribute(probe, "unix:gid"), System.getProperty("user.name"));
                } finally {
                    Files.deleteIfExists(probe);
                }
            }
            return current;
        }
    }

    private void verifyLiveDirectoryIsPrivate() throws IOException {
        if (isWritableByGroupOrOthers(liveDirectory) || !isOwnedByCurrentUser(liveDirectory)) {
            throw new IllegalStateException("The live folder " + liveDirectory + " must belong to the current user"
                    + " and must not be writable by the group or by others. Run: chmod 700 " + liveDirectory);
        }
    }

    private static boolean isWritableByGroupOrOthers(Path directory) throws IOException {
        return Files.getPosixFilePermissions(directory, LinkOption.NOFOLLOW_LINKS).stream()
                .anyMatch(permission -> permission == PosixFilePermission.GROUP_WRITE
                        || permission == PosixFilePermission.OTHERS_WRITE);
    }

    private static boolean isOwnedByCurrentUser(Path directory) throws IOException {
        return Files.getAttribute(directory, "unix:uid", LinkOption.NOFOLLOW_LINKS)
                .equals(ProcessIdentity.current().userId());
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
    public Path writeSettingsFile(int issueNumber) {
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

    static boolean isContinueMarkerName(String fileName) {
        return CONTINUE_MARKER_NAME.matcher(fileName).matches();
    }

    public boolean isContinueRequested(int issueNumber) {
        return Files.exists(continueMarker(issueNumber));
    }

    /** Writes the marker that the hook writes. Only the owner can read it. */
    public void requestContinue(int issueNumber) {
        write(continueMarker(issueNumber), LocalDate.now().toString().getBytes(StandardCharsets.UTF_8),
                PRIVATE_FILE_PERMISSIONS);
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
