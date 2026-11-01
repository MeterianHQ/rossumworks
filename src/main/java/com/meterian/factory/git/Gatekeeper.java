package com.meterian.factory.git;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Arrays;
import java.util.List;

/** The gatekeeper is a local bare repository. Its hooks accept only factory branches and forward them to GitHub. */
public final class Gatekeeper {

    private static final String GITHUB_REMOTE = "github";
    private static final List<String> HOOK_NAMES = List.of("pre-receive", "post-receive");

    private Gatekeeper() {
    }

    public static void ensure(Path gatekeeperDirectory, String githubUrl) {
        ProcessGit git = new ProcessGit();
        if (!Files.isDirectory(gatekeeperDirectory)) {
            createParentDirectory(gatekeeperDirectory);
            git.run(gatekeeperDirectory.getParent(), "init", "-q", "--bare", gatekeeperDirectory.toString());
        }
        git.setRemote(gatekeeperDirectory, GITHUB_REMOTE, githubUrl);
        Path hooksDirectory = gatekeeperDirectory.toAbsolutePath().resolve("hooks");
        git.run(gatekeeperDirectory, "config", "core.hooksPath", hooksDirectory.toString());
        HOOK_NAMES.forEach(hookName -> writeHook(gatekeeperDirectory, hookName));
    }

    private static void createParentDirectory(Path gatekeeperDirectory) {
        try {
            Files.createDirectories(gatekeeperDirectory.getParent());
        } catch (IOException exception) {
            throw new GitException("Cannot create the folder of the gatekeeper " + gatekeeperDirectory, exception);
        }
    }

    /**
     * Writes the hook to a temporary file and moves it in place in one step, so that a push at the same time
     * never sees a missing or a not executable hook. An up-to-date hook stays as it is.
     */
    private static void writeHook(Path gatekeeperDirectory, String hookName) {
        Path hooksDirectory = gatekeeperDirectory.resolve("hooks");
        Path hookFile = hooksDirectory.resolve(hookName);
        byte[] content = readHookResource(hookName);
        if (isUpToDate(hookFile, content)) {
            return;
        }
        Path temporaryFile = null;
        try {
            Files.createDirectories(hooksDirectory);
            temporaryFile = Files.createTempFile(hooksDirectory, "." + hookName + "-", ".tmp");
            Files.write(temporaryFile, content);
            Files.setPosixFilePermissions(temporaryFile, PosixFilePermissions.fromString("rwxr-xr-x"));
            Files.move(temporaryFile, hookFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException exception) {
            throw new GitException("Cannot write the hook " + hookFile, exception);
        } finally {
            deleteIfPresent(temporaryFile);
        }
    }

    private static byte[] readHookResource(String hookName) {
        try (InputStream source = Gatekeeper.class.getResourceAsStream("/gatekeeper/" + hookName)) {
            if (source == null) {
                throw new GitException("The resource of the hook " + hookName + " is missing");
            }
            return source.readAllBytes();
        } catch (IOException exception) {
            throw new GitException("Cannot read the resource of the hook " + hookName, exception);
        }
    }

    private static boolean isUpToDate(Path hookFile, byte[] content) {
        try {
            return Files.isRegularFile(hookFile) && Files.isExecutable(hookFile)
                    && Arrays.equals(Files.readAllBytes(hookFile), content);
        } catch (IOException exception) {
            return false;
        }
    }

    /**
     * After a successful move the temporary file is gone, so this deletes only what a failure leaves. A file
     * that stays does no harm: git runs only the hooks with the exact names.
     */
    private static void deleteIfPresent(Path temporaryFile) {
        if (temporaryFile == null) {
            return;
        }
        try {
            Files.deleteIfExists(temporaryFile);
        } catch (IOException ignored) {
            // The first failure is the one to report, so this one stays quiet.
        }
    }
}
