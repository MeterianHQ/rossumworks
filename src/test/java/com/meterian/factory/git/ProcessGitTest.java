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
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
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

    @Test
    void headCommitReturnsTheHashOfHead() {
        assertThat(git.headCommit(clone)).isEqualTo(run(clone, "rev-parse", "HEAD"));
    }

    @Test
    void patchIdDiffersWhenOnlyTheIndentationDiffers() {
        run(clone, "checkout", "-q", "-b", "feature");
        commitFile(clone, "b.txt", "if (x) {\n  y();\n}\n", "Two spaces");
        String twoSpaces = git.patchId(clone, "main");
        run(clone, "reset", "-q", "--hard", "main");
        commitFile(clone, "b.txt", "if (x) {\n    y();\n}\n", "Four spaces");

        assertThat(git.patchId(clone, "main")).isNotEqualTo(twoSpaces);
    }

    @Test
    void patchIdDiffersWhenOnlyTheContentOfABinaryFileDiffers() throws IOException {
        run(clone, "checkout", "-q", "-b", "feature");
        Files.write(clone.resolve("image.bin"), new byte[] {0, 1, 2, 3});
        run(clone, "add", "image.bin");
        run(clone, "commit", "-q", "-m", "First binary");
        String first = git.patchId(clone, "main");
        run(clone, "reset", "-q", "--hard", "main");
        Files.write(clone.resolve("image.bin"), new byte[] {0, 1, 2, 4});
        run(clone, "add", "image.bin");
        run(clone, "commit", "-q", "-m", "Second binary");

        assertThat(git.patchId(clone, "main")).isNotEqualTo(first);
    }

    @Test
    void pushCommitPushesTheGivenCommitAndNotHead() {
        Path remote = createBare(temporaryDirectory.resolve("remote.git"));
        git.setRemote(clone, "remote", remote.toString());
        String first = git.headCommit(clone);
        commitFile(clone, "b.txt", "second\n", "Second");

        git.pushCommit(clone, "remote", first, "factory/issue-7");

        assertThat(run(remote, "rev-parse", "refs/heads/factory/issue-7")).isEqualTo(first);
    }

    @Test
    void pushCommitRefusesABranchOutsideFactory() {
        assertThatThrownBy(() -> git.pushCommit(clone, "origin", git.headCommit(clone), "main"))
                .isInstanceOf(GitException.class)
                .hasMessageContaining("factory/");
    }

    @Test
    void treeHashOfReadsTheTreeOfACommit() {
        String first = git.headCommit(clone);
        String firstTree = git.treeHash(clone);
        commitFile(clone, "b.txt", "second\n", "Second");

        assertThat(git.treeHashOf(clone, first)).isEqualTo(firstTree);
    }

    @Test
    void mergeWithoutCommitReturnsTheConflictedPaths() {
        createConflictBetweenFeatureAndMain();

        List<String> conflictedPaths = git.mergeWithoutCommit(clone, "main");

        assertThat(conflictedPaths).containsExactly("a.txt");
        assertThat(clone.resolve(".git/MERGE_HEAD")).exists();
    }

    @Test
    void mergeWithoutCommitReturnsNoPathWhenTheBranchContainsTheUpstream() {
        run(clone, "checkout", "-q", "-b", "feature");
        commitFile(clone, "b.txt", "feature\n", "Feature commit");

        assertThat(git.mergeWithoutCommit(clone, "main")).isEmpty();
        assertThat(clone.resolve(".git/MERGE_HEAD")).doesNotExist();
    }

    @Test
    void commitAllAfterASolvedConflictMakesARebaseUpToDate() throws IOException {
        createConflictBetweenFeatureAndMain();
        git.mergeWithoutCommit(clone, "main");
        Files.writeString(clone.resolve("a.txt"), "feature and main\n");

        git.commitAll(clone, "Solve the conflict");

        assertThat(run(clone, "rev-list", "--parents", "-n", "1", "HEAD").split(" ")).hasSize(3);
        assertThat(git.rebase(clone, "main")).isEqualTo(RebaseOutcome.UP_TO_DATE);
    }

    @Test
    void pruneWorktreesForgetsADeletedWorktree() throws IOException {
        Path worktree = temporaryDirectory.resolve("worktree");
        git.addWorktree(clone, worktree, "factory/issue-1", "main");
        deleteRecursively(worktree);

        git.pruneWorktrees(clone);

        assertThat(run(clone, "worktree", "list")).doesNotContain(worktree.toString());
    }

    @Test
    void hasOpenMergeOnlyDuringAMergeWithoutCommit() {
        createConflictBetweenFeatureAndMain();
        assertThat(git.hasOpenMerge(clone)).isFalse();

        git.mergeWithoutCommit(clone, "main");

        assertThat(git.hasOpenMerge(clone)).isTrue();
        assertThat(git.conflictedPaths(clone)).containsExactly("a.txt");
    }

    @Test
    void resetSoftKeepsTheFilesAndMovesHead() throws IOException {
        createConflictBetweenFeatureAndMain();
        git.mergeWithoutCommit(clone, "main");
        Files.writeString(clone.resolve("a.txt"), "feature and main\n");
        git.commitAll(clone, "Solve the conflict");
        String mergedTree = git.treeHash(clone);

        git.resetSoft(clone, "main");
        git.commitAll(clone, "One commit");

        assertThat(git.treeHash(clone)).isEqualTo(mergedTree);
        assertThat(run(clone, "rev-list", "--count", "main..HEAD")).isEqualTo("1");
        assertThat(git.hasOpenMerge(clone)).isFalse();
    }

    private void createConflictBetweenFeatureAndMain() {
        run(clone, "checkout", "-q", "-b", "feature");
        commitFile(clone, "a.txt", "feature\n", "Feature change");
        run(clone, "checkout", "-q", "main");
        commitFile(clone, "a.txt", "main\n", "Main change");
        run(clone, "checkout", "-q", "feature");
    }

    private static void deleteRecursively(Path directory) throws IOException {
        try (Stream<Path> paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }
}
