package com.meterian.factory.git;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;

/** The gatekeeper is a local bare repository. Its hooks accept only factory branches and forward them to GitHub. */
public final class Gatekeeper {

    private static final String GITHUB_REMOTE = "github";
    private static final List<String> HOOK_NAMES = List.of("pre-receive", "post-receive");

    private Gatekeeper() {
    }

    public static void ensure(Path gatekeeperDirectory, String githubUrl) {
        if (!Files.isDirectory(gatekeeperDirectory)) {
            createParentDirectory(gatekeeperDirectory);
            ProcessGit.run(gatekeeperDirectory.getParent(), "init", "-q", "--bare", gatekeeperDirectory.toString());
        }
        new ProcessGit().setRemote(gatekeeperDirectory, GITHUB_REMOTE, githubUrl);
        HOOK_NAMES.forEach(hookName -> writeHook(gatekeeperDirectory, hookName));
    }

    private static void createParentDirectory(Path gatekeeperDirectory) {
        try {
            Files.createDirectories(gatekeeperDirectory.getParent());
        } catch (IOException exception) {
            throw new GitException("Cannot create the folder of the gatekeeper " + gatekeeperDirectory, exception);
        }
    }

    private static void writeHook(Path gatekeeperDirectory, String hookName) {
        Path hookFile = gatekeeperDirectory.resolve("hooks").resolve(hookName);
        try (InputStream source = Gatekeeper.class.getResourceAsStream("/gatekeeper/" + hookName)) {
            if (source == null) {
                throw new GitException("The resource of the hook " + hookName + " is missing");
            }
            Files.createDirectories(hookFile.getParent());
            Files.copy(source, hookFile, StandardCopyOption.REPLACE_EXISTING);
            Files.setPosixFilePermissions(hookFile, PosixFilePermissions.fromString("rwxr-xr-x"));
        } catch (IOException exception) {
            throw new GitException("Cannot write the hook " + hookFile, exception);
        }
    }
}
