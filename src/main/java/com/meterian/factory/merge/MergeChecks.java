package com.meterian.factory.merge;

import java.util.List;

/**
 * The checks that the workflow makes possible.
 *
 * @param reviewNames the names of the reviews of the workflow; empty when it has no reviews state
 * @param hasBuild    true when the workflow has a build state
 */
public record MergeChecks(List<String> reviewNames, boolean hasBuild) {

    public boolean hasReviews() {
        return !reviewNames.isEmpty();
    }
}
