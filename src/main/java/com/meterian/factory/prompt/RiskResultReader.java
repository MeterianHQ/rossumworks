package com.meterian.factory.prompt;

import com.fasterxml.jackson.databind.JsonNode;
import com.meterian.factory.state.RiskLevel;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Reads the result file of the risk agent. Fails closed: an unclear file is high risk and not small. */
public final class RiskResultReader {

    private RiskResultReader() {
    }

    public static RiskResult read(Path worktree) {
        return ResultFileReader.readObject(worktree.resolve(".factory").resolve("risk.json"))
                .flatMap(RiskResultReader::parse)
                .orElseGet(RiskResultReader::highRisk);
    }

    private static Optional<RiskResult> parse(JsonNode node) {
        Optional<RiskLevel> risk = parseRisk(node.path("risk").asText(""));
        if (risk.isEmpty() || !node.path("small").isBoolean()) {
            return Optional.empty();
        }
        List<String> reasons = new ArrayList<>();
        node.path("reasons").forEach(reason -> reasons.add(reason.asText("")));
        return Optional.of(new RiskResult(risk.get(), node.get("small").asBoolean(), reasons));
    }

    private static Optional<RiskLevel> parseRisk(String text) {
        return switch (text) {
            case "low" -> Optional.of(RiskLevel.LOW);
            case "medium" -> Optional.of(RiskLevel.MEDIUM);
            case "high" -> Optional.of(RiskLevel.HIGH);
            default -> Optional.empty();
        };
    }

    private static RiskResult highRisk() {
        return new RiskResult(RiskLevel.HIGH, false, List.of("The risk agent wrote no valid result"));
    }
}
