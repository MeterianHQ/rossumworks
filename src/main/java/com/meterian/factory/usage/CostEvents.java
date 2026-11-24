package com.meterian.factory.usage;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meterian.factory.events.WorkerEvents;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.stream.Stream;

/** Writes the cost of each session to events.jsonl, and reads the cost of one day from the same file. */
public final class CostEvents {

    private static final System.Logger LOGGER = System.getLogger(CostEvents.class.getName());
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String COST_TYPE = "cost";

    private final Path eventsFile;
    private final WorkerEvents events;

    public CostEvents(Path eventsFile, WorkerEvents events) {
        this.eventsFile = eventsFile;
        this.events = events;
    }

    public void write(int issueNumber, BigDecimal costUsd) {
        events.writeCost(issueNumber, costUsd);
    }

    /** A file that cannot be read gives a warning, and the cost starts at zero. */
    public BigDecimal readCostOf(LocalDate day, ZoneId zone) {
        if (!Files.isRegularFile(eventsFile)) {
            return BigDecimal.ZERO;
        }
        try (Stream<String> lines = Files.lines(eventsFile, StandardCharsets.UTF_8)) {
            return lines.map(line -> readCostOfLine(line, day, zone)).reduce(BigDecimal.ZERO, BigDecimal::add);
        } catch (IOException | UncheckedIOException exception) {
            LOGGER.log(System.Logger.Level.WARNING, "The file " + eventsFile
                    + " cannot be read. The cost of today starts at zero: " + exception.getMessage());
            return BigDecimal.ZERO;
        }
    }

    private BigDecimal readCostOfLine(String line, LocalDate day, ZoneId zone) {
        try {
            JsonNode event = JSON.readTree(line);
            if (event == null || !COST_TYPE.equals(event.path("type").asText())) {
                return BigDecimal.ZERO;
            }
            LocalDate eventDay = LocalDate.ofInstant(Instant.parse(event.path("time").asText()), zone);
            JsonNode cost = event.path("cost_usd");
            return eventDay.equals(day) && cost.isNumber() ? cost.decimalValue() : BigDecimal.ZERO;
        } catch (JsonProcessingException | DateTimeParseException exception) {
            LOGGER.log(System.Logger.Level.WARNING, "A line of " + eventsFile + " cannot be read: "
                    + exception.getMessage());
            return BigDecimal.ZERO;
        }
    }
}
