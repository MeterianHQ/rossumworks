package com.meterian.factory.git;

import java.nio.file.Path;
import java.util.List;

public interface Git {

    void fetch(Path repository, String remote, String branch);

    void addWorktree(Path clone, Path worktree, String branch, String startPoint);

    void removeWorktree(Path clone, Path worktree);

    /** Forgets the worktrees whose folder does not exist. */
    void pruneWorktrees(Path clone);

    /** Returns the hash of the tree of HEAD. */
    String treeHash(Path worktree);

    /** Returns a stable identifier of the changes since the merge base with the base. It keeps whitespace. */
    String patchId(Path worktree, String base);

    /** Returns the hash of the tree of the commit. */
    String treeHashOf(Path worktree, String commit);

    /** Returns the hash of the commit that the reference points to. */
    String commitOf(Path repository, String reference);

    /** Returns the hash of the commit of HEAD. */
    String headCommit(Path worktree);

    boolean hasUncommittedChanges(Path worktree);

    /** Adds all changes, except the folder .factory, and commits them. */
    void commitAll(Path worktree, String message);

    /** Rebases onto the upstream. It is up to date when HEAD contains the upstream. On a conflict it aborts. */
    RebaseOutcome rebase(Path worktree, String upstream);

    /** Merges the upstream and does not commit. Returns the paths with a conflict; the merge stays open. */
    List<String> mergeWithoutCommit(Path worktree, String upstream);

    /** True when a merge waits for its commit. */
    boolean hasOpenMerge(Path worktree);

    /** Returns the paths that have a conflict in the index. */
    List<String> conflictedPaths(Path worktree);

    /** Returns the hashes of the parents of the commit, the first parent first. */
    List<String> parentsOf(Path worktree, String commit);

    /** True when the history of the commit contains the ancestor. */
    boolean isAncestor(Path worktree, String ancestor, String commit);

    /** Moves HEAD to the commit and keeps the index and the files. */
    void resetSoft(Path worktree, String commit);

    /** Moves HEAD, the index, and the tracked files to the commit. */
    void resetHard(Path worktree, String commit);

    /** Force-pushes HEAD to the branch of the remote. */
    void push(Path worktree, String remote, String branch);

    /** Force-pushes the commit to the branch of the remote. */
    void pushCommit(Path worktree, String remote, String commit, String branch);

    /**
     * Pushes the commit to the branch of the remote only when the branch on the remote is at the commit of the
     * remote-tracking branch, or is missing when no remote-tracking branch exists.
     */
    void forwardCommit(Path repository, String remote, String commit, String branch);

    void excludeFactoryFolder(Path worktree);

    /** Sets the URL of the remote. It adds the remote or replaces the URL. */
    void setRemote(Path repository, String name, String url);
}
