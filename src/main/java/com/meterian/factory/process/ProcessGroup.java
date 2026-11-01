package com.meterian.factory.process;

import java.io.IOException;
import java.time.Duration;

/** Ends all processes of a process group: TERM first, then KILL after a grace time. */
public final class ProcessGroup {

    private static final System.Logger LOGGER = System.getLogger(ProcessGroup.class.getName());
    private static final Duration GRACE = Duration.ofSeconds(2);
    private static final long POLL_MILLISECONDS = 50;

    private ProcessGroup() {
    }

    public static void terminate(long groupId) {
        signal("-TERM", groupId);
        waitUntilEmpty(groupId);
        if (isAlive(groupId)) {
            signal("-KILL", groupId);
        }
    }

    private static void waitUntilEmpty(long groupId) {
        long deadline = System.nanoTime() + GRACE.toNanos();
        while (isAlive(groupId) && System.nanoTime() < deadline) {
            try {
                Thread.sleep(POLL_MILLISECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private static boolean isAlive(long groupId) {
        return signal("-0", groupId);
    }

    private static boolean signal(String signal, long groupId) {
        try {
            Process signaller = new ProcessBuilder("kill", signal, "--", "-" + groupId)
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start();
            return signaller.waitFor() == 0;
        } catch (IOException exception) {
            LOGGER.log(System.Logger.Level.WARNING, "The signal to the process group failed.", exception);
            return false;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
