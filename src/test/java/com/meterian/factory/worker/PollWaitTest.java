package com.meterian.factory.worker;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class PollWaitTest {

    private static final Duration LONG_INTERVAL = Duration.ofMinutes(10);
    private static final Duration EARLY_DELAY = Duration.ofMillis(200);

    @Test
    void waitsForTheFullInterval() throws InterruptedException {
        PollWait wait = new PollWait(Duration.ofMillis(300), EARLY_DELAY);
        long start = System.nanoTime();

        wait.awaitNextPoll();

        assertThat(elapsedSince(start)).isGreaterThanOrEqualTo(Duration.ofMillis(300));
    }

    @Test
    void anEarlyPollEndsTheWaitAfterTheEarlyDelay() throws Exception {
        PollWait wait = new PollWait(LONG_INTERVAL, EARLY_DELAY);
        long start = System.nanoTime();
        CompletableFuture<Void> waiting = CompletableFuture.runAsync(() -> awaitQuietly(wait));

        wait.requestEarlyPoll();
        waiting.get(5, TimeUnit.SECONDS);

        assertThat(elapsedSince(start)).isGreaterThanOrEqualTo(EARLY_DELAY).isLessThan(Duration.ofSeconds(5));
    }

    @Test
    void anEarlyPollBeforeTheWaitEndsTheNextWait() throws InterruptedException {
        PollWait wait = new PollWait(LONG_INTERVAL, EARLY_DELAY);
        wait.requestEarlyPoll();
        long start = System.nanoTime();

        wait.awaitNextPoll();

        assertThat(elapsedSince(start)).isLessThan(Duration.ofSeconds(5));
    }

    @Test
    void manyEarlyPollsGiveOnlyOneEarlyPoll() throws Exception {
        PollWait wait = new PollWait(Duration.ofMillis(800), Duration.ZERO);
        wait.requestEarlyPoll();
        wait.requestEarlyPoll();
        wait.awaitNextPoll();
        long start = System.nanoTime();

        wait.awaitNextPoll();

        assertThat(elapsedSince(start)).isGreaterThanOrEqualTo(Duration.ofMillis(800));
    }

    @Test
    void aRequestDuringTheEarlyDelayGivesOneMoreEarlyPoll() throws Exception {
        PollWait wait = new PollWait(LONG_INTERVAL, Duration.ofMillis(500));
        wait.requestEarlyPoll();
        CompletableFuture<Void> firstWait = CompletableFuture.runAsync(() -> awaitQuietly(wait));
        Thread.sleep(100);
        wait.requestEarlyPoll();
        firstWait.get(5, TimeUnit.SECONDS);
        long start = System.nanoTime();

        wait.awaitNextPoll();

        assertThat(elapsedSince(start)).isLessThan(Duration.ofSeconds(5));
    }

    @Test
    void aStopEndsTheWaitAtOnce() throws Exception {
        PollWait wait = new PollWait(LONG_INTERVAL, LONG_INTERVAL);
        CompletableFuture<Void> waiting = CompletableFuture.runAsync(() -> awaitQuietly(wait));

        wait.stop();

        waiting.get(5, TimeUnit.SECONDS);
    }

    @Test
    void aStopEndsTheEarlyDelay() throws Exception {
        PollWait wait = new PollWait(LONG_INTERVAL, LONG_INTERVAL);
        wait.requestEarlyPoll();
        CompletableFuture<Void> waiting = CompletableFuture.runAsync(() -> awaitQuietly(wait));

        Thread.sleep(100);
        wait.stop();

        waiting.get(5, TimeUnit.SECONDS);
    }

    private static void awaitQuietly(PollWait wait) {
        try {
            wait.awaitNextPoll();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private static Duration elapsedSince(long startNanos) {
        return Duration.ofNanos(System.nanoTime() - startNanos);
    }
}
