package com.meterian.factory.git;

import static com.meterian.factory.testing.TemporaryRepository.commitFile;
import static com.meterian.factory.testing.TemporaryRepository.createBare;
import static com.meterian.factory.testing.TemporaryRepository.createClone;
import static com.meterian.factory.testing.TemporaryRepository.run;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProcessGitTest {

    @TempDir
    Path temporaryDirectory;

    private final Git git = new ProcessGit();
    private Path clone;

    @BeforeEach
    void createRepositories() {
        Path origin = createBare(temporaryDirectory.resolve("origin.git"));
        clone = createClone(origin, temporaryDirectory.resolve("clone"));
    }

    @Test
    void treeHashDoesNotChangeWithAnEmptyCommit() {
        String before = git.treeHash(clone);

        run(clone, "commit", "-q", "--allow-empty", "-m", "Empty commit");

        assertThat(git.treeHash(clone)).isEqualTo(before);
    }

    @Test
    void patchIdStaysTheSameAfterARebaseWithoutConflict() {
        run(clone, "checkout", "-q", "-b", "feature");
        commitFile(clone, "b.txt", "feature\n", "Feature commit");
        run(clone, "checkout", "-q", "main");
        commitFile(clone, "c.txt", "main\n", "Main commit");
        run(clone, "checkout", "-q", "feature");
        String before = git.patchId(clone, "main");

        RebaseOutcome outcome = git.rebase(clone, "main");

        assertThat(outcome).isEqualTo(RebaseOutcome.REBASED);
        assertThat(before).isNotBlank();
        assertThat(git.patchId(clone, "main")).isEqualTo(before);
    }

    @Test
    void rebaseReportsAConflictAndLeavesACleanTree() {
        run(clone, "checkout", "-q", "-b", "feature");
        commitFile(clone, "a.txt", "feature\n", "Feature change");
        run(clone, "checkout", "-q", "main");
        commitFile(clone, "a.txt", "main\n", "Main change");
        run(clone, "checkout", "-q", "feature");

        RebaseOutcome outcome = git.rebase(clone, "main");

        assertThat(outcome).isEqualTo(RebaseOutcome.CONFLICT);
        assertThat(git.hasUncommittedChanges(clone)).isFalse();
        assertThat(clone.resolve(".git/rebase-merge")).doesNotExist();
        assertThat(clone.resolve(".git/rebase-apply")).doesNotExist();
    }

    @Test
    void commitAllDoesNotCommitTheFactoryFolder() throws IOException {
        Path worktree = temporaryDirectory.resolve("worktree");
        git.addWorktree(clone, worktree, "factory/issue-1", "main");
        git.excludeFactoryFolder(worktree);
        Files.createDirectories(worktree.resolve(".factory"));
        Files.writeString(worktree.resolve(".factory/question.md"), "Question\n");
        Files.writeString(worktree.resolve("fix.txt"), "fix\n");

        git.commitAll(worktree, "Fix the issue");

        String committedFiles = run(worktree, "show", "--name-only", "--format=", "HEAD");
        assertThat(committedFiles).contains("fix.txt").doesNotContain(".factory");
    }

    @Test
    void failsWithTheCommandInTheMessage() {
        Path notRepository = temporaryDirectory.resolve("plain");
        assertThat(notRepository.toFile().mkdir()).isTrue();

        assertThatThrownBy(() -> git.treeHash(notRepository))
                .isInstanceOf(GitException.class)
                .hasMessageContaining("rev-parse");
    }

    @Test
    void setRemoteReplacesAnExistingUrl() {
        git.setRemote(clone, "gatekeeper", "/first/url");
        git.setRemote(clone, "gatekeeper", "/second/url");

        assertThat(run(clone, "remote", "get-url", "gatekeeper")).isEqualTo("/second/url");
    }

    @Test
    void addWorktreeResetsAnExistingBranchToTheStartPoint() {
        run(clone, "branch", "factory/issue-1");
        commitFile(clone, "b.txt", "second\n", "Second commit");
        Path worktree = temporaryDirectory.resolve("worktree");

        git.addWorktree(clone, worktree, "factory/issue-1", "main");

        assertThat(run(worktree, "rev-parse", "HEAD")).isEqualTo(run(clone, "rev-parse", "main"));
    }

    @Test
    void pushRefusesABranchOutsideFactory() {
        assertThatThrownBy(() -> git.push(clone, "origin", "main"))
                .isInstanceOf(GitException.class)
                .hasMessageContaining("factory/");
    }

    @Test
    void ignoresTheGlobalConfigurationOfTheMachine() throws IOException {
        Path hostileConfiguration = temporaryDirectory.resolve("hostile.gitconfig");
        Files.writeString(hostileConfiguration, "[status]\n\tshowUntrackedFiles = no\n");
        Git hostileGit = new ProcessGit(Map.of("GIT_CONFIG_GLOBAL", hostileConfiguration.toString()));
        Files.writeString(clone.resolve("untracked.txt"), "new\n");

        assertThat(hostileGit.hasUncommittedChanges(clone)).isTrue();
    }

    @Test
    void commitsWithTheFixedIdentity() throws IOException {
        Files.writeString(clone.resolve("fix.txt"), "fix\n");

        git.commitAll(clone, "Fix the issue");

        assertThat(run(clone, "log", "-1", "--format=%an <%ae>"))
                .isEqualTo("claude-ai-factory <claude-ai-factory@users.noreply.github.com>");
    }
}
