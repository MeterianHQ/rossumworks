package com.meterian.factory.prompt;

import com.fasterxml.jackson.databind.JsonNode;
import com.meterian.factory.state.ReviewKind;
import com.meterian.factory.state.Verdict;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Reads the result file of a review. Fails closed: an unclear file is a reject. */
public final class ReviewResultReader {

    private static final String INVALID_TITLE = "The review wrote no valid result";

    private ReviewResultReader() {
    }

    public static ReviewResult read(Path worktree, ReviewKind kind) {
        Path file = worktree.resolve(".factory").resolve(kind.fileName());
        return ResultFileReader.readObject(file)
                .flatMap(ReviewResultReader::parse)
                .orElseGet(ReviewResultReader::rejectInvalid);
    }

    private static Optional<ReviewResult> parse(JsonNode node) {
        Optional<Verdict> verdict = parseVerdict(node.path("verdict").asText(""));
        if (verdict.isEmpty() || !node.path("findings").isArray()) {
            return Optional.empty();
        }
        List<Finding> findings = new ArrayList<>();
        for (JsonNode findingNode : node.get("findings")) {
            findings.add(new Finding(findingNode.path("title").asText(""), findingNode.path("detail").asText("")));
        }
        return Optional.of(new ReviewResult(verdict.get(), node.path("tree").asText(""), findings));
    }

    private static Optional<Verdict> parseVerdict(String text) {
        return switch (text) {
            case "approve" -> Optional.of(Verdict.APPROVE);
            case "reject" -> Optional.of(Verdict.REJECT);
            default -> Optional.empty();
        };
    }

    private static ReviewResult rejectInvalid() {
        return new ReviewResult(Verdict.REJECT, "",
                List.of(new Finding(INVALID_TITLE, "The file is missing, too large, or not valid.")));
    }
}
