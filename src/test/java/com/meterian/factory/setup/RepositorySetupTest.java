package com.meterian.factory.setup;

import static com.meterian.factory.testing.TemporaryRepository.commitFile;
import static com.meterian.factory.testing.TemporaryRepository.createBare;
import static com.meterian.factory.testing.TemporaryRepository.createClone;
import static com.meterian.factory.testing.TemporaryRepository.run;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.meterian.factory.git.Git;
import com.meterian.factory.git.ProcessGit;
import com.meterian.factory.github.PullRequest;
import com.meterian.factory.testing.FakeGitHubClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RepositorySetupTest {

    @TempDir
    Path temporaryDirectory;

    private final Git git = new ProcessGit();
    private final FakeGitHubClient gitHub = new FakeGitHubClient();
    private Path github;
    private Path work;
    private Path clone;
    private Path gatekeeper;
    private RepositorySetup setup;

    @BeforeEach
    void createRepositories() {
        github = createBare(temporaryDirectory.resolve("github.git"));
        createClone(github, temporaryDirectory.resolve("seed"));
        work = temporaryDirectory.resolve("work");
        clone = work.resolve("repository");
        gatekeeper = work.resolve("gatekeeper.git");
        gitHub.readPullRequestHeadsFrom(github);
        setup = new RepositorySetup(git, gitHub, github.toString());
    }

    @Test
    void clonesAndReadsTheDefaultBranch() throws Exception {
        RepositoryFacts facts = setup.read(clone);

        assertThat(facts.defaultBranch()).isEqualTo("main");
        assertThat(facts.baseCommit()).isEqualTo(run(github, "rev-parse", "main"));
        assertThat(facts.hasFactory()).isFalse();
        assertThat(facts.hasFile().test("a.txt")).isTrue();
        assertThat(facts.hasFile().test("b.txt")).isFalse();
    }

    @Test
    void fetchesAnExistingClone() throws Exception {
        setup.read(clone);
        Path seed = temporaryDirectory.resolve("seed");
        commitFile(seed, "b.txt", "second\n", "Second commit");
        run(seed, "push", "-q", "origin", "main");

        RepositoryFacts facts = setup.read(clone);

        assertThat(facts.baseCommit()).isEqualTo(run(github, "rev-parse", "main"));
        assertThat(facts.hasFile().test("b.txt")).isTrue();
    }

    @Test
    void findsAnExistingFactory() throws Exception {
        pushToMain(".factory/factory.yaml", "build: {}\n");

        assertThat(setup.read(clone).hasFactory()).isTrue();
    }

    @Test
    void refusesAFactoryFolderWithoutTheMainFile() {
        pushToMain(".factory/other.txt", "x\n");

        assertThatThrownBy(() -> setup.read(clone))
                .isInstanceOf(SetupException.class)
                .hasMessage("The default branch has a folder .factory without factory.yaml. "
                        + "Correct the folder, and run create again.");
    }

    @Test
    void publishesOnlyTheSetupBranch() throws Exception {
        RepositoryFacts facts = setup.read(clone);
        String mainBefore = run(github, "rev-parse", "main");
        Map<String, String> files = Map.of(".factory/factory.yaml", "a: b\n", ".factory/prompts/p.md", "text\n");

        PullRequest pullRequest = setup.publish(clone, gatekeeper, facts, files, "bbossola");

        assertThat(run(github, "show", "factory/setup:.factory/factory.yaml")).isEqualTo("a: b");
        assertThat(run(github, "show", "factory/setup:.factory/prompts/p.md")).isEqualTo("text");
        assertThat(run(github, "rev-parse", "main")).isEqualTo(mainBefore);
        assertThat(run(github, "for-each-ref", "--format=%(refname)", "refs/heads"))
                .isEqualTo("refs/heads/factory/setup\nrefs/heads/main");
        assertThat(gitHub.openPullRequest("factory/setup")).contains(pullRequest);
        assertThat(gitHub.assigneesOf(pullRequest.number())).containsExactly("bbossola");
        assertThat(pullRequest.body()).contains("- .factory/factory.yaml", "- .factory/prompts/p.md",
                "The worker takes issues after the merge of this PR.", "docs/factory-definition.md");
        assertThat(Files.exists(work.resolve("setup-worktree"))).isFalse();
    }

    @Test
    void publishesAgainAfterACrashedRun() throws Exception {
        RepositoryFacts facts = setup.read(clone);
        git.addWorktree(clone, work.resolve("setup-worktree"), "factory/setup", facts.baseCommit());

        PullRequest pullRequest = setup.publish(clone, gatekeeper, facts, Map.of(".factory/factory.yaml", "a\n"), "o");

        assertThat(pullRequest.headBranch()).isEqualTo("factory/setup");
    }

    @Test
    void anOpenPullRequestIsShownAndNothingIsPushed() throws Exception {
        run(github, "branch", "factory/setup", "main");
        PullRequest existing = gitHub.createPullRequest("factory/setup", "main", "Set up", "body");

        assertThat(setup.findOpenSetup()).contains(existing);
    }

    @Test
    void refusesAnExistingSetupBranch() {
        run(github, "branch", "factory/setup", "main");

        assertThatThrownBy(setup::verifyNoSetupBranch)
                .isInstanceOf(SetupException.class)
                .hasMessage("The branch factory/setup exists on GitHub without an open PR. "
                        + "Delete the branch, or open the PR.");
    }

    @Test
    void aBranchThatDoesNotArriveOnGitHubStopsWithoutAPullRequest() throws Exception {
        RepositoryFacts facts = setup.read(clone);
        RepositorySetup brokenForward = new RepositorySetup(git, gitHub,
                temporaryDirectory.resolve("missing.git").toString());

        assertThatThrownBy(() -> brokenForward.publish(clone, gatekeeper, facts,
                Map.of(".factory/factory.yaml", "a\n"), "bbossola"))
                .isInstanceOf(SetupException.class)
                .hasMessageContaining("GitHub does not have the branch factory/setup");

        assertThat(gitHub.pullRequests()).isEqualTo(List.of());
        assertThat(Files.exists(work.resolve("setup-worktree"))).isFalse();
    }

    private void pushToMain(String fileName, String content) {
        Path seed = temporaryDirectory.resolve("seed");
        commitFile(seed, fileName, content, "Add " + fileName);
        run(seed, "push", "-q", "origin", "main");
    }
}
