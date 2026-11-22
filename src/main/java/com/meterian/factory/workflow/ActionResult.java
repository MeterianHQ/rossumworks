package com.meterian.factory.workflow;

import com.meterian.factory.prompt.Finding;
import com.meterian.factory.state.IssueRecord;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** The result of one action of an issue. The engine finds the next state of the issue from it. */
sealed interface ActionResult {

    /**
     * The action ends with an event; the engine applies the transition of the event.
     *
     * @param actionFields the fields of the action for the event line, for example the exit code of a build
     */
    record Event(IssueRecord record, String event, Optional<String> reason, List<Finding> findings,
                 Map<String, Object> actionFields) implements ActionResult {

        public Event {
            findings = List.copyOf(findings);
            actionFields = Map.copyOf(actionFields);
        }

        public Event(IssueRecord record, String event, Optional<String> reason, List<Finding> findings) {
            this(record, event, reason, findings, Map.of());
        }

        Event withActionFields(Map<String, Object> newActionFields) {
            return new Event(record, event, reason, findings, newActionFields);
        }
    }

    /** The action does not end now, for example at the usage limit; the record has no transition. */
    record Stay(IssueRecord record) implements ActionResult {
    }

    /** The action moves the issue to a named state without an event, for example the continue of a pushed branch. */
    record MoveTo(IssueRecord record, String stateName) implements ActionResult {
    }

    /** A guard of the action stops the issue: Blocked with the wait reason FAILURE and the reason as a note. */
    record Blocked(IssueRecord record, String reason) implements ActionResult {
    }

    static Event endWith(IssueRecord record, String event) {
        return new Event(record, event, Optional.empty(), List.of());
    }

    static Event endWithReason(IssueRecord record, String event, String reason) {
        return new Event(record, event, Optional.of(reason), List.of());
    }

    static Event endWithFindings(IssueRecord record, String event, List<Finding> findings) {
        return new Event(record, event, Optional.empty(), findings);
    }

    static ActionResult stay(IssueRecord record) {
        return new Stay(record);
    }

    static ActionResult moveTo(IssueRecord record, String stateName) {
        return new MoveTo(record, stateName);
    }

    static ActionResult block(IssueRecord record, String reason) {
        return new Blocked(record, reason);
    }
}
