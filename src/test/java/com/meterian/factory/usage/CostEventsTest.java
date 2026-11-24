package com.meterian.factory.usage;

import static org.assertj.core.api.Assertions.assertThat;

import com.meterian.factory.events.EventsFile;
import com.meterian.factory.events.WorkerEvents;
import com.meterian.factory.testing.MutableClock;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CostEventsTest {

    private static final Instant YESTERDAY_EVENING = Instant.parse("2026-11-22T23:30:00Z");

    @TempDir
    Path directory;

    private final MutableClock clock = new MutableClock(YESTERDAY_EVENING);

    private CostEvents createCostEvents() {
        Path file = directory.resolve(EventsFile.FILE_NAME);
        return new CostEvents(file, new WorkerEvents(new EventsFile(file), clock));
    }

    @Test
    void writesOneCostEventForEachSession() throws Exception {
        createCostEvents().write(7, new BigDecimal("0.25"));

        assertThat(Files.readString(directory.resolve(EventsFile.FILE_NAME)))
                .isEqualTo("{\"time\":\"2026-11-22T23:30:00Z\",\"type\":\"cost\",\"issue\":7,\"cost_usd\":0.25}\n");
    }

    @Test
    void addsUpOnlyTheCostEventsOfTheDay() {
        CostEvents costEvents = createCostEvents();
        costEvents.write(7, new BigDecimal("5.00"));
        clock.advance(Duration.ofHours(1));
        costEvents.write(7, new BigDecimal("0.25"));
        costEvents.write(8, new BigDecimal("1.50"));

        BigDecimal cost = costEvents.readCostOf(LocalDate.parse("2026-11-23"), ZoneOffset.UTC);

        assertThat(cost).isEqualByComparingTo("1.75");
    }

    @Test
    void ignoresALineThatItCannotRead() throws Exception {
        CostEvents costEvents = createCostEvents();
        clock.advance(Duration.ofHours(1));
        costEvents.write(7, new BigDecimal("0.25"));
        Files.writeString(directory.resolve(EventsFile.FILE_NAME), "not json\n", StandardOpenOption.APPEND);

        BigDecimal cost = costEvents.readCostOf(LocalDate.parse("2026-11-23"), ZoneOffset.UTC);

        assertThat(cost).isEqualByComparingTo("0.25");
    }

    @Test
    void theCostIsZeroWithoutAFile() {
        assertThat(createCostEvents().readCostOf(LocalDate.parse("2026-11-23"), ZoneOffset.UTC)).isZero();
    }
}
