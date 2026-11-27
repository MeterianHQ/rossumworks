package com.meterian.factory.workflow;

import com.meterian.factory.state.ActionType;
import com.meterian.factory.state.IssueRecord;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The fields of an action for the event action-end: the prompt of a coding action, the exit code of a build, the
 * verdicts of the reviews, the risk result, and the reason of a merge gate that needs the owner.
 */
final class ActionEndFields {

    private ActionEndFields() {
    }

    static Map<String, Object> describe(ActionType type, ActionResult result) {
        Map<String, Object> fields = new LinkedHashMap<>();
        if (!(result instanceof ActionResult.Event event)) {
            return fields;
        }
        fields.putAll(event.actionFields());
        if (type == ActionType.RISK) {
            fields.putAll(describeRisk(event.record()));
        }
        if (type == ActionType.MERGE_GATE) {
            event.reason().ifPresent(reason -> fields.put("reason", reason));
        }
        return fields;
    }

    /** Only the first reason goes to the line: the status comment has all of them. */
    private static Map<String, Object> describeRisk(IssueRecord assessed) {
        Map<String, Object> fields = new LinkedHashMap<>();
        assessed.risk().ifPresent(risk -> fields.put("risk", risk.name().toLowerCase()));
        fields.put("small", assessed.small());
        assessed.riskReasons().stream().findFirst().ifPresent(reason -> fields.put("reasons", reason));
        return fields;
    }
}
