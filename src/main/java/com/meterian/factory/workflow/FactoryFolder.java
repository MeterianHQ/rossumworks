package com.meterian.factory.workflow;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meterian.factory.prompt.Finding;
import com.meterian.factory.prompt.ReviewResultReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/** The files of the factory in the scratch folder of a worktree. Git does not commit this folder. */
final class FactoryFolder {

    private static final String QUESTION_FILE = "question.md";
    private static final String PENDING_FINDINGS_FILE = "pending-findings.json";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<List<Finding>> FINDINGS_TYPE = new TypeReference<>() {
    };
    private static final String RISK_FILE = "risk.json";

    private final Path folder;

    FactoryFolder(Path worktree) {
        this.folder = worktree.resolve(ScratchFolder.NAME);
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

    /** Keeps the findings of the pending prompt for the next coding action, also after a restart of the worker. */
    void writePendingFindings(List<Finding> findings) {
        try {
            Files.createDirectories(folder);
            Files.writeString(folder.resolve(PENDING_FINDINGS_FILE), MAPPER.writeValueAsString(findings),
                    StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new UncheckedIOException("Cannot write the pending findings in " + folder, exception);
        }
    }

    /** Empty when the file does not exist. */
    Optional<List<Finding>> readPendingFindings() {
        Path file = folder.resolve(PENDING_FINDINGS_FILE);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            return Optional.empty();
        }
        try {
            return Optional.of(MAPPER.readValue(read(file), FINDINGS_TYPE));
        } catch (IOException exception) {
            throw new UncheckedIOException("Cannot read the pending findings in " + file, exception);
        }
    }

    void deleteReviewResult(String reviewName) {
        delete(folder.resolve(ReviewResultReader.fileNameOf(reviewName)));
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
