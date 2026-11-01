package com.meterian.factory.merge;

import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;

/**
 * One lock for each target repository, keyed by its clone. Only one issue of a target merges at a time, so each
 * merge rebases on the merge before it and builds that tree.
 */
final class MergeLock {

    private static final ConcurrentMap<Path, Object> LOCKS = new ConcurrentHashMap<>();

    private MergeLock() {
    }

    /** The key is the absolute and normalized path, so two spellings of one clone give one lock. */
    static <T> T run(Path clone, Supplier<T> action) {
        synchronized (LOCKS.computeIfAbsent(clone.toAbsolutePath().normalize(), key -> new Object())) {
            return action.get();
        }
    }
}
