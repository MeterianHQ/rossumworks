package com.meterian.factory.board;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Tells if the worker may start an issue that it did not start yet. An issue in work is not affected. A local marker
 * file keeps the state, so that a restart of the worker never starts work that the owner paused.
 */
public final class PickUpSwitch {

    private final Path markerFile;
    private boolean paused;

    public PickUpSwitch(Path markerFile) {
        this.markerFile = markerFile;
        this.paused = Files.exists(markerFile);
    }

    public synchronized boolean isPaused() {
        return paused;
    }

    /** @throws UncheckedIOException when the marker file cannot be written: the state does not change */
    public synchronized void pause() {
        try {
            Files.createDirectories(markerFile.getParent());
            Files.writeString(markerFile, "");
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
        paused = true;
    }

    /** @throws UncheckedIOException when the marker file cannot be deleted: the state does not change */
    public synchronized void resume() {
        try {
            Files.deleteIfExists(markerFile);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
        paused = false;
    }
}
