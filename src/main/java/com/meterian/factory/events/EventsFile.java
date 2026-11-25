package com.meterian.factory.events;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
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

    public Path file() {
        return file;
    }

    /**
     * One write call gives the line and its newline, so a reader never sees half a line. Files.write is not
     * enough: it writes a long line in parts of 8 KB.
     */
    @Override
    public synchronized void append(String line) {
        ByteBuffer bytes = ByteBuffer.wrap((line + "\n").getBytes(StandardCharsets.UTF_8));
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                StandardOpenOption.APPEND)) {
            while (bytes.hasRemaining()) {
                channel.write(bytes);
            }
        } catch (IOException | RuntimeException exception) {
            LOGGER.log(System.Logger.Level.WARNING, "The event is not in " + file + ": " + exception.getMessage());
        }
    }
}
