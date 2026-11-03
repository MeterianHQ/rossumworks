package com.meterian.factory.workflow;

import com.meterian.factory.configuration.FactoryConfiguration;
import com.meterian.factory.git.CloneLock;
import com.meterian.factory.git.Gatekeeper;
import com.meterian.factory.git.Git;
import java.nio.file.Files;
import java.nio.file.Path;

/** Creates and removes the worktree of an issue. Both steps are safe to run again. */
final class IssueWorkspace {

    static final String GATEKEEPER_REMOTE = "gatekeeper";

    private static final String ORIGIN = "origin";

    private final FactoryConfiguration configuration;
    private final Git git;
    private final String mergeTargetBranch;

    IssueWorkspace(FactoryConfiguration configuration, Git git, String mergeTargetBranch) {
        this.configuration = configuration;
        this.git = git;
        this.mergeTargetBranch = mergeTargetBranch;
    }

    /**
     * Makes a new worktree on the branch of the issue from the merge target, and the remote of the gatekeeper.
     * All steps run under the lock of the clone, because they write its shared refs and worktree metadata.
     */
    void create(int issueNumber, Path worktree) {
        Path clone = configuration.target().cloneDirectory();
        CloneLock.run(clone, () -> createUnderLock(clone, issueNumber, worktree));
    }

    void remove(Path worktree) {
        Path clone = configuration.target().cloneDirectory();
        CloneLock.run(clone, () -> {
            removeIfPresent(clone, worktree);
            git.pruneWorktrees(clone);
        });
    }

    private void createUnderLock(Path clone, int issueNumber, Path worktree) {
        Path gatekeeperDirectory = configuration.gatekeeperDirectory().toAbsolutePath();
        git.pruneWorktrees(clone);
        Gatekeeper.ensure(gatekeeperDirectory, configuration.target().sshUrl());
        git.setRemote(clone, GATEKEEPER_REMOTE, gatekeeperDirectory.toString());
        git.fetch(clone, ORIGIN, mergeTargetBranch);
        removeIfPresent(clone, worktree);
        String startPoint = ORIGIN + "/" + mergeTargetBranch;
        git.addWorktree(clone, worktree, WorkspaceLayout.branch(issueNumber), startPoint);
        git.excludeFactoryFolder(worktree);
    }

    private void removeIfPresent(Path clone, Path worktree) {
        if (Files.exists(worktree)) {
            git.removeWorktree(clone, worktree);
        }
    }
}
