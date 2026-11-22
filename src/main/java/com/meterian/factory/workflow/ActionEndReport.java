package com.meterian.factory.workflow;

import com.meterian.factory.agent.AgentException;
import com.meterian.factory.agent.ApiKeyDetectedException;
import com.meterian.factory.agent.PhaseRefusedException;
import com.meterian.factory.events.WorkerEvents;
import com.meterian.factory.git.GitException;
import com.meterian.factory.github.Issue;
import com.meterian.factory.state.ActionType;
import com.meterian.factory.state.IssueRecord;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * Runs an action and writes the event action-end with its result, its duration, and the fields of the action. An
 * action that stays writes no line, and a state without an action writes no line.
 */
final class ActionEndReport {

    private static final String BLOCKED_RESULT = "blocked";
    private static final String MOVED_RESULT = "moved";
    private static final String ERROR_RESULT = "error";

    private final WorkerEvents events;
    private final Clock clock;

    ActionEndReport(WorkerEvents events, Clock clock) {
        this.events = events;
        this.clock = clock;
    }

    /** A refused phase and an API key are not an end of the action: the worker shuts down. */
    ActionResult run(Action action, Issue issue, IssueRecord record, Path worktree)
            throws AgentException, ApiKeyDetectedException {
        Instant startTime = clock.instant();
        try {
            ActionResult result = action.run(issue, record, worktree);
            report(record, result, startTime);
            return result;
        } catch (PhaseRefusedException refused) {
            throw refused;
        } catch (AgentException | GitException | UncheckedIOException exception) {
            reportError(record, startTime);
            throw exception;
        }
    }

    Optional<ActionResult> start(Action action, Issue issue, IssueRecord record) {
        Instant startTime = clock.instant();
        Optional<ActionResult> result = action.start(issue, record);
        result.ifPresent(found -> report(record, found, startTime));
        return result;
    }

    private void report(IssueRecord record, ActionResult result, Instant startTime) {
        Optional<String> resultName = nameOf(result);
        if (record.action().isEmpty() || resultName.isEmpty()) {
            return;
        }
        ActionType type = record.action().get();
        events.actionEnded(record.issueNumber(), record.state(), type.yamlName(), resultName.get(),
                Duration.between(startTime, clock.instant()), ActionEndFields.describe(type, record, result));
    }

    private void reportError(IssueRecord record, Instant startTime) {
        record.action().ifPresent(type -> events.actionEnded(record.issueNumber(), record.state(), type.yamlName(),
                ERROR_RESULT, Duration.between(startTime, clock.instant()), Map.of()));
    }

    private static Optional<String> nameOf(ActionResult result) {
        return switch (result) {
            case ActionResult.Event event -> Optional.of(event.event());
            case ActionResult.Blocked blocked -> Optional.of(BLOCKED_RESULT);
            case ActionResult.MoveTo moveTo -> Optional.of(MOVED_RESULT);
            case ActionResult.Stay stay -> Optional.empty();
        };
    }
}
