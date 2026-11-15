package com.meterian.factory.git;

import static com.meterian.factory.testing.TemporaryRepository.commitFile;
import static com.meterian.factory.testing.TemporaryRepository.createBare;
import static com.meterian.factory.testing.TemporaryRepository.createClone;
import static com.meterian.factory.testing.TemporaryRepository.run;
import static com.meterian.factory.testing.TemporaryRepository.runWithEnvironment;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GatekeeperTest {

    private static final String BRANCH = "factory/issue-7";

    @TempDir
    Path temporaryDirectory;

    private final Git git = new ProcessGit();
    private Path github;
    private Path gatekeeper;
    private Path clone;

    @BeforeEach
    void createRepositories() {
        github = createBare(temporaryDirectory.resolve("github.git"));
        gatekeeper = temporaryDirectory.resolve("gatekeeper.git");
        Gatekeeper.ensure(gatekeeper, github.toString());
        clone = createClone(createBare(temporaryDirectory.resolve("origin.git")), temporaryDirectory.resolve("clone"));
        git.setRemote(clone, "gatekeeper", gatekeeper.toString());
    }

    @Test
    void acceptsAndForwardsAFactoryBranch() {
        git.push(clone, "gatekeeper", "factory/issue-7");

        String expected = run(clone, "rev-parse", "HEAD");
        assertThat(run(github, "rev-parse", "refs/heads/factory/issue-7")).isEqualTo(expected);
    }

    /** Another factory clones GitHub, adds a commit to the branch, and pushes it to GitHub. */
    private String pushACommitOfAnotherFactory() {
        Path otherClone = temporaryDirectory.resolve("other-factory");
        run(temporaryDirectory, "clone", "-q", github.toString(), otherClone.toString());
        run(otherClone, "config", "user.name", "Other Factory");
        run(otherClone, "config", "user.email", "other-factory@example.com");
        if (!run(otherClone, "ls-remote", "origin", BRANCH).isBlank()) {
            run(otherClone, "checkout", "-q", "-B", "work", "origin/" + BRANCH);
        }
        commitFile(otherClone, "other.txt", "other\n", "Change of another factory");
        run(otherClone, "push", "-q", "origin", "HEAD:refs/heads/" + BRANCH);
        return run(otherClone, "rev-parse", "HEAD");
    }

    private String headOnGitHub() {
        return run(github, "rev-parse", "refs/heads/" + BRANCH);
    }

    @Test
    void forwardKeepsACommitThatAnotherFactoryPushedToGitHub() {
        git.push(clone, "gatekeeper", BRANCH);
        String otherCommit = pushACommitOfAnotherFactory();
        commitFile(clone, "b.txt", "factory\n", "Change of the factory");

        git.push(clone, "gatekeeper", BRANCH);

        assertThat(headOnGitHub()).isEqualTo(otherCommit);
    }

    @Test
    void newForwardKeepsACommitThatAnotherFactoryPushedToGitHub() {
        git.push(clone, "gatekeeper", BRANCH);
        String otherCommit = pushACommitOfAnotherFactory();
        commitFile(clone, "b.txt", "factory\n", "Change of the factory");
        git.push(clone, "gatekeeper", BRANCH);
        String factoryCommit = run(clone, "rev-parse", "HEAD").trim();

        assertThatThrownBy(() -> git.forwardCommit(gatekeeper, "github", factoryCommit, BRANCH))
                .isInstanceOf(GitException.class);
        assertThat(headOnGitHub()).isEqualTo(otherCommit);
    }

    @Test
    void forwardMakesTheBranchAgainWhenGitHubDeletedIt() {
        git.push(clone, "gatekeeper", BRANCH);
        run(github, "update-ref", "-d", "refs/heads/" + BRANCH);
        commitFile(clone, "b.txt", "factory\n", "Change of the factory");

        git.push(clone, "gatekeeper", BRANCH);

        assertThat(headOnGitHub()).isEqualTo(run(clone, "rev-parse", "HEAD"));
    }

    @Test
    void newForwardMakesTheBranchAgainWhenGitHubDeletedIt() {
        git.push(clone, "gatekeeper", BRANCH);
        run(github, "update-ref", "-d", "refs/heads/" + BRANCH);
        String factoryCommit = run(clone, "rev-parse", "HEAD").trim();

        git.forwardCommit(gatekeeper, "github", factoryCommit, BRANCH);

        assertThat(headOnGitHub().trim()).isEqualTo(factoryCommit);
    }

    @Test
    void forwardReplacesTheOwnEarlierPush() {
        git.push(clone, "gatekeeper", BRANCH);
        run(clone, "commit", "-q", "--amend", "-m", "Rewritten commit");

        git.push(clone, "gatekeeper", BRANCH);

        assertThat(headOnGitHub()).isEqualTo(run(clone, "rev-parse", "HEAD"));
    }

    @Test
    void forwardContinuesTheBranchThatTheFactoryStartsFrom() {
        pushACommitOfAnotherFactory();
        run(clone, "fetch", "-q", github.toString(), "refs/heads/" + BRANCH + ":refs/heads/" + BRANCH);
        run(clone, "checkout", "-q", BRANCH);
        Gatekeeper.expectBranchOnGitHub(gatekeeper, clone, BRANCH);
        commitFile(clone, "b.txt", "factory\n", "Change of the factory");

        git.push(clone, "gatekeeper", BRANCH);

        assertThat(headOnGitHub()).isEqualTo(run(clone, "rev-parse", "HEAD"));
    }

    @Test
    void refusesAPushToMainAfterAnAcceptedFactoryPush() {
        git.push(clone, "gatekeeper", "factory/issue-7");

        assertThatThrownBy(() -> run(clone, "push", "gatekeeper", "HEAD:refs/heads/main"))
                .isInstanceOf(GitException.class);

        assertThat(run(github, "for-each-ref", "--format=%(refname)")).isEqualTo("refs/heads/factory/issue-7");
    }

    @Test
    void refusesAPushToATag() {
        assertThatThrownBy(() -> run(clone, "push", "gatekeeper", "HEAD:refs/tags/v1"))
                .isInstanceOf(GitException.class);

        assertThat(run(github, "tag", "--list")).isEmpty();
        assertThat(run(gatekeeper, "tag", "--list")).isEmpty();
    }

    @Test
    void refusesTheDeleteOfAFactoryBranch() {
        git.push(clone, "gatekeeper", "factory/issue-7");

        assertThatThrownBy(() -> run(clone, "push", "gatekeeper", ":refs/heads/factory/issue-7"))
                .isInstanceOf(GitException.class);
        assertThat(run(github, "rev-parse", "--verify", "refs/heads/factory/issue-7")).isNotBlank();
    }

    @Test
    void refusesTheDeleteOfMain() {
        git.push(clone, "gatekeeper", "factory/issue-7");
        run(gatekeeper, "update-ref", "refs/heads/main", run(clone, "rev-parse", "HEAD"));

        assertThatThrownBy(() -> run(clone, "push", "gatekeeper", ":refs/heads/main"))
                .isInstanceOf(GitException.class);
        assertThat(run(gatekeeper, "rev-parse", "--verify", "refs/heads/main")).isNotBlank();
    }

    @Test
    void refusesAMixedPush() {
        assertThatThrownBy(() -> run(clone, "push", "gatekeeper",
                "HEAD:refs/heads/factory/issue-7", "HEAD:refs/heads/main")).isInstanceOf(GitException.class);

        assertThat(run(github, "for-each-ref")).isEmpty();
        assertThat(run(gatekeeper, "for-each-ref")).isEmpty();
    }

    @Test
    void leavesAnUpToDateHookUnchanged() throws IOException {
        Path hook = gatekeeper.resolve("hooks").resolve("pre-receive");
        Object inodeBefore = Files.getAttribute(hook, "unix:ino");
        FileTime modifiedBefore = FileTime.fromMillis(0);
        Files.setLastModifiedTime(hook, modifiedBefore);

        Gatekeeper.ensure(gatekeeper, github.toString());

        assertThat(Files.getAttribute(hook, "unix:ino")).isEqualTo(inodeBefore);
        assertThat(Files.getLastModifiedTime(hook)).isEqualTo(modifiedBefore);
    }

    @Test
    void replacesAHookWithANewFileAndLeavesNoTemporaryFile() throws IOException {
        Path hook = gatekeeper.resolve("hooks").resolve("pre-receive");
        Files.writeString(hook, "#!/bin/sh\nexit 0\n");
        Object inodeBefore = Files.getAttribute(hook, "unix:ino");

        Gatekeeper.ensure(gatekeeper, github.toString());

        assertThat(Files.getAttribute(hook, "unix:ino")).isNotEqualTo(inodeBefore);
        assertThat(hook).isExecutable();
        assertThat(Files.readAllBytes(hook)).isEqualTo(readResource("/gatekeeper/pre-receive"));
        try (Stream<Path> files = Files.list(gatekeeper.resolve("hooks"))) {
            assertThat(files.map(file -> file.getFileName().toString())).noneMatch(name -> name.endsWith(".tmp"));
        }
    }

    @Test
    void restoresTheHooksOfAnExistingGatekeeper() throws IOException {
        for (String hookName : new String[] {"pre-receive", "post-receive"}) {
            Path hook = gatekeeper.resolve("hooks").resolve(hookName);
            Files.writeString(hook, "#!/bin/sh\nexit 0\n");
            hook.toFile().setExecutable(false);
        }

        Gatekeeper.ensure(gatekeeper, github.toString());

        for (String hookName : new String[] {"pre-receive", "post-receive"}) {
            Path hook = gatekeeper.resolve("hooks").resolve(hookName);
            assertThat(hook).isExecutable();
            assertThat(Files.readAllBytes(hook)).isEqualTo(readResource("/gatekeeper/" + hookName));
        }
    }

    @Test
    void refusesAPushToMainWithAHostileGlobalConfiguration() throws IOException {
        Path emptyHooks = Files.createDirectory(temporaryDirectory.resolve("empty-hooks"));
        Path hostileConfiguration = temporaryDirectory.resolve("hostile.gitconfig");
        Files.writeString(hostileConfiguration, "[core]\n\thooksPath = " + emptyHooks + "\n");

        assertThatThrownBy(() -> runWithEnvironment(Map.of("GIT_CONFIG_GLOBAL", hostileConfiguration.toString()),
                clone, "push", "gatekeeper", "HEAD:refs/heads/main")).isInstanceOf(GitException.class);

        assertThat(run(github, "for-each-ref")).isEmpty();
    }

    private byte[] readResource(String name) throws IOException {
        try (var source = Gatekeeper.class.getResourceAsStream(name)) {
            return source.readAllBytes();
        }
    }
}
