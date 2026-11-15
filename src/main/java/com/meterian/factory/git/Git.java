package com.meterian.factory.git;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

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

    /** Returns the hashes of the newest commits that change the path in the history of the reference, newest first. */
    List<String> commitsOfPath(Path repository, String reference, String path, int limit);

    /** Returns the hash of the commit that the reference points to: git rev-parse reference^{commit}. */
    String resolveCommit(Path repository, String reference);

    /**
     * Returns the content of the file in the commit, as git show commit:path gives it. It is empty when the
     * commit does not have the file, and it fails when the commit does not exist.
     */
    Optional<String> showFile(Path repository, String commit, String path);

    /** True when the commit has the file or the folder: git cat-file -e commit:path. */
    boolean hasPath(Path repository, String commit, String path);

    /** Returns the default branch of the remote origin, without the prefix origin/. */
    String defaultBranch(Path clone);

    /** Returns the hash of the commit of HEAD. */
    String headCommit(Path worktree);

    /** True when HEAD differs from the merge base with the base reference in the path: git diff base...HEAD. */
    boolean changesPath(Path worktree, String baseReference, String path);

    boolean hasUncommittedChanges(Path worktree);

    /** Adds all changes, except the scratch folder .factory-work, and commits them. */
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

    /** Force-pushes HEAD like push, and returns the output of git, with the "remote:" lines of the hooks. */
    String pushAndReadOutput(Path worktree, String remote, String branch);

    /** Force-pushes the commit to the branch of the remote. */
    void pushCommit(Path worktree, String remote, String commit, String branch);

    /**
     * Pushes the commit to the branch of the remote only when the branch on the remote is at the commit of the
     * remote-tracking branch, or is missing when no remote-tracking branch exists.
     */
    void forwardCommit(Path repository, String remote, String commit, String branch);

    void excludeFactoryFolder(Path worktree);

    /** Clones the repository from the URL into the directory. The parent of the directory must exist. */
    void cloneRepository(String url, Path directory);

    /** Sets the URL of the remote. It adds the remote or replaces the URL. */
    void setRemote(Path repository, String name, String url);
}
