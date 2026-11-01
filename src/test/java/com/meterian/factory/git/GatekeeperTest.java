package com.meterian.factory.git;

import static com.meterian.factory.testing.TemporaryRepository.createBare;
import static com.meterian.factory.testing.TemporaryRepository.createClone;
import static com.meterian.factory.testing.TemporaryRepository.run;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GatekeeperTest {

    @TempDir
    Path temporaryDirectory;

    private final Git git = new ProcessGit();
    private Path github;
    private Path clone;

    @BeforeEach
    void createRepositories() {
        github = createBare(temporaryDirectory.resolve("github.git"));
        Path gatekeeper = temporaryDirectory.resolve("gatekeeper.git");
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
    void refusesAPushToMain() {
        assertThatThrownBy(() -> git.push(clone, "gatekeeper", "main")).isInstanceOf(GitException.class);

        assertThatThrownBy(() -> run(github, "rev-parse", "--verify", "refs/heads/main"))
                .isInstanceOf(GitException.class);
    }

    @Test
    void refusesAPushToATag() {
        assertThatThrownBy(() -> run(clone, "push", "gatekeeper", "HEAD:refs/tags/v1"))
                .isInstanceOf(GitException.class);
    }

    @Test
    void refusesTheDeleteOfAFactoryBranch() {
        git.push(clone, "gatekeeper", "factory/issue-7");

        assertThatThrownBy(() -> run(clone, "push", "gatekeeper", ":refs/heads/factory/issue-7"))
                .isInstanceOf(GitException.class);
        assertThat(run(github, "rev-parse", "--verify", "refs/heads/factory/issue-7")).isNotBlank();
    }
}
