package com.meterian.factory.worker;

import java.time.Duration;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The wait between two polls. An action of the page ends the wait early, so that the worker sees the new label
 * in seconds and not at the end of the poll interval. The short delay before the early poll gives GitHub time
 * to show the label in the list of issues. A stop ends the wait at once.
 */
final class PollWait {

    private final Duration pollInterval;
    private final Duration earlyPollDelay;
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition changed = lock.newCondition();
    private boolean earlyPollRequested;
    private boolean stopped;

    PollWait(Duration pollInterval, Duration earlyPollDelay) {
        this.pollInterval = pollInterval;
        this.earlyPollDelay = earlyPollDelay;
    }

    /** Many requests before the end of one wait give one early poll. */
    void requestEarlyPoll() {
        lock.lock();
        try {
            earlyPollRequested = true;
            changed.signalAll();
        } finally {
            lock.unlock();
        }
    }

    void stop() {
        lock.lock();
        try {
            stopped = true;
            changed.signalAll();
        } finally {
            lock.unlock();
        }
    }

    /** Returns at the end of the poll interval, after an early poll request and its delay, or at a stop. */
    void awaitNextPoll() throws InterruptedException {
        lock.lock();
        try {
            waitUntilTimeoutOrEarlyPoll(pollInterval.toNanos());
            if (earlyPollRequested) {
                earlyPollRequested = false;
                waitUntilTimeoutOrStop(earlyPollDelay.toNanos());
            }
        } finally {
            lock.unlock();
        }
    }

    private void waitUntilTimeoutOrEarlyPoll(long remainingNanos) throws InterruptedException {
        while (!stopped && !earlyPollRequested && remainingNanos > 0) {
            remainingNanos = changed.awaitNanos(remainingNanos);
        }
    }

    private void waitUntilTimeoutOrStop(long remainingNanos) throws InterruptedException {
        while (!stopped && remainingNanos > 0) {
            remainingNanos = changed.awaitNanos(remainingNanos);
        }
    }
}
