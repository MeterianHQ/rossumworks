package com.meterian.factory.merge;

/** The result of the merge steps. */
public sealed interface MergeResult {

    /** The pull request is merged. The tree hash is the one that the last build checked. */
    record Merged(String builtTreeHash) implements MergeResult {
    }

    /** The rebase changed the diff. The reviews do not apply to the new diff. */
    record NeedsNewReviews() implements MergeResult {
    }

    /** The agent must fix the findings, for example a conflict or a failed build. */
    record NeedsFix(String findings) implements MergeResult {
    }

    /** A step failed. The change does not merge. */
    record Failed(String reason) implements MergeResult {
    }
}
