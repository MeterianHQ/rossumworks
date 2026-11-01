package com.meterian.factory.workflow;

import com.meterian.factory.state.ReviewKind;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Optional;

/** The files of the factory in the folder .factory of a worktree. Git does not commit this folder. */
final class FactoryFolder {

    private static final String QUESTION_FILE = "question.md";
    private static final String FIX_PROMPT_FILE = "fix-prompt.md";
    private static final String RISK_FILE = "risk.json";

    private final Path folder;

    FactoryFolder(Path worktree) {
        this.folder = worktree.resolve(".factory");
    }

    boolean hasQuestion() {
        return Files.isRegularFile(folder.resolve(QUESTION_FILE), LinkOption.NOFOLLOW_LINKS);
    }

    String readQuestion() {
        return read(folder.resolve(QUESTION_FILE));
    }

    /** The live conversation answers the question, so the merge gate does not see an open question. */
    void deleteQuestion() {
        delete(folder.resolve(QUESTION_FILE));
    }

    /** Keeps the fix prompt for the next implement step, also after a restart of the worker. */
    void writeFixPrompt(String prompt) {
        try {
            Files.createDirectories(folder);
            Files.writeString(folder.resolve(FIX_PROMPT_FILE), prompt, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new UncheckedIOException("Cannot write the fix prompt in " + folder, exception);
        }
    }

    Optional<String> readFixPrompt() {
        Path file = folder.resolve(FIX_PROMPT_FILE);
        return Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) ? Optional.of(read(file)) : Optional.empty();
    }

    void deleteReviewResult(ReviewKind kind) {
        delete(folder.resolve(kind.fileName()));
    }

    void deleteRiskResult() {
        delete(folder.resolve(RISK_FILE));
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new UncheckedIOException("Cannot read the file " + file, exception);
        }
    }

    private static void delete(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException exception) {
            throw new UncheckedIOException("Cannot delete the old file " + file, exception);
        }
    }
}
