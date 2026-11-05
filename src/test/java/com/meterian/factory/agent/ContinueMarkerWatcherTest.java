package com.meterian.factory.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ContinueMarkerWatcherTest {

    private static final long SHORT_WAIT_MILLISECONDS = 500;

    @TempDir
    Path liveDirectory;

    private final CountDownLatch firstRequest = new CountDownLatch(1);
    private final AtomicInteger requestCount = new AtomicInteger();
    private ContinueMarkerWatcher watcher;

    private void startWatcher() throws IOException {
        watcher = new ContinueMarkerWatcher(liveDirectory, () -> {
            requestCount.incrementAndGet();
            firstRequest.countDown();
        });
        watcher.start();
    }

    @AfterEach
    void closeWatcher() {
        if (watcher != null) {
            watcher.close();
        }
    }

    @Test
    void aNewMarkerCallsTheListener() throws Exception {
        startWatcher();

        new LiveFiles(liveDirectory).requestContinue(425);

        assertThat(firstRequest.await(10, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void aMarkerThatTheHookWritesCallsTheListener() throws Exception {
        startWatcher();

        Files.writeString(new LiveFiles(liveDirectory).continueMarker(425), "Wed Nov  4 10:14:00 GMT 2026\n");

        assertThat(firstRequest.await(10, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void anotherFileDoesNotCallTheListener() throws Exception {
        startWatcher();

        Files.writeString(liveDirectory.resolve("issue-425.settings.json"), "{}");

        assertThat(firstRequest.await(SHORT_WAIT_MILLISECONDS, TimeUnit.MILLISECONDS)).isFalse();
    }

    @Test
    void theDeleteOfAMarkerDoesNotCallTheListener() throws Exception {
        LiveFiles liveFiles = new LiveFiles(liveDirectory);
        liveFiles.requestContinue(425);
        startWatcher();

        liveFiles.clearContinueRequest(425);

        assertThat(firstRequest.await(SHORT_WAIT_MILLISECONDS, TimeUnit.MILLISECONDS)).isFalse();
    }

    @Test
    void aClosedWatcherDoesNotCallTheListener() throws Exception {
        startWatcher();
        watcher.close();

        new LiveFiles(liveDirectory).requestContinue(425);

        assertThat(firstRequest.await(SHORT_WAIT_MILLISECONDS, TimeUnit.MILLISECONDS)).isFalse();
    }
}
