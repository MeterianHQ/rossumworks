package com.meterian.factory.git;

import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;

/**
 * One lock for each clone. A git command that writes the shared refs or the worktree metadata of a clone
 * runs under this lock: two of them at the same time can fail on a lock file of git.
 */
public final class CloneLock {

    private static final ConcurrentMap<Path, Object> LOCKS = new ConcurrentHashMap<>();

    private CloneLock() {
    }

    /** The key is the absolute and normalized path, so two spellings of one clone give one lock. */
    public static Object of(Path clone) {
        return LOCKS.computeIfAbsent(clone.toAbsolutePath().normalize(), key -> new Object());
    }

    public static <T> T run(Path clone, Supplier<T> action) {
        synchronized (of(clone)) {
            return action.get();
        }
    }

    public static void run(Path clone, Runnable action) {
        synchronized (of(clone)) {
            action.run();
        }
    }
}
