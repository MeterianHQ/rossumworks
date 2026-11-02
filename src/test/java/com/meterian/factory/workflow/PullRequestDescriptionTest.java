package com.meterian.factory.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import com.meterian.factory.prompt.Finding;
import com.meterian.factory.state.ReviewKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PullRequestDescriptionTest {

    private static final String FALLBACK = "The agent wrote no description.";
    private static final String LAST_LINE = "The factory made this change for the issue #7.";

    @TempDir
    Path worktree;

    private void writeDescription(String content) throws IOException {
        Files.createDirectories(worktree.resolve(".factory-work"));
        Files.writeString(worktree.resolve(".factory-work/description.md"), content);
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
        Files.createDirectories(worktree.resolve(".factory-work"));
        Files.createSymbolicLink(worktree.resolve(".factory-work/description.md"), target);

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

    private String describedAs(String description) throws IOException {
        writeDescription(description);
        return PullRequestDescription.bodyFor(7, worktree);
    }

    @Test
    void neutralizesTheClosingKeywordWithAGitHubIssueNumber() throws IOException {
        assertThat(describedAs("It fixes GH-12.")).contains("It fixes issue 12.");
    }

    @Test
    void neutralizesTheClosingKeywordWithARepositoryReference() throws IOException {
        assertThat(describedAs("It fixes o/r#12.")).contains("It fixes issue 12.");
    }

    @Test
    void neutralizesTheClosingKeywordWithAnIssueUrl() throws IOException {
        assertThat(describedAs("It fixes https://github.com/o/r/issues/12 now."))
                .contains("It fixes issue 12 now.");
    }

    @Test
    void neutralizesAClosingKeywordWithAColonAtTheStart() throws IOException {
        assertThat(describedAs("Fix: #12")).contains("\n\nFix issue 12\n\n");
    }

    @Test
    void neutralizesAClosingKeywordWithANoBreakSpace() throws IOException {
        assertThat(describedAs("It fixes\u00A0#12.")).contains("It fixes issue 12.");
    }

    @Test
    void keepsAUrlToAPullRequest() throws IOException {
        assertThat(describedAs("It fixes https://github.com/o/r/pull/12 now."))
                .contains("It fixes https://github.com/o/r/pull/12 now.");
    }

    @Test
    void reportsNoDescriptionForAMissingFile() {
        assertThat(PullRequestDescription.bodyIfDescribed(7, worktree)).isEmpty();
    }

    private static final String BODY = "Fixes #7\n\n## The change\n\nThe change.\n\n" + LAST_LINE;

    private static ReviewNote note(ReviewKind kind, String title, String detail) {
        return new ReviewNote(kind, new Finding(title, detail));
    }

    @Test
    void addsTheNotesOfTheReviewsAfterTheChange() {
        String body = PullRequestDescription.withReviewNotes(BODY, List.of(
                note(ReviewKind.SECURITY, "Info", "No secret."), note(ReviewKind.QUALITY, "Minor", "A long name.")));

        assertThat(body).isEqualTo(BODY + "\n\n## Notes of the reviews\n\n"
                + "- security: Info: No secret.\n- quality: Minor: A long name.");
    }

    @Test
    void replacesTheNotesOfAnEarlierRound() {
        String earlier = PullRequestDescription.withReviewNotes(BODY, List.of(note(ReviewKind.SECURITY, "Old", "Old.")));

        String body = PullRequestDescription.withReviewNotes(earlier, List.of(note(ReviewKind.QUALITY, "New", "New.")));

        assertThat(body).isEqualTo(BODY + "\n\n## Notes of the reviews\n\n- quality: New: New.");
    }

    @Test
    void addsNoSectionWithoutANote() {
        String earlier = PullRequestDescription.withReviewNotes(BODY, List.of(note(ReviewKind.SECURITY, "Old", "Old.")));

        assertThat(PullRequestDescription.withReviewNotes(BODY, List.of())).isEqualTo(BODY);
        assertThat(PullRequestDescription.withReviewNotes(earlier, List.of())).isEqualTo(BODY);
    }

    @Test
    void writesEachNoteOnOneLineWithoutAClosingKeyword() {
        String body = PullRequestDescription.withReviewNotes(BODY, List.of(
                note(ReviewKind.CORRECTNESS, "Closes #12", "First line.\n## A heading\r\nFixes o/r#13.")));

        assertThat(body).endsWith("\n\n- correctness: Closes issue 12: First line. ## A heading Fixes issue 13.");
    }

    @Test
    void cutsANoteOverOneThousandCharacters() {
        String body = PullRequestDescription.withReviewNotes(BODY, List.of(
                note(ReviewKind.CORRECTNESS, "Long", "a".repeat(2_000))));

        String line = body.substring(body.lastIndexOf("\n") + 1);
        assertThat(line).isEqualTo("- correctness: "
                + ("Long: " + "a".repeat(2_000)).substring(0, PullRequestDescription.MAXIMUM_NOTE_CHARACTERS) + " (cut)");
    }

    @Test
    void keepsTheTextAfterANotesHeadingOfTheAgent() throws IOException {
        writeDescription("Intro.\n\n## Notes of the reviews\n\nThe agent text after the heading.");
        String body = PullRequestDescription.bodyFor(7, worktree);
        ReviewNote note = new ReviewNote(ReviewKind.CORRECTNESS, new Finding("Info", "A note."));

        String withNote = PullRequestDescription.withReviewNotes(body, List.of(note));
        String afterARoundWithoutNotes = PullRequestDescription.withReviewNotes(withNote, List.of());

        assertThat(withNote).contains("The agent text after the heading.", LAST_LINE, "- correctness: Info: A note.");
        assertThat(afterARoundWithoutNotes).isEqualTo(body).contains("The agent text after the heading.", LAST_LINE);
    }
}
