package com.meterian.factory.git;

import static com.meterian.factory.testing.TemporaryRepository.createBare;
import static com.meterian.factory.testing.TemporaryRepository.createClone;
import static com.meterian.factory.testing.TemporaryRepository.run;
import static com.meterian.factory.testing.TemporaryRepository.runWithEnvironment;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GatekeeperTest {

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
