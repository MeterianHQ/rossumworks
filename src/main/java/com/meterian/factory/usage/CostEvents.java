package com.meterian.factory.usage;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meterian.factory.events.WorkerEvents;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
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

    /**
     * Replaces the bytes that are not UTF-8, and ignores each line that is not a cost event. Thus one bad line
     * does not hide the cost of the other lines.
     *
     * @throws IOException when the file cannot be read: then the cost of the day is not known
     */
    public BigDecimal readCostOf(LocalDate day, ZoneId zone) throws IOException {
        if (!Files.exists(eventsFile)) {
            return BigDecimal.ZERO;
        }
        try (BufferedReader reader = openReplacingBadBytes(); Stream<String> lines = reader.lines()) {
            return lines.map(line -> readCostOfLine(line, day, zone)).reduce(BigDecimal.ZERO, BigDecimal::add);
        } catch (UncheckedIOException exception) {
            throw exception.getCause();
        }
    }

    private BufferedReader openReplacingBadBytes() throws IOException {
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE);
        return new BufferedReader(new InputStreamReader(Files.newInputStream(eventsFile), decoder));
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
