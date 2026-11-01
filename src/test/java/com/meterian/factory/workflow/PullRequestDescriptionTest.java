package com.meterian.factory.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PullRequestDescriptionTest {

    private static final String FALLBACK = "The agent wrote no description.";
    private static final String LAST_LINE = "The factory made this change for the issue #7.";

    @TempDir
    Path worktree;

    private void writeDescription(String content) throws IOException {
        Files.createDirectories(worktree.resolve(".factory"));
        Files.writeString(worktree.resolve(".factory/description.md"), content);
    }

    @Test
    void buildsTheBodyWithTheDescription() throws IOException {
        writeDescription("The parser reads the interval.\n");

        String body = PullRequestDescription.bodyFor(7, worktree);

        assertThat(body).isEqualTo("Fixes #7\n\n## The change\n\nThe parser reads the interval.\n\n" + LAST_LINE);
    }

    @Test
    void usesTheFallbackForAMissingFile() {
        assertThat(PullRequestDescription.bodyFor(7, worktree))
                .isEqualTo("Fixes #7\n\n## The change\n\n" + FALLBACK + "\n\n" + LAST_LINE);
    }

    @Test
    void usesTheFallbackForAnEmptyFile() throws IOException {
        writeDescription("  \n");

        assertThat(PullRequestDescription.bodyFor(7, worktree)).contains("\n\n" + FALLBACK + "\n\n");
    }

    @Test
    void usesTheFallbackForASymbolicLink() throws IOException {
        Path target = worktree.resolve("secret.txt");
        Files.writeString(target, "A secret.");
        Files.createDirectories(worktree.resolve(".factory"));
        Files.createSymbolicLink(worktree.resolve(".factory/description.md"), target);

        String body = PullRequestDescription.bodyFor(7, worktree);

        assertThat(body).contains(FALLBACK).doesNotContain("A secret.");
    }

    @Test
    void usesTheFallbackForAFileOverOneMegabyte() throws IOException {
        writeDescription("a".repeat(1_000_001));

        assertThat(PullRequestDescription.bodyFor(7, worktree)).contains(FALLBACK);
    }

    @Test
    void neutralizesTheClosingKeywordsOfTheDescription() throws IOException {
        writeDescription("This fixes #12 and Closes: #13 and RESOLVED  #14.");

        String body = PullRequestDescription.bodyFor(7, worktree);

        assertThat(body).contains("This fixes issue 12 and Closes issue 13 and RESOLVED issue 14.")
                .startsWith("Fixes #7\n");
    }

    @Test
    void cutsADescriptionOverTwentyThousandCharacters() throws IOException {
        writeDescription("a".repeat(20_001));

        String body = PullRequestDescription.bodyFor(7, worktree);

        assertThat(body).contains("a".repeat(20_000) + "\n\nThe description is too long; the factory cut it.")
                .doesNotContain("a".repeat(20_001));
    }
}
