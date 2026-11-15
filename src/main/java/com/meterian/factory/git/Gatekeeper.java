package com.meterian.factory.git;

import com.meterian.factory.file.AtomicFileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * The gatekeeper is a local bare repository. Its hooks accept only factory branches and forward them to GitHub. A
 * forward replaces the branch on GitHub only when the branch is at the commit that the gatekeeper expects: the
 * commit of its remote-tracking branch. Thus a commit of another factory stays.
 */
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

    /**
     * A factory that continues the branch of another factory expects that branch on GitHub. The commit comes from
     * the branch in the clone, so the expectation is the commit that the worktree starts from.
     */
    public static void expectBranchOnGitHub(Path gatekeeperDirectory, Path clone, String branch) {
        new ProcessGit().run(gatekeeperDirectory, "fetch", "-q", clone.toAbsolutePath().toString(),
                "+refs/heads/" + branch + ":refs/remotes/" + GITHUB_REMOTE + "/" + branch);
    }

    private static void createParentDirectory(Path gatekeeperDirectory) {
        try {
            Files.createDirectories(gatekeeperDirectory.getParent());
        } catch (IOException exception) {
            throw new GitException("Cannot create the folder of the gatekeeper " + gatekeeperDirectory, exception);
        }
    }

    /** Writes the hook in one step, so that a push at the same time never sees a missing or a not executable hook. */
    private static void writeHook(Path gatekeeperDirectory, String hookName) {
        Path hookFile = gatekeeperDirectory.resolve("hooks").resolve(hookName);
        try {
            AtomicFileWriter.write(hookFile, readHookResource(hookName), "rwxr-xr-x");
        } catch (IOException exception) {
            throw new GitException("Cannot write the hook " + hookFile, exception);
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
}
