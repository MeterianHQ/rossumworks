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

    IssueWorkspace(FactoryConfiguration configuration, Git git) {
        this.configuration = configuration;
        this.git = git;
    }

    /**
     * Makes a new worktree on the branch of the issue from the merge target, and the remote of the gatekeeper.
     * All steps run under the lock of the clone, because they write its shared refs and worktree metadata.
     */
    void create(int issueNumber, Path worktree) {
        String mergeTargetBranch = configuration.mergeTargetBranch();
        createFromBranch(issueNumber, worktree, mergeTargetBranch);
    }

    /**
     * Makes the worktree like create, but starts at the branch of the issue that an earlier owner pushed. The
     * gatekeeper then expects that commit on GitHub, so the next forward can replace it.
     */
    void createFromPushedBranch(int issueNumber, Path worktree) {
        String branch = WorkspaceLayout.branch(issueNumber);
        Path clone = configuration.target().cloneDirectory();
        CloneLock.run(clone, () -> {
            createUnderLock(clone, issueNumber, worktree, branch);
            Gatekeeper.expectBranchOnGitHub(configuration.gatekeeperDirectory().toAbsolutePath(), clone, branch);
        });
    }

    private void createFromBranch(int issueNumber, Path worktree, String startBranch) {
        Path clone = configuration.target().cloneDirectory();
        CloneLock.run(clone, () -> createUnderLock(clone, issueNumber, worktree, startBranch));
    }

    void remove(Path worktree) {
        Path clone = configuration.target().cloneDirectory();
        CloneLock.run(clone, () -> {
            removeIfPresent(clone, worktree);
            git.pruneWorktrees(clone);
        });
    }

    private void createUnderLock(Path clone, int issueNumber, Path worktree, String startBranch) {
        Path gatekeeperDirectory = configuration.gatekeeperDirectory().toAbsolutePath();
        git.pruneWorktrees(clone);
        Gatekeeper.ensure(gatekeeperDirectory, configuration.target().sshUrl());
        git.setRemote(clone, GATEKEEPER_REMOTE, gatekeeperDirectory.toString());
        git.fetch(clone, ORIGIN, startBranch);
        removeIfPresent(clone, worktree);
        String startPoint = ORIGIN + "/" + startBranch;
        git.addWorktree(clone, worktree, WorkspaceLayout.branch(issueNumber), startPoint);
        git.excludeFactoryFolder(worktree);
    }

    private void removeIfPresent(Path clone, Path worktree) {
        if (Files.exists(worktree)) {
            git.removeWorktree(clone, worktree);
        }
    }
}
