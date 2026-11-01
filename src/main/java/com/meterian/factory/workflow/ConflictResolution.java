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

    private final Git git;
    private final String mergeTargetBranch;
    private final String mergeTargetReference;

    ConflictResolution(Git git, String mergeTargetBranch) {
        this.git = git;
        this.mergeTargetBranch = mergeTargetBranch;
        this.mergeTargetReference = "origin/" + mergeTargetBranch;
    }

    /** Opens a merge in the worktree, so that the agent solves the conflict in the files. */
    Finding describeMergeFix(Path worktree, MergeResult.NeedsFix needsFix) {
        List<String> conflictedPaths = git.mergeWithoutCommit(worktree, mergeTargetReference);
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

    /** Returns a finding when a file with a conflict still holds a conflict marker. */
    Optional<Finding> findRemainingMarkers(Path worktree) {
        List<String> pathsWithMarkers = git.conflictedPaths(worktree).stream()
                .filter(path -> holdsConflictMarkers(worktree.resolve(path)))
                .toList();
        if (pathsWithMarkers.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new Finding("The files still hold conflict markers: " + String.join(", ", pathsWithMarkers)
                + ".", "Remove each conflict marker and keep the intent of both sides."));
    }

    /** Commits the merge, then makes it one commit on the merge target, so that a later rebase replays no conflict. */
    void commitAsOneCommit(Path worktree, String message) {
        git.commitAll(worktree, message);
        git.resetSoft(worktree, mergeTargetReference);
        git.commitAll(worktree, message);
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
