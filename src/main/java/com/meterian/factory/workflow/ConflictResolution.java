package com.meterian.factory.workflow;

import com.meterian.factory.git.Git;
import com.meterian.factory.merge.MergeResult;
import com.meterian.factory.prompt.Finding;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/** A conflict with the merge target: the open merge, the check of the markers, and the one commit at the end. */
final class ConflictResolution {

    /** The second parent of a merge commit is the upstream commit that the merge used. */
    private static final String MERGED_UPSTREAM = "HEAD^2";

    /** The finding for the agent, and the paths that have a conflict. */
    record OpenedMerge(Finding finding, List<String> conflictedPaths) {
    }

    private final Git git;
    private final String mergeTargetBranch;
    private final String mergeTargetReference;

    ConflictResolution(Git git, String mergeTargetBranch) {
        this.git = git;
        this.mergeTargetBranch = mergeTargetBranch;
        this.mergeTargetReference = "origin/" + mergeTargetBranch;
    }

    /** Opens a merge in the worktree, so that the agent solves the conflict in the files. */
    OpenedMerge openMerge(Path worktree, MergeResult.NeedsFix needsFix) {
        List<String> conflictedPaths = git.mergeWithoutCommit(worktree, mergeTargetReference);
        return new OpenedMerge(describeMergeFix(worktree, needsFix, conflictedPaths), conflictedPaths);
    }

    /** Returns a finding when a file of the conflict still holds a conflict marker. */
    Optional<Finding> findRemainingMarkers(Path worktree, List<String> conflictedPaths) {
        List<String> pathsWithMarkers = conflictedPaths.stream()
                .filter(path -> holdsConflictMarkers(worktree.resolve(path)))
                .toList();
        if (pathsWithMarkers.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new Finding("The files still hold conflict markers: " + String.join(", ", pathsWithMarkers)
                + ".", "Remove each conflict marker and keep the intent of both sides."));
    }

    /** Commits the merge, then makes it one commit on the upstream commit that the merge used. */
    void commitAsOneCommit(Path worktree, String message) {
        git.commitAll(worktree, message);
        finishMergeCommit(worktree, message);
    }

    /** True when HEAD is a merge commit of the factory that is not one commit yet, for example after a crash. */
    boolean hasUnfinishedMergeCommit(Path worktree) {
        List<String> parents = git.parentsOf(worktree, "HEAD");
        return parents.size() == 2
                && !git.isAncestor(worktree, parents.get(1), parents.get(0))
                && !git.isAncestor(worktree, "HEAD", mergeTargetReference);
    }

    /** Replaces the merge commit at HEAD with one commit on its second parent. Never uses the moving merge target. */
    void finishMergeCommit(Path worktree, String message) {
        git.resetSoft(worktree, MERGED_UPSTREAM);
        git.commitAll(worktree, message);
    }

    private Finding describeMergeFix(Path worktree, MergeResult.NeedsFix needsFix, List<String> conflictedPaths) {
        if (!conflictedPaths.isEmpty()) {
            return new Finding("The branch has a conflict with " + mergeTargetBranch + " in these files: "
                    + String.join(", ", conflictedPaths) + ".",
                    "Edit each file, remove the conflict markers, and keep the intent of both sides.");
        }
        if (git.hasOpenMerge(worktree)) {
            return new Finding("The branch has a conflict with " + mergeTargetBranch
                    + " in a rebase, but the merge has no conflict.",
                    "Check that the change still fits " + mergeTargetBranch + ". The factory commits the merge.");
        }
        return new Finding("The merge needs a fix", needsFix.findings());
    }

    private static boolean holdsConflictMarkers(Path file) {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        try {
            String content = new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1);
            return content.lines().anyMatch(ConflictResolution::isConflictMarker);
        } catch (IOException exception) {
            throw new UncheckedIOException("Cannot read the file " + file, exception);
        }
    }

    private static boolean isConflictMarker(String line) {
        return line.startsWith("<<<<<<< ") || line.equals("=======") || line.startsWith(">>>>>>> ");
    }
}
