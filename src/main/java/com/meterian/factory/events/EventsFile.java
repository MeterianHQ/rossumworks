package com.meterian.factory.events;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Appends the event lines to the file events.jsonl in the work folder. A failure to write gives a warning in the
 * worker log, and the worker continues.
 */
public final class EventsFile implements EventLines {

    public static final String FILE_NAME = "events.jsonl";
    private static final System.Logger LOGGER = System.getLogger(EventsFile.class.getName());

    private final Path file;

    public EventsFile(Path file) {
        this.file = file;
    }

    public static EventsFile inWorkFolder(Path workDirectory) {
        return new EventsFile(workDirectory.resolve(FILE_NAME));
    }

    /** One write gives the line and its newline, so a reader never sees half a line. */
    @Override
    public synchronized void append(String line) {
        byte[] bytes = (line + "\n").getBytes(StandardCharsets.UTF_8);
        try {
            Files.write(file, bytes, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
        } catch (IOException | RuntimeException exception) {
            LOGGER.log(System.Logger.Level.WARNING, "The event is not in " + file + ": " + exception.getMessage());
        }
    }
}
