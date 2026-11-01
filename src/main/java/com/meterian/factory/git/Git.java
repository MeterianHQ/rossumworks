package com.meterian.factory.git;

import java.nio.file.Path;

public interface Git {

    void fetch(Path repository, String remote, String branch);

    void addWorktree(Path clone, Path worktree, String branch, String startPoint);

    void removeWorktree(Path clone, Path worktree);

    /** Returns the hash of the tree of HEAD. */
    String treeHash(Path worktree);

    /** Returns a stable identifier of the changes since the merge base with the base. It keeps whitespace. */
    String patchId(Path worktree, String base);

    /** Returns the hash of the tree of the commit. */
    String treeHashOf(Path worktree, String commit);

    /** Returns the hash of the commit of HEAD. */
    String headCommit(Path worktree);

    boolean hasUncommittedChanges(Path worktree);

    /** Adds all changes, except the folder .factory, and commits them. */
    void commitAll(Path worktree, String message);

    /** Rebases onto the upstream. On a conflict it aborts the rebase. */
    RebaseOutcome rebase(Path worktree, String upstream);

    /** Force-pushes HEAD to the branch of the remote. */
    void push(Path worktree, String remote, String branch);

    /** Force-pushes the commit to the branch of the remote. */
    void pushCommit(Path worktree, String remote, String commit, String branch);

    void excludeFactoryFolder(Path worktree);

    /** Sets the URL of the remote. It adds the remote or replaces the URL. */
    void setRemote(Path repository, String name, String url);
}
