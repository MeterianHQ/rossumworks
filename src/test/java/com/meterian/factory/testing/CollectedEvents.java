package com.meterian.factory.testing;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meterian.factory.events.EventLines;
import com.meterian.factory.events.WorkerEvents;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;

/** Keeps the event lines of a test in memory, so that the test reads them as JSON. */
public final class CollectedEvents implements EventLines {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final List<String> lines = new ArrayList<>();
    private final WorkerEvents workerEvents;

    public CollectedEvents(Clock clock) {
        this.workerEvents = new WorkerEvents(this, clock);
    }

    public CollectedEvents() {
        this(Clock.systemUTC());
    }

    /** Events for a test that does not read them. */
    public static WorkerEvents discarded() {
        return new WorkerEvents(line -> { }, Clock.systemUTC());
    }

    public WorkerEvents workerEvents() {
        return workerEvents;
    }

    @Override
    public synchronized void append(String line) {
        lines.add(line);
    }

    public synchronized List<String> lines() {
        return List.copyOf(lines);
    }

    public synchronized List<JsonNode> ofType(String type) {
        return lines.stream().map(CollectedEvents::parse).filter(line -> line.path("type").asText().equals(type))
                .toList();
    }

    private static JsonNode parse(String line) {
        try {
            return JSON.readTree(line);
        } catch (JsonProcessingException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}
