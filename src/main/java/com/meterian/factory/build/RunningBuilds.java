package com.meterian.factory.build;

import com.meterian.factory.process.ProcessGroup;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** The builds that run now, one for each worktree. A stop ends the process group of the build. */
final class RunningBuilds {

    private final Map<Path, Process> processes = new HashMap<>();
    private final Set<Path> stoppedWorktrees = new HashSet<>();
    private boolean refusesNewBuilds;

    /** Returns false when the builds stop: the caller must then end the process. */
    synchronized boolean register(Path worktree, Process process) {
        if (refusesNewBuilds) {
            return false;
        }
        processes.put(toKey(worktree), process);
        stoppedWorktrees.remove(toKey(worktree));
        return true;
    }

    synchronized void unregister(Path worktree) {
        processes.remove(toKey(worktree));
        stoppedWorktrees.remove(toKey(worktree));
    }

    /** True when a stop ended the build that runs in the worktree. */
    synchronized boolean wasStopped(Path worktree) {
        return stoppedWorktrees.contains(toKey(worktree));
    }

    synchronized boolean refusesNewBuilds() {
        return refusesNewBuilds;
    }

    void stop(Path worktree) {
        Process process;
        synchronized (this) {
            process = processes.get(toKey(worktree));
            if (process == null) {
                return;
            }
            stoppedWorktrees.add(toKey(worktree));
        }
        ProcessGroup.terminate(process.pid());
    }

    void stopAll() {
        Set<Path> worktrees;
        synchronized (this) {
            refusesNewBuilds = true;
            worktrees = Set.copyOf(processes.keySet());
        }
        worktrees.forEach(this::stop);
    }

    private static Path toKey(Path worktree) {
        return worktree.toAbsolutePath().normalize();
    }
}
