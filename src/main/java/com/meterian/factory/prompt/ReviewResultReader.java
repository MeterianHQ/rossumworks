package com.meterian.factory.prompt;

import com.fasterxml.jackson.databind.JsonNode;
import com.meterian.factory.state.Verdict;
import com.meterian.factory.workflow.ScratchFolder;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Reads the result file of a review. Fails closed: an unclear file is a reject. */
public final class ReviewResultReader {

    private static final String INVALID_TITLE = "The review wrote no valid result";

    private ReviewResultReader() {
    }

    /** The name of the result file of a review, for example review-security.json. */
    public static String fileNameOf(String reviewName) {
        return "review-" + reviewName + ".json";
    }

    public static ReviewResult read(Path worktree, String reviewName) {
        Path file = worktree.resolve(ScratchFolder.NAME).resolve(fileNameOf(reviewName));
        return ResultFileReader.readObject(file)
                .flatMap(ReviewResultReader::parseReviewResult)
                .orElseGet(ReviewResultReader::createRejectResult);
    }

    /** An approval with a finding is not consistent, so it stays a reject. A missing notes list is empty. */
    private static Optional<ReviewResult> parseReviewResult(JsonNode node) {
        Optional<Verdict> verdict = parseVerdict(node.path("verdict").asText(""));
        if (verdict.isEmpty() || !node.path("findings").isArray()) {
            return Optional.empty();
        }
        List<Finding> findings = parseFindings(node.get("findings"));
        List<Finding> notes = node.path("notes").isArray() ? parseFindings(node.get("notes")) : List.of();
        Verdict effectiveVerdict = findings.isEmpty() ? verdict.get() : Verdict.REJECT;
        return Optional.of(new ReviewResult(effectiveVerdict, node.path("tree").asText(""), findings, notes));
    }

    private static List<Finding> parseFindings(JsonNode array) {
        List<Finding> findings = new ArrayList<>();
        for (JsonNode findingNode : array) {
            findings.add(new Finding(findingNode.path("title").asText(""), findingNode.path("detail").asText("")));
        }
        return findings;
    }

    private static Optional<Verdict> parseVerdict(String text) {
        return switch (text) {
            case "approve" -> Optional.of(Verdict.APPROVE);
            case "reject" -> Optional.of(Verdict.REJECT);
            default -> Optional.empty();
        };
    }

    private static ReviewResult createRejectResult() {
        return new ReviewResult(Verdict.REJECT, "",
                List.of(new Finding(INVALID_TITLE, "The file is missing, too large, or not valid.")), List.of());
    }
}
