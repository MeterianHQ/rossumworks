package com.meterian.factory.merge;

import java.util.Optional;

/** The result of the merge steps. */
public sealed interface MergeResult {

    /**
     * The pull request is merged. The tree hash is the one that the last build checked. The note tells when
     * the tree on the merge target after the merge is not the built tree.
     */
    record Merged(String builtTreeHash, Optional<String> note) implements MergeResult {

        public Merged(String builtTreeHash) {
            this(builtTreeHash, Optional.empty());
        }
    }

    /** The rebase changed the diff. The reviews do not apply to the new diff. */
    record NeedsNewReviews() implements MergeResult {
    }

    /** The agent must fix the findings, for example a conflict or a failed build. */
    record NeedsFix(String findings) implements MergeResult {
    }

    /** The merge did not happen for a reason that can go away. The next poll runs the merge again. */
    record Postponed(String reason) implements MergeResult {
    }

    /** GitHub does not show the pushed commit, also after a new forward from the gatekeeper. */
    record NotForwarded(String pushedCommit, String pushedTree) implements MergeResult {
    }

    /** The GitHub check runs of the pushed commit refuse the merge, for example a failed check run. */
    record ChecksFailed(String reason) implements MergeResult {
    }

    /** A step failed. The change does not merge. */
    record Failed(String reason) implements MergeResult {
    }
}
