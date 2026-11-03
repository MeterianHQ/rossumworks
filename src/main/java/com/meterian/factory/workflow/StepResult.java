package com.meterian.factory.workflow;

import com.meterian.factory.prompt.Finding;
import com.meterian.factory.state.IssueRecord;
import java.util.List;
import java.util.Optional;

/** The result of one step of an issue. The engine finds the next state of the issue from it. */
sealed interface StepResult {

    /** The step ends with an outcome; the engine applies the transition of the outcome. */
    record Outcome(IssueRecord record, String outcome, Optional<String> reason, List<Finding> findings)
            implements StepResult {

        public Outcome {
            findings = List.copyOf(findings);
        }
    }

    /** The step does not end now, for example at the usage limit; the record has no transition. */
    record Stay(IssueRecord record) implements StepResult {
    }

    /** A guard of the step stops the issue: Blocked with the wait reason FAILURE and the reason as a note. */
    record Blocked(IssueRecord record, String reason) implements StepResult {
    }

    static StepResult endWith(IssueRecord record, String outcome) {
        return new Outcome(record, outcome, Optional.empty(), List.of());
    }

    static StepResult endWithReason(IssueRecord record, String outcome, String reason) {
        return new Outcome(record, outcome, Optional.of(reason), List.of());
    }

    static StepResult endWithFindings(IssueRecord record, String outcome, List<Finding> findings) {
        return new Outcome(record, outcome, Optional.empty(), findings);
    }

    static StepResult stay(IssueRecord record) {
        return new Stay(record);
    }

    static StepResult block(IssueRecord record, String reason) {
        return new Blocked(record, reason);
    }
}
