package com.meterian.factory.github;

import java.util.List;

/**
 * The first page of the check runs of a commit.
 *
 * @param totalCount the number of check runs of the commit on GitHub, also the check runs after this page
 * @param checkRuns  the check runs of this page
 */
public record CheckRunPage(int totalCount, List<CheckRun> checkRuns) {

    public CheckRunPage {
        checkRuns = List.copyOf(checkRuns);
    }

    /** True when GitHub has more check runs than this page holds. */
    public boolean isIncomplete() {
        return totalCount > checkRuns.size();
    }
}
