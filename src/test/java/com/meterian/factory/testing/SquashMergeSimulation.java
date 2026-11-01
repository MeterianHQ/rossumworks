package com.meterian.factory.testing;

import static com.meterian.factory.testing.TemporaryRepository.run;
import static com.meterian.factory.testing.TemporaryRepository.runWithEnvironment;

import com.meterian.factory.git.GitException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

/**
 * Does the squash merge of GitHub on the origin of a clone: one new commit on the newest base, with the tree of
 * a three-way merge of the head and the base. The clone must hold the head commit.
 */
final class SquashMergeSimulation {

    private static final String BASE_REFERENCE = "refs/fake-github/base";

    private final Path clone;

    SquashMergeSimulation(Path clone) {
        this.clone = clone;
    }

    /** Returns the tree of the new commit on the base branch, or empty when the merge has a conflict. */
    Optional<String> squashMerge(String headCommit, String baseBranch) {
        String originUrl = run(clone, "remote", "get-url", "origin");
        // A fetch by URL does not move the remote-tracking branches of the clone.
        run(clone, "fetch", "-q", originUrl, "+refs/heads/" + baseBranch + ":" + BASE_REFERENCE);
        String baseCommit = run(clone, "rev-parse", BASE_REFERENCE);
        Optional<String> tree = mergeTrees(baseCommit, headCommit);
        if (tree.isEmpty()) {
            return Optional.empty();
        }
        String squashCommit = run(clone, "commit-tree", tree.get(), "-p", baseCommit, "-m", "Squash merge");
        run(clone, "push", "-q", originUrl, squashCommit + ":refs/heads/" + baseBranch);
        return tree;
    }

    private Optional<String> mergeTrees(String baseCommit, String headCommit) {
        if (isAncestor(baseCommit, headCommit)) {
            return Optional.of(run(clone, "rev-parse", headCommit + "^{tree}"));
        }
        String mergeBase = run(clone, "merge-base", baseCommit, headCommit);
        Path indexFile = createIndexFile();
        Map<String, String> environment = Map.of("GIT_INDEX_FILE", indexFile.toString());
        try {
            runWithEnvironment(environment, clone, "read-tree", "-i", "-m", "--aggressive",
                    mergeBase, baseCommit, headCommit);
            return Optional.of(runWithEnvironment(environment, clone, "write-tree"));
        } catch (GitException conflict) {
            return Optional.empty();
        } finally {
            deleteQuietly(indexFile);
        }
    }

    private boolean isAncestor(String ancestor, String commit) {
        try {
            run(clone, "merge-base", "--is-ancestor", ancestor, commit);
            return true;
        } catch (GitException notAncestor) {
            return false;
        }
    }

    private static Path createIndexFile() {
        try {
            Path indexFile = Files.createTempFile("fake-github", ".index");
            Files.delete(indexFile);
            return indexFile;
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // The file is in the temporary folder.
        }
    }
}
