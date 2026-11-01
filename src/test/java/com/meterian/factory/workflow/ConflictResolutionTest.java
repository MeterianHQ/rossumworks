package com.meterian.factory.workflow;

import static com.meterian.factory.testing.TemporaryRepository.commitFile;
import static com.meterian.factory.testing.TemporaryRepository.createBare;
import static com.meterian.factory.testing.TemporaryRepository.createClone;
import static com.meterian.factory.testing.TemporaryRepository.run;
import static org.assertj.core.api.Assertions.assertThat;

import com.meterian.factory.git.Git;
import com.meterian.factory.git.ProcessGit;
import com.meterian.factory.prompt.Finding;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConflictResolutionTest {

    private static final String NON_ASCII_FILE = "café.txt";

    @TempDir
    Path temporaryDirectory;

    private final Git git = new ProcessGit();
    private final ConflictResolution conflictResolution = new ConflictResolution(git, "main");
    private Path clone;

    @BeforeEach
    void openAMergeWithAConflict() {
        Path origin = createBare(temporaryDirectory.resolve("origin.git"));
        clone = createClone(origin, temporaryDirectory.resolve("clone"));
        run(clone, "checkout", "-q", "-b", "feature");
        commitFile(clone, NON_ASCII_FILE, "feature\n", "Feature change");
        run(clone, "checkout", "-q", "main");
        commitFile(clone, NON_ASCII_FILE, "main\n", "Main change");
        run(clone, "checkout", "-q", "feature");
        git.mergeWithoutCommit(clone, "main");
    }

    @Test
    void findsAMarkerInAFileWithANonAsciiName() {
        List<String> conflictedPaths = git.conflictedPaths(clone);

        Optional<Finding> finding = conflictResolution.findRemainingMarkers(clone, conflictedPaths);

        assertThat(conflictedPaths).containsExactly(NON_ASCII_FILE);
        assertThat(finding).hasValueSatisfying(found -> assertThat(found.title()).contains(NON_ASCII_FILE));
    }

    @Test
    void findsAMarkerInAStagedFile() {
        run(clone, "add", NON_ASCII_FILE);

        Optional<Finding> finding = conflictResolution.findRemainingMarkers(clone, List.of(NON_ASCII_FILE));

        assertThat(git.conflictedPaths(clone)).isEmpty();
        assertThat(finding).isPresent();
    }
}
