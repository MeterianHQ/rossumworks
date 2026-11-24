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
import java.time.Duration;
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
    void clonesARepositoryIntoARelativeDestination() throws IOException {
        Path relativeParent = Path.of("target", "clone-test-" + System.nanoTime());
        Path relativeDestination = relativeParent.resolve("work").resolve("repository");
        Files.createDirectories(relativeDestination.getParent());
        try {
            git.cloneRepository(temporaryDirectory.resolve("origin.git").toString(), relativeDestination);

            assertThat(relativeDestination.toAbsolutePath().resolve(".git")).isDirectory();
            assertThat(relativeDestination.toAbsolutePath().resolve("a.txt")).isRegularFile();
            assertThat(relativeDestination.toAbsolutePath().getParent().resolve("work")).doesNotExist();
        } finally {
            deleteRecursively(relativeParent);
        }
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
        Files.createDirectories(worktree.resolve(".factory-work"));
        Files.writeString(worktree.resolve(".factory-work/question.md"), "Question\n");
        Files.writeString(worktree.resolve("fix.txt"), "fix\n");

        git.commitAll(worktree, "Fix the issue");

        String committedFiles = run(worktree, "show", "--name-only", "--format=", "HEAD");
        assertThat(committedFiles).contains("fix.txt").doesNotContain(".factory-work");
    }

    @Test
    void excludesTheScratchFolderButNotTheDefinitionFolder() throws IOException {
        Path worktree = temporaryDirectory.resolve("worktree");
        git.addWorktree(clone, worktree, "factory/issue-2", "main");
        git.excludeFactoryFolder(worktree);
        Files.createDirectories(worktree.resolve(".factory"));
        Files.writeString(worktree.resolve(".factory/factory.yaml"), "name: factory\n");
        Files.createDirectories(worktree.resolve(".factory-work"));
        Files.writeString(worktree.resolve(".factory-work/question.md"), "Question\n");

        git.commitAll(worktree, "Add the workflow definition");

        String committedFiles = run(worktree, "show", "--name-only", "--format=", "HEAD");
        assertThat(committedFiles).contains(".factory/factory.yaml").doesNotContain(".factory-work/question.md");
    }

    @Test
    void changesPathIgnoresTheScratchFolder() throws IOException {
        Path worktree = temporaryDirectory.resolve("worktree");
        git.addWorktree(clone, worktree, "factory/issue-3", "main");
        Files.createDirectories(worktree.resolve(".factory-work"));
        Files.writeString(worktree.resolve(".factory-work/question.md"), "Question\n");
        Files.writeString(worktree.resolve("fix.txt"), "fix\n");
        run(worktree, "add", "-A", "-f");
        run(worktree, "-c", "user.name=t", "-c", "user.email=t@example.com", "commit", "-q", "-m", "Fix");

        assertThat(git.changesPath(worktree, "main", ".factory")).isFalse();

        Files.createDirectories(worktree.resolve(".factory"));
        Files.writeString(worktree.resolve(".factory/factory.yaml"), "name: factory\n");
        run(worktree, "add", "-A", "-f");
        run(worktree, "-c", "user.name=t", "-c", "user.email=t@example.com", "commit", "-q", "-m", "Definition");

        assertThat(git.changesPath(worktree, "main", ".factory")).isTrue();
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
    void givesEachGitCommandSshOptionsThatNeverWaitForAnAnswer() {
        ProcessGit processGit = new ProcessGit();

        String environment = processGit.run(clone, "-c", "alias.environment=!env", "environment");

        assertThat(environment.lines()).contains("GIT_SSH_COMMAND=ssh -o BatchMode=yes -o ConnectTimeout=30"
                + " -o ServerAliveInterval=15 -o ServerAliveCountMax=4");
    }

    @Test
    void doesNotGiveTheAnthropicKeyToAGitHook() throws IOException {
        Path hook = clone.resolve(".git").resolve("hooks").resolve("pre-commit");
        Path hookEnvironment = temporaryDirectory.resolve("hook-environment.txt");
        Files.writeString(hook, "#!/bin/sh\nenv > '" + hookEnvironment + "'\n");
        assertThat(hook.toFile().setExecutable(true)).isTrue();
        Git keyedGit = new ProcessGit(Map.of("ANTHROPIC_API_KEY", "secret-key", "GH_TOKEN", "token"));
        Files.writeString(clone.resolve("b.txt"), "new\n");

        keyedGit.commitAll(clone, "Add b");

        assertThat(Files.readAllLines(hookEnvironment)).noneMatch(line -> line.startsWith("ANTHROPIC_"))
                .contains("GH_TOKEN=token");
    }

    @Test
    void removesTheInheritedVariablesThatRedirectGitOrAskForACredential() {
        Path otherRepository = createBare(temporaryDirectory.resolve("other.git"));
        ProcessGit hostileGit = new ProcessGit(Map.of(
                "GIT_DIR", otherRepository.toString(),
                "GIT_WORK_TREE", temporaryDirectory.toString(),
                "GIT_INDEX_FILE", temporaryDirectory.resolve("other.index").toString(),
                "GIT_ASKPASS", "/bin/echo",
                "SSH_ASKPASS", "/bin/echo"));

        String environment = hostileGit.run(clone, "-c", "alias.environment=!env", "environment");

        assertThat(hostileGit.headCommit(clone)).isEqualTo(run(clone, "rev-parse", "HEAD"));
        assertThat(environment.lines()).noneMatch(line -> line.startsWith("GIT_ASKPASS=")
                || line.startsWith("SSH_ASKPASS=") || line.startsWith("GIT_INDEX_FILE=")
                || line.startsWith("GIT_WORK_TREE=") || line.startsWith("GIT_DIR=" + otherRepository));
    }

    @Test
    void endsAFetchThatDoesNotEndInTheTimeout() {
        run(clone, "config", "remote.slow.url", temporaryDirectory.resolve("origin.git").toString());
        run(clone, "config", "remote.slow.uploadpack", "sleep 30; git-upload-pack");
        Git impatientGit = new ProcessGit(Map.of(), Duration.ofSeconds(1));
        long start = System.nanoTime();

        assertThatThrownBy(() -> impatientGit.fetch(clone, "slow", "main"))
                .isInstanceOf(GitException.class)
                .hasMessage("git fetch did not end in 1 second.");
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(15));
    }

    @Test
    void endsAPushThatDoesNotEndInTheTimeout() {
        run(clone, "config", "remote.slow.url", temporaryDirectory.resolve("origin.git").toString());
        run(clone, "config", "remote.slow.receivepack", "sleep 30; git-receive-pack");
        Git impatientGit = new ProcessGit(Map.of(), Duration.ofSeconds(1));
        long start = System.nanoTime();

        assertThatThrownBy(() -> impatientGit.pushCommit(clone, "slow", "HEAD", "factory/issue-1"))
                .isInstanceOf(GitException.class)
                .hasMessage("git push did not end in 1 second.");
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(15));
    }

    @Test
    void usesANetworkTimeoutOfFiveMinutes() {
        assertThat(ProcessGit.NETWORK_TIMEOUT).isEqualTo(Duration.ofMinutes(5));
    }

    @Test
    void commitsWithTheFixedIdentity() throws IOException {
        Files.writeString(clone.resolve("fix.txt"), "fix\n");

        git.commitAll(clone, "Fix the issue");

        assertThat(run(clone, "log", "-1", "--format=%an <%ae>"))
                .isEqualTo("rossumworks <rossumworks@users.noreply.github.com>");
    }

    @Test
    void commitOfResolvesAReferenceToItsCommit() {
        String head = run(clone, "rev-parse", "HEAD");

        assertThat(git.commitOf(clone, "origin/main")).isEqualTo(head);
        assertThatThrownBy(() -> git.commitOf(clone, "origin/missing")).isInstanceOf(GitException.class);
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
    void patchIdDiffersWhenOnlyAnInvalidUtf8ByteDiffers() throws IOException {
        String first = patchIdOfANewFile("one", new byte[] {'a', (byte) 0xFF, 'b', '\n'});
        String second = patchIdOfANewFile("two", new byte[] {'a', (byte) 0xFE, 'b', '\n'});

        assertThat(first).isNotBlank().isNotEqualTo(second);
    }

    private String patchIdOfANewFile(String branch, byte[] content) throws IOException {
        run(clone, "checkout", "-q", "-b", branch, "main");
        Files.write(clone.resolve("bytes.txt"), content);
        run(clone, "add", "bytes.txt");
        run(clone, "commit", "-q", "-m", "Bytes " + branch);
        return git.patchId(clone, "main");
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
    void pushAndReadOutputGivesTheLinesOfTheRemoteHook() throws IOException {
        Path remote = createBare(temporaryDirectory.resolve("hooked.git"));
        Path hook = remote.resolve("hooks/post-receive");
        Files.writeString(hook, "#!/bin/sh\necho forward-failed-here >&2\n");
        hook.toFile().setExecutable(true);
        git.setRemote(clone, "hooked", remote.toString());

        String output = git.pushAndReadOutput(clone, "hooked", "factory/issue-7");

        assertThat(output).contains("remote: forward-failed-here");
        assertThat(run(remote, "rev-parse", "refs/heads/factory/issue-7")).isEqualTo(git.headCommit(clone));
    }

    @Test
    void pushAndReadOutputRefusesABranchOutsideFactory() {
        assertThatThrownBy(() -> git.pushAndReadOutput(clone, "origin", "main"))
                .isInstanceOf(GitException.class)
                .hasMessageContaining("factory/");
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

    @Test
    void conflictedPathsReadsANonAsciiName() {
        run(clone, "checkout", "-q", "-b", "feature");
        commitFile(clone, "größe.txt", "feature\n", "Feature change");
        run(clone, "checkout", "-q", "main");
        commitFile(clone, "größe.txt", "main\n", "Main change");
        run(clone, "checkout", "-q", "feature");

        assertThat(git.mergeWithoutCommit(clone, "main")).containsExactly("größe.txt");
    }

    @Test
    void showFileReadsTheFileOfTheCommit() {
        String first = git.headCommit(clone);
        commitFile(clone, "a.txt", "second\n", "Second commit");

        assertThat(git.showFile(clone, first, "a.txt")).contains("first\n");
    }

    @Test
    void hasPathFindsAFileAndAFolder() {
        commitFile(clone, ".factory/prompts/a.md", "text\n", "Add a prompt");
        String head = git.headCommit(clone);

        assertThat(git.hasPath(clone, head, ".factory")).isTrue();
        assertThat(git.hasPath(clone, head, ".factory/prompts/a.md")).isTrue();
        assertThat(git.hasPath(clone, head, "mvnw")).isFalse();
    }

    @Test
    void showFileIsEmptyForAFileThatTheCommitDoesNotHave() {
        assertThat(git.showFile(clone, git.headCommit(clone), "missing.txt")).isEmpty();
    }

    @Test
    void showFileRefusesACommitThatDoesNotExist() {
        assertThatThrownBy(() -> git.showFile(clone, "0123456789abcdef0123456789abcdef01234567", "a.txt"))
                .isInstanceOf(GitException.class);
    }

    @Test
    void commitsOfPathGivesTheNewestCommitsThatChangeThePath() {
        String first = git.headCommit(clone);
        commitFile(clone, "a.txt", "second\n", "Second commit");
        String second = git.headCommit(clone);
        commitFile(clone, "b.txt", "b\n", "Other file");
        commitFile(clone, "a.txt", "fourth\n", "Fourth commit");
        String fourth = git.headCommit(clone);

        assertThat(git.commitsOfPath(clone, "HEAD", "a.txt", 10)).containsExactly(fourth, second, first);
        assertThat(git.commitsOfPath(clone, "HEAD", "a.txt", 2)).containsExactly(fourth, second);
        assertThat(git.commitsOfPath(clone, "HEAD", "missing.txt", 10)).isEmpty();
    }

    @Test
    void resolveCommitGivesTheCommitOfTheReference() {
        commitFile(clone, "b.txt", "b\n", "Second commit");
        run(clone, "push", "-q", "origin", "main");
        run(clone, "fetch", "-q", "origin");

        assertThat(git.resolveCommit(clone, "origin/main")).isEqualTo(git.headCommit(clone));
    }

    @Test
    void resolveCommitRefusesAnUnknownReference() {
        assertThatThrownBy(() -> git.resolveCommit(clone, "origin/unknown")).isInstanceOf(GitException.class);
    }

    @Test
    void defaultBranchReadsTheHeadOfTheOrigin() {
        run(clone, "remote", "set-head", "origin", "main");

        assertThat(git.defaultBranch(clone)).isEqualTo("main");
    }

    @Test
    void defaultBranchAsksTheOriginWhenTheCloneDoesNotKnowItsHead() {
        assertThat(git.defaultBranch(clone)).isEqualTo("main");
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
