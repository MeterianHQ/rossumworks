package com.meterian.factory.setup;

import static com.meterian.factory.testing.TemporaryRepository.commitFile;
import static com.meterian.factory.testing.TemporaryRepository.createBare;
import static com.meterian.factory.testing.TemporaryRepository.createClone;
import static com.meterian.factory.testing.TemporaryRepository.run;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.meterian.factory.configuration.RepositoryName;
import com.meterian.factory.git.Git;
import com.meterian.factory.git.GitException;
import com.meterian.factory.git.RebaseOutcome;
import com.meterian.factory.git.ProcessGit;
import com.meterian.factory.github.PullRequest;
import com.meterian.factory.testing.FakeGitHubClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
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

        PullRequest pullRequest = setup.publish(clone, gatekeeper, facts, files);
        assertThat(setup.assignPullRequest(pullRequest, "octocat")).isEmpty();

        assertThat(run(github, "show", "factory/setup:.factory/factory.yaml")).isEqualTo("a: b");
        assertThat(run(github, "show", "factory/setup:.factory/prompts/p.md")).isEqualTo("text");
        assertThat(run(github, "rev-parse", "main")).isEqualTo(mainBefore);
        assertThat(run(github, "for-each-ref", "--format=%(refname)", "refs/heads"))
                .isEqualTo("refs/heads/factory/setup\nrefs/heads/main");
        assertThat(gitHub.openPullRequest("factory/setup")).contains(pullRequest);
        assertThat(gitHub.assigneesOf(pullRequest.number())).containsExactly("octocat");
        assertThat(pullRequest.body()).contains("- .factory/factory.yaml", "- .factory/prompts/p.md",
                "The worker takes issues after the merge of this PR.", "docs/factory-definition.md");
        assertThat(Files.exists(work.resolve("setup-worktree"))).isFalse();
    }

    @Test
    void publishesAShellScriptAsExecutable() throws Exception {
        RepositoryFacts facts = setup.read(clone);
        Map<String, String> files = Map.of(".factory/factory.yaml", "a: b\n", ".factory/run.sh", "echo\n");

        setup.publish(clone, gatekeeper, facts, files);

        assertThat(run(github, "ls-tree", "factory/setup", ".factory/run.sh")).startsWith("100755 blob ");
        assertThat(run(github, "ls-tree", "factory/setup", ".factory/factory.yaml")).startsWith("100644 blob ");
    }

    @Test
    void publishesAgainAfterACrashedRun() throws Exception {
        RepositoryFacts facts = setup.read(clone);
        git.addWorktree(clone, work.resolve("setup-worktree"), "factory/setup", facts.baseCommit());

        PullRequest pullRequest = setup.publish(clone, gatekeeper, facts, Map.of(".factory/factory.yaml", "a\n"));

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

        assertThatThrownBy(() -> setup.verifyNoSetupBranch(new RepositoryName("acme", "widget")))
                .isInstanceOf(SetupException.class)
                .hasMessage("The branch factory/setup exists on GitHub without an open PR. "
                        + "The command wrote no worker.yaml. "
                        + "Delete the branch on GitHub with this command: "
                        + "gh api -X DELETE repos/acme/widget/git/refs/heads/factory/setup. "
                        + "Then run create again.");
    }

    @Test
    void aBranchThatDoesNotArriveOnGitHubStopsWithoutAPullRequest() throws Exception {
        RepositoryFacts facts = setup.read(clone);
        RepositorySetup brokenForward = new RepositorySetup(git, gitHub,
                temporaryDirectory.resolve("missing.git").toString());

        assertThatThrownBy(() -> brokenForward.publish(clone, gatekeeper, facts,
                Map.of(".factory/factory.yaml", "a\n")))
                .isInstanceOf(SetupException.class)
                .hasMessageContaining("GitHub does not have the branch factory/setup. The gatekeeper did not "
                        + "forward it: ")
                .hasMessageContaining("missing.git");

        assertThat(gitHub.pullRequests()).isEqualTo(List.of());
        assertThat(Files.exists(work.resolve("setup-worktree"))).isFalse();
    }

    @Test
    void aFailedAssignmentGivesTheProblem() throws Exception {
        RepositoryFacts facts = setup.read(clone);
        PullRequest pullRequest = setup.publish(clone, gatekeeper, facts, Map.of(".factory/factory.yaml", "a\n"));
        gitHub.failTheNextAssignment();

        assertThat(setup.assignPullRequest(pullRequest, "octocat")).contains("Cannot assign the PR to octocat: "
                + "GitHub answered 422 for POST /issues/" + pullRequest.number() + "/assignees. Assign it by hand.");
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

        assertThatThrownBy(() -> setup.publish(clone, gatekeeper, facts, Map.of(".factory/factory.yaml", "a\n")))
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
                Map.of(".factory/factory.yaml", "a\n")))
                .isInstanceOf(SetupException.class)
                .hasMessageStartingWith("Cannot push the branch factory/setup: ")
                .hasMessageNotContaining("cleanup failed");
    }

    /** A git whose removal of a worktree and prune fail after the start. */
    private static final class FailingCleanupGit implements Git {
        private final Git delegate;
        private final AtomicBoolean started = new AtomicBoolean();

        FailingCleanupGit(Git delegate) {
            this.delegate = delegate;
        }

        @Override
        public void removeWorktree(Path clone, Path worktree) {
            throw new GitException("cleanup failed: remove");
        }

        @Override
        public void pruneWorktrees(Path clone) {
            if (started.getAndSet(true)) {
                throw new GitException("cleanup failed: prune");
            }
            delegate.pruneWorktrees(clone);
        }

        @Override
        public void fetch(Path repository, String remote, String branch) {
            delegate.fetch(repository, remote, branch);
        }

        @Override
        public void addWorktree(Path clone, Path worktree, String branch, String startPoint) {
            delegate.addWorktree(clone, worktree, branch, startPoint);
        }

        @Override
        public String treeHash(Path worktree) {
            return delegate.treeHash(worktree);
        }

        @Override
        public String patchId(Path worktree, String base) {
            return delegate.patchId(worktree, base);
        }

        @Override
        public String treeHashOf(Path worktree, String commit) {
            return delegate.treeHashOf(worktree, commit);
        }

        @Override
        public String commitOf(Path repository, String reference) {
            return delegate.commitOf(repository, reference);
        }

        @Override
        public List<String> commitsOfPath(Path repository, String reference, String path, int limit) {
            return delegate.commitsOfPath(repository, reference, path, limit);
        }

        @Override
        public String resolveCommit(Path repository, String reference) {
            return delegate.resolveCommit(repository, reference);
        }

        @Override
        public Optional<String> showFile(Path repository, String commit, String path) {
            return delegate.showFile(repository, commit, path);
        }

        @Override
        public boolean hasPath(Path repository, String commit, String path) {
            return delegate.hasPath(repository, commit, path);
        }

        @Override
        public String defaultBranch(Path clone) {
            return delegate.defaultBranch(clone);
        }

        @Override
        public String headCommit(Path worktree) {
            return delegate.headCommit(worktree);
        }

        @Override
        public boolean changesPath(Path worktree, String baseReference, String path) {
            return delegate.changesPath(worktree, baseReference, path);
        }

        @Override
        public boolean hasUncommittedChanges(Path worktree) {
            return delegate.hasUncommittedChanges(worktree);
        }

        @Override
        public void commitAll(Path worktree, String message) {
            delegate.commitAll(worktree, message);
        }

        @Override
        public RebaseOutcome rebase(Path worktree, String upstream) {
            return delegate.rebase(worktree, upstream);
        }

        @Override
        public List<String> mergeWithoutCommit(Path worktree, String upstream) {
            return delegate.mergeWithoutCommit(worktree, upstream);
        }

        @Override
        public boolean hasOpenMerge(Path worktree) {
            return delegate.hasOpenMerge(worktree);
        }

        @Override
        public List<String> conflictedPaths(Path worktree) {
            return delegate.conflictedPaths(worktree);
        }

        @Override
        public List<String> parentsOf(Path worktree, String commit) {
            return delegate.parentsOf(worktree, commit);
        }

        @Override
        public boolean isAncestor(Path worktree, String ancestor, String commit) {
            return delegate.isAncestor(worktree, ancestor, commit);
        }

        @Override
        public void resetSoft(Path worktree, String commit) {
            delegate.resetSoft(worktree, commit);
        }

        @Override
        public void resetHard(Path worktree, String commit) {
            delegate.resetHard(worktree, commit);
        }

        @Override
        public void push(Path worktree, String remote, String branch) {
            delegate.push(worktree, remote, branch);
        }

        @Override
        public String pushAndReadOutput(Path worktree, String remote, String branch) {
            return delegate.pushAndReadOutput(worktree, remote, branch);
        }

        @Override
        public void pushCommit(Path worktree, String remote, String commit, String branch) {
            delegate.pushCommit(worktree, remote, commit, branch);
        }

        @Override
        public void forwardCommit(Path repository, String remote, String commit, String branch) {
            delegate.forwardCommit(repository, remote, commit, branch);
        }

        @Override
        public void excludeFactoryFolder(Path worktree) {
            delegate.excludeFactoryFolder(worktree);
        }

        @Override
        public void cloneRepository(String url, Path directory) {
            delegate.cloneRepository(url, directory);
        }

        @Override
        public void setRemote(Path repository, String name, String url) {
            delegate.setRemote(repository, name, url);
        }
    }
}
