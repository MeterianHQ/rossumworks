package com.meterian.factory.merge;

import com.meterian.factory.github.CheckRun;
import com.meterian.factory.github.CheckRunPage;
import java.util.List;
import java.util.stream.Collectors;

/**
 * The result of the GitHub check runs on the head commit of the pull request. A failed check run wins over a
 * wait. A page that does not hold all the check runs of the commit can hide a failure, so it refuses the merge.
 */
public sealed interface GitHubChecks {

    /** All the check runs passed, or the commit has no check run. */
    record Passed() implements GitHubChecks {
    }

    /** No check run failed, but one or more check runs are not complete. */
    record NotComplete() implements GitHubChecks {
    }

    /** One or more check runs failed. */
    record Failed(List<CheckRun> failedCheckRuns) implements GitHubChecks {

        public Failed {
            failedCheckRuns = List.copyOf(failedCheckRuns);
        }

        /** Names each failed check run and its link, for example "the check build failed (https://...)". */
        public String describe() {
            return failedCheckRuns.stream()
                    .map(checkRun -> "the check " + checkRun.name() + " failed (" + checkRun.url() + ")")
                    .collect(Collectors.joining(", "));
        }
    }

    /** The commit has more check runs than one answer of GitHub holds. */
    record TooMany(int totalCount, int readCount) implements GitHubChecks {

        public String describe() {
            return "the commit has " + totalCount + " GitHub check runs, and the merge gate can read only "
                    + readCount;
        }
    }

    static GitHubChecks evaluate(CheckRunPage page) {
        List<CheckRun> checkRuns = page.checkRuns();
        List<CheckRun> failedCheckRuns = checkRuns.stream().filter(CheckRun::hasFailed).toList();
        if (!failedCheckRuns.isEmpty()) {
            return new Failed(failedCheckRuns);
        }
        if (page.isIncomplete()) {
            return new TooMany(page.totalCount(), checkRuns.size());
        }
        if (checkRuns.stream().anyMatch(checkRun -> !checkRun.isComplete())) {
            return new NotComplete();
        }
        return new Passed();
    }
}
