package com.meterian.factory.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EventsFileTest {

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
}
