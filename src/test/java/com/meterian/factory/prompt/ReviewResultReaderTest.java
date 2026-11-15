package com.meterian.factory.prompt;

import static org.assertj.core.api.Assertions.assertThat;

import com.meterian.factory.state.Verdict;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReviewResultReaderTest {

    @TempDir
    Path worktree;

    private void writeReview(String content) throws IOException {
        Files.createDirectories(worktree.resolve(".factory-work"));
        Files.writeString(worktree.resolve(".factory-work").resolve("review-correctness.json"), content);
    }

    @Test
    void readsTheResultFileOfANamedReview() throws IOException {
        Files.createDirectories(worktree.resolve(".factory-work"));
        Files.writeString(worktree.resolve(".factory-work").resolve("review-code-style.json"),
                "{\"verdict\": \"approve\", \"tree\": \"abc\", \"findings\": []}");

        assertThat(ReviewResultReader.read(worktree, "code-style").verdict()).isEqualTo(Verdict.APPROVE);
        assertThat(ReviewResultReader.read(worktree, "correctness").verdict()).isEqualTo(Verdict.REJECT);
    }

    @Test
    void readsAnApprovingReview() throws IOException {
        writeReview("{\"verdict\": \"approve\", \"tree\": \"abc\", \"findings\": []}");

        ReviewResult result = ReviewResultReader.read(worktree, "correctness");

        assertThat(result.verdict()).isEqualTo(Verdict.APPROVE);
        assertThat(result.treeHash()).isEqualTo("abc");
        assertThat(result.findings()).isEmpty();
    }

    @Test
    void readsTheFindingsOfARejectingReview() throws IOException {
        writeReview("{\"verdict\": \"reject\", \"tree\": \"abc\", \"findings\": [{\"title\": \"T\", \"detail\": \"D\"}]}");

        ReviewResult result = ReviewResultReader.read(worktree, "correctness");

        assertThat(result.verdict()).isEqualTo(Verdict.REJECT);
        assertThat(result.findings()).containsExactly(new Finding("T", "D"));
    }

    @Test
    void aMissingReviewFileIsAReject() {
        ReviewResult result = ReviewResultReader.read(worktree, "correctness");

        assertThat(result.verdict()).isEqualTo(Verdict.REJECT);
        assertThat(result.findings()).extracting(Finding::title).containsExactly("The review wrote no valid result");
    }

    @Test
    void anInvalidReviewFileIsAReject() throws IOException {
        writeReview("{");

        assertThat(ReviewResultReader.read(worktree, "correctness").verdict()).isEqualTo(Verdict.REJECT);
    }

    @Test
    void anUnknownVerdictIsAReject() throws IOException {
        writeReview("{\"verdict\": \"maybe\", \"tree\": \"abc\", \"findings\": []}");

        assertThat(ReviewResultReader.read(worktree, "correctness").verdict()).isEqualTo(Verdict.REJECT);
    }

    @Test
    void aFileLargerThanOneMegabyteIsAReject() throws IOException {
        writeReview("{\"verdict\": \"approve\", \"tree\": \"abc\", \"findings\": [], \"x\": \"" + "a".repeat(1_100_000) + "\"}");

        assertThat(ReviewResultReader.read(worktree, "correctness").verdict()).isEqualTo(Verdict.REJECT);
    }

    @Test
    void anApprovalWithFindingsIsAReject() throws IOException {
        writeReview("{\"verdict\": \"approve\", \"tree\": \"abc\", \"findings\": [{\"title\": \"T\", \"detail\": \"D\"}]}");

        ReviewResult result = ReviewResultReader.read(worktree, "correctness");

        assertThat(result.verdict()).isEqualTo(Verdict.REJECT);
        assertThat(result.findings()).containsExactly(new Finding("T", "D"));
    }

    @Test
    void aSymbolicLinkIsAReject() throws IOException {
        Path real = worktree.resolve("real.json");
        Files.writeString(real, "{\"verdict\": \"approve\", \"tree\": \"abc\", \"findings\": []}");
        Files.createDirectories(worktree.resolve(".factory-work"));
        Files.createSymbolicLink(worktree.resolve(".factory-work").resolve("review-correctness.json"), real);

        assertThat(ReviewResultReader.read(worktree, "correctness").verdict()).isEqualTo(Verdict.REJECT);
    }

    @Test
    void anApprovalWithNotesAndNoFindingsIsAnApprovalWithTheNotes() throws IOException {
        writeReview("{\"verdict\": \"approve\", \"tree\": \"abc\", \"findings\": [],"
                + " \"notes\": [{\"title\": \"Info\", \"detail\": \"A later idea.\"}]}");

        ReviewResult result = ReviewResultReader.read(worktree, "correctness");

        assertThat(result.verdict()).isEqualTo(Verdict.APPROVE);
        assertThat(result.findings()).isEmpty();
        assertThat(result.notes()).containsExactly(new Finding("Info", "A later idea."));
    }

    @Test
    void anApprovalWithFindingsAndNotesIsAReject() throws IOException {
        writeReview("{\"verdict\": \"approve\", \"tree\": \"abc\", \"findings\": [{\"title\": \"T\", \"detail\": \"D\"}],"
                + " \"notes\": [{\"title\": \"Info\", \"detail\": \"A later idea.\"}]}");

        assertThat(ReviewResultReader.read(worktree, "correctness").verdict()).isEqualTo(Verdict.REJECT);
    }

    @Test
    void aRejectWithNotesIsAReject() throws IOException {
        writeReview("{\"verdict\": \"reject\", \"tree\": \"abc\", \"findings\": [],"
                + " \"notes\": [{\"title\": \"Info\", \"detail\": \"A later idea.\"}]}");

        ReviewResult result = ReviewResultReader.read(worktree, "correctness");

        assertThat(result.verdict()).isEqualTo(Verdict.REJECT);
        assertThat(result.notes()).containsExactly(new Finding("Info", "A later idea."));
    }

    @Test
    void aFileWithoutNotesHasNoNotes() throws IOException {
        writeReview("{\"verdict\": \"approve\", \"tree\": \"abc\", \"findings\": []}");

        ReviewResult result = ReviewResultReader.read(worktree, "correctness");

        assertThat(result.verdict()).isEqualTo(Verdict.APPROVE);
        assertThat(result.notes()).isEmpty();
    }

    @Test
    void anInvalidNotesListIsReadAsNoNotes() throws IOException {
        writeReview("{\"verdict\": \"approve\", \"tree\": \"abc\", \"findings\": [], \"notes\": \"none\"}");

        ReviewResult result = ReviewResultReader.read(worktree, "correctness");

        assertThat(result.verdict()).isEqualTo(Verdict.APPROVE);
        assertThat(result.notes()).isEmpty();
    }
}
