package com.meterian.factory.events;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Writes one JSON line for each event of the worker, so that an agent can monitor the worker. A line has the
 * fields time, type, and issue, and then the fields of its type. A line never has a secret or a prompt text.
 */
public final class WorkerEvents {

    private static final System.Logger LOGGER = System.getLogger(WorkerEvents.class.getName());
    private static final ObjectMapper JSON = new ObjectMapper();

    private final EventLines lines;
    private final Clock clock;
    /** The end of the newest wait for each reason. Only a later end is a new wait. */
    private final Map<String, Instant> waitEnds = new HashMap<>();

    public WorkerEvents(EventLines lines, Clock clock) {
        this.lines = lines;
        this.clock = clock;
    }

    public void writeWorkerStart(String version, String repository) {
        writeWorkerEvent("start", version, repository);
    }

    public void writeWorkerStop(String version, String repository) {
        writeWorkerEvent("stop", version, repository);
    }

    /**
     * @param event   empty for a move without an event: a limit, a move of on-any-state, or a move of the worker
     * @param entries the count and the limit of each state with a limit, for example "1/3"
     */
    public void writeMove(int issueNumber, String fromState, Optional<String> event, String toState, String column,
                           Map<String, String> entries) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("from", fromState);
        event.ifPresent(name -> fields.put("event", name));
        fields.put("to", toState);
        fields.put("column", column);
        fields.put("entries", entries);
        write("move", Optional.of(issueNumber), fields);
    }

    /**
     * @param result       the event of the action, or "blocked", "moved", and "error" for the other ends
     * @param actionFields the fields of the action, for example the exit code of a build
     */
    public void writeActionEnd(int issueNumber, String state, String action, String result, Duration duration,
                            Map<String, Object> actionFields) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("state", state);
        fields.put("action", action);
        fields.put("result", result);
        fields.put("duration_s", duration.toSeconds());
        fields.putAll(actionFields);
        write("action-end", Optional.of(issueNumber), fields);
    }

    public void writePullRequestOpen(int issueNumber, String url) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("event", "opened");
        fields.put("url", url);
        write("pr", Optional.of(issueNumber), fields);
    }

    /** @param commit the merge commit, empty when GitHub does not give it */
    public void writePullRequestMerge(int issueNumber, String url, Optional<String> commit) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("event", "merged");
        fields.put("url", url);
        commit.ifPresent(sha -> fields.put("commit", sha));
        write("pr", Optional.of(issueNumber), fields);
    }

    /** The reason is the note of the block: the line gets only its first line. */
    public void writeBlock(int issueNumber, String reason) {
        write("blocked", Optional.of(issueNumber), Map.of("reason", firstLineOf(reason)));
    }

    /** The question comes from the agent: the line gets only its first line. */
    public void writeQuestion(int issueNumber, String question) {
        write("question", Optional.of(issueNumber), Map.of("first_line", firstLineOf(question)));
    }

    /** Several threads report the same wait. Only a later end of the wait writes a new line. */
    public void writeWait(String reason, Instant until) {
        synchronized (waitEnds) {
            Instant knownEnd = waitEnds.get(reason);
            if (knownEnd != null && !until.isAfter(knownEnd)) {
                return;
            }
            waitEnds.put(reason, until);
        }
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("reason", reason);
        fields.put("until", until.truncatedTo(ChronoUnit.SECONDS).toString());
        write("wait", Optional.empty(), fields);
    }

    private void writeWorkerEvent(String event, String version, String repository) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("event", event);
        fields.put("version", version);
        fields.put("repository", repository);
        write("worker", Optional.empty(), fields);
    }

    private void write(String type, Optional<Integer> issueNumber, Map<String, Object> fields) {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("time", clock.instant().truncatedTo(ChronoUnit.SECONDS).toString());
        line.put("type", type);
        issueNumber.ifPresent(number -> line.put("issue", number));
        line.putAll(fields);
        try {
            lines.append(JSON.writeValueAsString(line));
        } catch (JsonProcessingException exception) {
            LOGGER.log(System.Logger.Level.WARNING, "The event " + type + " has no JSON form: "
                    + exception.getMessage());
        }
    }

    private static String firstLineOf(String text) {
        return text.strip().lines().findFirst().orElse("");
    }
}
