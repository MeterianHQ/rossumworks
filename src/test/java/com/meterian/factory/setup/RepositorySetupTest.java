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
                .hasMessageContaining("GitHub does not have the branch factory/setup. The gatekeeper did not "
                        + "forward it: ")
                .hasMessageContaining("missing.git");

        assertThat(gitHub.pullRequests()).isEqualTo(List.of());
        assertThat(Files.exists(work.resolve("setup-worktree"))).isFalse();
    }

    private void pushToMain(String fileName, String content) {
        Path seed = temporaryDirectory.resolve("seed");
        commitFile(seed, fileName, content, "Add " + fileName);
        run(seed, "push", "-q", "origin", "main");
    }

    @Test
    void aFailureOfTheCommitIsNotReportedAsAFailedForward() throws Exception {
        RepositoryFacts facts = setup.read(clone);
        Files.writeString(clone.resolve(".git/hooks/pre-commit"), "#!/bin/sh\necho commit-refused >&2\nexit 1\n");
        clone.resolve(".git/hooks/pre-commit").toFile().setExecutable(true);

        assertThatThrownBy(() -> setup.publish(clone, gatekeeper, facts, Map.of(".factory/factory.yaml", "a\n"), "o"))
                .isInstanceOf(SetupException.class)
                .hasMessageStartingWith("Cannot push the branch factory/setup: ")
                .hasMessageContaining("commit")
                .satisfies(exception -> assertThat(exception.getMessage()).doesNotContain("did not forward"));
    }

    @Test
    void aFailureOfTheCleanupDoesNotHideTheFirstFailure() throws Exception {
        RepositoryFacts facts = setup.read(clone);
        Git failingCleanup = new FailingCleanupGit(git);
        RepositorySetup brokenSetup = new RepositorySetup(failingCleanup, gitHub, github.toString());
        Files.writeString(clone.resolve(".git/hooks/pre-commit"), "#!/bin/sh\nexit 1\n");
        clone.resolve(".git/hooks/pre-commit").toFile().setExecutable(true);

        assertThatThrownBy(() -> brokenSetup.publish(clone, gatekeeper, facts,
                Map.of(".factory/factory.yaml", "a\n"), "o"))
                .isInstanceOf(SetupException.class)
                .hasMessageStartingWith("Cannot push the branch factory/setup: ")
                .hasMessageNotContaining("cleanup failed");
    }

    /** A git whose removal of a worktree and prune fail after the start. */
    private static final class FailingCleanupGit implements Git {
        private final Git delegate;
        private final java.util.concurrent.atomic.AtomicBoolean started = new java.util.concurrent.atomic.AtomicBoolean();

        FailingCleanupGit(Git delegate) {
            this.delegate = delegate;
        }

        @Override
        public void removeWorktree(Path clone, Path worktree) {
            throw new com.meterian.factory.git.GitException("cleanup failed: remove");
        }

        @Override
        public void pruneWorktrees(Path clone) {
            if (started.getAndSet(true)) {
                throw new com.meterian.factory.git.GitException("cleanup failed: prune");
            }
            delegate.pruneWorktrees(clone);
        }

        @Override public void fetch(Path r, String a, String b) { delegate.fetch(r, a, b); }
        @Override public void addWorktree(Path c, Path w, String b, String s) { delegate.addWorktree(c, w, b, s); }
        @Override public String treeHash(Path w) { return delegate.treeHash(w); }
        @Override public String patchId(Path w, String b) { return delegate.patchId(w, b); }
        @Override public String treeHashOf(Path w, String c) { return delegate.treeHashOf(w, c); }
        @Override public String commitOf(Path r, String f) { return delegate.commitOf(r, f); }
        @Override public java.util.List<String> commitsOfPath(Path r, String f, String p, int l) { return delegate.commitsOfPath(r, f, p, l); }
        @Override public String resolveCommit(Path r, String f) { return delegate.resolveCommit(r, f); }
        @Override public java.util.Optional<String> showFile(Path r, String c, String p) { return delegate.showFile(r, c, p); }
        @Override public boolean hasPath(Path r, String c, String p) { return delegate.hasPath(r, c, p); }
        @Override public String defaultBranch(Path c) { return delegate.defaultBranch(c); }
        @Override public String headCommit(Path w) { return delegate.headCommit(w); }
        @Override public boolean changesPath(Path w, String b, String p) { return delegate.changesPath(w, b, p); }
        @Override public boolean hasUncommittedChanges(Path w) { return delegate.hasUncommittedChanges(w); }
        @Override public void commitAll(Path w, String m) { delegate.commitAll(w, m); }
        @Override public com.meterian.factory.git.RebaseOutcome rebase(Path w, String u) { return delegate.rebase(w, u); }
        @Override public java.util.List<String> mergeWithoutCommit(Path w, String u) { return delegate.mergeWithoutCommit(w, u); }
        @Override public boolean hasOpenMerge(Path w) { return delegate.hasOpenMerge(w); }
        @Override public java.util.List<String> conflictedPaths(Path w) { return delegate.conflictedPaths(w); }
        @Override public java.util.List<String> parentsOf(Path w, String c) { return delegate.parentsOf(w, c); }
        @Override public boolean isAncestor(Path w, String a, String c) { return delegate.isAncestor(w, a, c); }
        @Override public void resetSoft(Path w, String c) { delegate.resetSoft(w, c); }
        @Override public void resetHard(Path w, String c) { delegate.resetHard(w, c); }
        @Override public void push(Path w, String r, String b) { delegate.push(w, r, b); }
        @Override public String pushAndReadOutput(Path w, String r, String b) { return delegate.pushAndReadOutput(w, r, b); }
        @Override public void pushCommit(Path w, String r, String c, String b) { delegate.pushCommit(w, r, c, b); }
        @Override public void excludeFactoryFolder(Path w) { delegate.excludeFactoryFolder(w); }
        @Override public void cloneRepository(String u, Path d) { delegate.cloneRepository(u, d); }
        @Override public void setRemote(Path r, String n, String u) { delegate.setRemote(r, n, u); }
    }
}
