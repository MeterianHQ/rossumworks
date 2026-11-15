package com.meterian.factory.board;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Tells if the owner asked the factory to stop. A stop interrupts no step: each loop ends after its current step and
 * the move to the next phase, and no issue starts. A local marker file keeps the stop after a restart of the worker.
 */
public final class FactorySwitch {

    static final String MARKER_NAME = "factory-stopped";
    /** The marker of the earlier "Pick up" pause. A paused factory stays stopped after the upgrade. */
    static final String EARLIER_MARKER_NAME = "pick-up-paused";

    private final Path markerFile;
    private final Path earlierMarkerFile;
    private boolean stopRequested;

    public FactorySwitch(Path workDirectory) {
        this.markerFile = workDirectory.resolve(MARKER_NAME);
        this.earlierMarkerFile = workDirectory.resolve(EARLIER_MARKER_NAME);
        this.stopRequested = Files.exists(markerFile) || Files.exists(earlierMarkerFile);
    }

    public synchronized boolean isStopRequested() {
        return stopRequested;
    }

    /** @throws UncheckedIOException when the marker file cannot be written: the state does not change */
    public synchronized void requestStop() {
        try {
            Files.createDirectories(markerFile.getParent());
            Files.writeString(markerFile, "");
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
        stopRequested = true;
    }

    /** @throws UncheckedIOException when a marker file cannot be deleted: the state does not change */
    public synchronized void start() {
        try {
            Files.deleteIfExists(markerFile);
            Files.deleteIfExists(earlierMarkerFile);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
        stopRequested = false;
    }
}
