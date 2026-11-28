package com.meterian.factory.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EventsFileTest {

    /** A strong reference: the log manager keeps a logger only while a reference to it exists. */
    private static final Logger EVENTS_FILE_LOGGER = Logger.getLogger(EventsFile.class.getName());

    @TempDir
    Path workDirectory;

    @Test
    void appendsEachLineWithItsNewline() throws IOException {
        EventsFile file = EventsFile.inWorkFolder(workDirectory);

        file.append("{\"type\":\"worker\"}");
        file.append("{\"type\":\"move\"}");

        assertThat(Files.readString(workDirectory.resolve("events.jsonl")))
                .isEqualTo("{\"type\":\"worker\"}\n{\"type\":\"move\"}\n");
    }

    @Test
    void writesALongLineAsOneCompleteLine() throws IOException {
        String longLine = "{\"reason\":\"" + "x".repeat(100_000) + "\"}";

        EventsFile.inWorkFolder(workDirectory).append(longLine);

        assertThat(Files.readAllLines(workDirectory.resolve("events.jsonl"))).containsExactly(longLine);
    }

    @Test
    void keepsTheLinesOfAnEarlierRun() throws IOException {
        EventsFile.inWorkFolder(workDirectory).append("{\"type\":\"worker\"}");

        EventsFile.inWorkFolder(workDirectory).append("{\"type\":\"move\"}");

        assertThat(Files.readAllLines(workDirectory.resolve("events.jsonl"))).hasSize(2);
    }

    @Test
    void aFailedWriteDoesNotStopTheWorker() throws IOException {
        Files.createDirectory(workDirectory.resolve("events.jsonl"));

        assertThatCode(() -> EventsFile.inWorkFolder(workDirectory).append("{\"type\":\"worker\"}"))
                .doesNotThrowAnyException();
    }

    @Test
    void aFailedWriteGivesAWarningInTheWorkerLog() throws IOException {
        Path eventsPath = Files.createDirectory(workDirectory.resolve("events.jsonl"));
        List<LogRecord> workerLog = new CopyOnWriteArrayList<>();
        Handler workerLogHandler = createHandlerThatCollects(workerLog);
        EVENTS_FILE_LOGGER.addHandler(workerLogHandler);
        try {
            EventsFile.inWorkFolder(workDirectory).append("{\"type\":\"worker\"}");
        } finally {
            EVENTS_FILE_LOGGER.removeHandler(workerLogHandler);
        }

        assertThat(workerLog).singleElement().satisfies(logRecord -> {
            assertThat(logRecord.getLevel()).isEqualTo(Level.WARNING);
            assertThat(logRecord.getMessage()).startsWith("The event is not in " + eventsPath + ": ");
        });
    }

    private static Handler createHandlerThatCollects(List<LogRecord> workerLog) {
        return new Handler() {
            @Override
            public void publish(LogRecord logRecord) {
                workerLog.add(logRecord);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
    }
}
