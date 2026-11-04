package com.meterian.factory.agent;

import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;

/**
 * Tells at once when a continue marker appears in the live folder. The hook of /factory:continue only writes a file,
 * so without this watcher the worker and the page see the request only at the next poll.
 */
public final class ContinueMarkerWatcher implements AutoCloseable {

    private static final System.Logger LOGGER = System.getLogger(ContinueMarkerWatcher.class.getName());

    private final Path liveDirectory;
    private final Runnable continueRequestListener;
    private WatchService watchService;

    public ContinueMarkerWatcher(Path liveDirectory, Runnable continueRequestListener) {
        this.liveDirectory = liveDirectory;
        this.continueRequestListener = continueRequestListener;
    }

    /** The watch is active when this method returns. */
    public void start() throws IOException {
        watchService = liveDirectory.getFileSystem().newWatchService();
        liveDirectory.register(watchService, StandardWatchEventKinds.ENTRY_CREATE);
        Thread.ofPlatform().daemon().name("continue-marker-watcher").start(this::watchUntilClosed);
    }

    @Override
    public void close() {
        if (watchService == null) {
            return;
        }
        try {
            watchService.close();
        } catch (IOException exception) {
            LOGGER.log(System.Logger.Level.WARNING, "The watch of the continue markers did not close.", exception);
        }
    }

    private void watchUntilClosed() {
        try {
            boolean isWatching = true;
            while (isWatching) {
                WatchKey key = watchService.take();
                callListenerForNewMarkers(key);
                isWatching = key.reset();
            }
        } catch (ClosedWatchServiceException exception) {
            LOGGER.log(System.Logger.Level.DEBUG, "The watch of the continue markers ended.");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private void callListenerForNewMarkers(WatchKey key) {
        if (key.pollEvents().stream().anyMatch(ContinueMarkerWatcher::canBeANewMarker)) {
            callListener();
        }
    }

    /** After an overflow the file names are lost, so the listener gets the call. */
    private static boolean canBeANewMarker(WatchEvent<?> event) {
        return event.kind() == StandardWatchEventKinds.OVERFLOW
                || LiveFiles.isContinueMarkerName(event.context().toString());
    }

    /** A failure of the listener never ends the watch: the next poll still sees the marker. */
    private void callListener() {
        try {
            continueRequestListener.run();
        } catch (RuntimeException exception) {
            LOGGER.log(System.Logger.Level.WARNING, "The listener of the continue markers failed.", exception);
        }
    }
}
