package com.meterian.factory.github;

import java.util.Optional;
import java.util.Set;

/**
 * A check of GitHub on a commit, for example a job of GitHub Actions.
 *
 * @param name       the name of the check
 * @param status     the status of GitHub, for example "queued", "in_progress", or "completed"
 * @param conclusion the conclusion of GitHub, for example "success" or "failure". Empty until the check is complete.
 * @param url        the link to the page of the check on GitHub
 */
public record CheckRun(String name, String status, Optional<String> conclusion, String url) {

    private static final String COMPLETED_STATUS = "completed";
    private static final Set<String> PASSED_CONCLUSIONS = Set.of("success", "skipped", "neutral");

    public boolean isComplete() {
        return COMPLETED_STATUS.equals(status);
    }

    /** A complete check with a conclusion that is not success, skipped, or neutral is a failed check. */
    public boolean hasFailed() {
        return isComplete() && conclusion.filter(PASSED_CONCLUSIONS::contains).isEmpty();
    }
}
