package com.meterian.factory.state;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.meterian.factory.definition.BuiltInDefinition;
import com.meterian.factory.definition.StateDefinition;
import com.meterian.factory.definition.WorkflowDefinition;
import java.io.IOException;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Reads a record in the format of main as a record of the default workflow. A record of main has no definition
 * commit, so it runs with the built-in definition, which is the default workflow.
 */
public final class MainRecordReader {

    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new Jdk8Module());
    private static final WorkflowDefinition WORKFLOW = BuiltInDefinition.load().workflow();
    private static final Map<String, String> STATE_OF_WAIT_REASON = Map.of(
            "QUESTION", "live",
            "LIVE_CONVERSATION", "live",
            "MERGE_NEEDS_APPROVAL", "waits-for-approval",
            "FACTORY_CHANGE_NEEDS_APPROVAL", "factory-change-approval",
            "ISSUE_TEXT_CHANGED", "text-changed",
            "BLOCKED_BY_OWNER", "blocked",
            "FIX_LOOP_LIMIT", "blocked",
            "FAILURE", "blocked");
    private static final Map<String, String> STATE_OF_PHASE = Map.of(
            "PREPARE", "prepare",
            "IMPLEMENT", "implement",
            "BUILD", "build",
            "REVIEW", "review",
            "RISK", "risk",
            "MERGE", "merge",
            "DONE", "done");
    private static final String QUEUED_STATUS = "QUEUED";
    private static final String QUEUED_COLUMN = "queued";
    private static final String IMPLEMENT_PHASE = "IMPLEMENT";
    private static final String PREPARE_PHASE = "PREPARE";

    private MainRecordReader() {
    }

    /** Empty when the JSON is not a valid record of main. Then the record stays broken: the worker fails closed. */
    public static Optional<IssueRecord> read(JsonNode json) {
        try {
            return convert(json);
        } catch (IllegalArgumentException | IllegalStateException | IOException exception) {
            return Optional.empty();
        }
    }

    private static Optional<IssueRecord> convert(JsonNode json) throws IOException {
        if (!json.isObject()) {
            return Optional.empty();
        }
        String phase = json.path("phase").asText("");
        Optional<String> state = chooseState(json, phase);
        String phaseState = STATE_OF_PHASE.get(phase);
        if (state.isEmpty() || phaseState == null) {
            return Optional.empty();
        }
        StateDefinition definition = WORKFLOW.state(state.get());
        int fixLoops = json.path("fixLoops").asInt(0);
        ObjectNode record = ((ObjectNode) json).deepCopy();
        removeKeysOfMain(record);
        record.put("state", definition.name());
        record.put("column", isQueued(json) ? QUEUED_COLUMN : definition.column());
        definition.actionType().ifPresent(type -> record.put("action", type.yamlName()));
        record.put("previousState", phaseState);
        record.put("actionStarted", false);
        record.set("entries", createEntries(phase, fixLoops));
        // Main keeps the fix prompt in .factory/fix-prompt.md, not in the findings file: the implement prompt runs.
        if (phase.equals(IMPLEMENT_PHASE) && fixLoops > 0) {
            record.put("pendingPrompt", PendingPrompt.IMPLEMENT.name());
        }
        record.set("reviews", lowerReviewNames(json.path("reviews")));
        record.set("live", convertLive(json.path("live")));
        return Optional.of(MAPPER.treeToValue(record, IssueRecord.class));
    }

    private static Optional<String> chooseState(JsonNode json, String phase) {
        if (hasLiveSession(json)) {
            return Optional.of("live");
        }
        JsonNode waitReason = json.path("waitReason");
        if (!waitReason.isNull() && !waitReason.isMissingNode()) {
            return Optional.ofNullable(STATE_OF_WAIT_REASON.get(waitReason.asText()));
        }
        String status = json.path("status").asText("");
        if (status.equals("BLOCKED") || status.equals("WAITS_FOR_YOU")) {
            return Optional.empty();
        }
        return Optional.ofNullable(STATE_OF_PHASE.get(phase));
    }

    private static boolean hasLiveSession(JsonNode json) {
        JsonNode live = json.path("live");
        return !live.isNull() && !live.isMissingNode();
    }

    private static boolean isQueued(JsonNode json) {
        return json.path("status").asText("").equals(QUEUED_STATUS);
    }

    private static void removeKeysOfMain(ObjectNode record) {
        record.remove(List.of("phase", "status", "waitReason", "fixLoops"));
    }

    /** The first entry counts, so N fix loops are N + 1 entries. A record in PREPARE did not enter implement. */
    private static ObjectNode createEntries(String phase, int fixLoops) {
        ObjectNode entries = MAPPER.createObjectNode();
        boolean enteredImplement = !phase.equals(PREPARE_PHASE) && (!phase.equals(IMPLEMENT_PHASE) || fixLoops > 0);
        if (enteredImplement) {
            entries.put("implement", fixLoops + 1);
        }
        return entries;
    }

    private static ObjectNode lowerReviewNames(JsonNode reviews) {
        ObjectNode lowered = MAPPER.createObjectNode();
        Iterator<Map.Entry<String, JsonNode>> fields = reviews.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            lowered.set(field.getKey().toLowerCase(), field.getValue());
        }
        return lowered;
    }

    /** The statuses before the conversation are dropped. The state live replaces them. */
    private static JsonNode convertLive(JsonNode live) {
        if (!live.isObject()) {
            return MAPPER.nullNode();
        }
        ObjectNode converted = ((ObjectNode) live).deepCopy();
        converted.remove(List.of("statusBefore", "waitBefore"));
        return converted;
    }
}
