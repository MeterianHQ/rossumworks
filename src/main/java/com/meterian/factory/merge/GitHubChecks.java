package com.meterian.factory.merge;

import com.meterian.factory.github.CheckRun;
import com.meterian.factory.github.CheckRunPage;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The result of the required check runs on a commit. Only a check run with a required name counts. A failed
 * required check run wins over a wait. A required check run that GitHub does not show yet makes the merge wait.
 */
public sealed interface GitHubChecks {

    /** Each required check run passed. */
    record Passed() implements GitHubChecks {
    }

    /** No required check run failed, but one or more are missing or not complete. */
    record NotComplete() implements GitHubChecks {
    }

    /** One or more required check runs failed. */
    record Failed(List<CheckRun> failedCheckRuns) implements GitHubChecks {

        public Failed {
            failedCheckRuns = List.copyOf(failedCheckRuns);
        }

        /** Names each failed check run and its link, for example "the check run build failed (https://...)". */
        public String describe() {
            return failedCheckRuns.stream()
                    .map(checkRun -> "the check run " + checkRun.name() + " failed (" + checkRun.url() + ")")
                    .collect(Collectors.joining(", "));
        }
    }

    /** A required check run is not on the page, and the page does not hold all the check runs of the commit. */
    record TooMany(int totalCount, int readCount) implements GitHubChecks {

        public String describe() {
            return "the commit has " + totalCount + " check runs, and the merge gate can read only " + readCount;
        }
    }

    static GitHubChecks evaluate(CheckRunPage page, List<String> requiredChecks) {
        List<CheckRun> requiredCheckRuns = page.checkRuns().stream()
                .filter(checkRun -> requiredChecks.contains(checkRun.name())).toList();
        List<CheckRun> failedCheckRuns = requiredCheckRuns.stream().filter(CheckRun::hasFailed).toList();
        if (!failedCheckRuns.isEmpty()) {
            return new Failed(failedCheckRuns);
        }
        Set<String> shownNames = requiredCheckRuns.stream().map(CheckRun::name).collect(Collectors.toSet());
        boolean isARequiredCheckRunMissing = !shownNames.containsAll(requiredChecks);
        if (isARequiredCheckRunMissing && page.isIncomplete()) {
            return new TooMany(page.totalCount(), page.checkRuns().size());
        }
        boolean isARequiredCheckRunRunning = requiredCheckRuns.stream().anyMatch(checkRun -> !checkRun.isComplete());
        if (isARequiredCheckRunMissing || isARequiredCheckRunRunning) {
            return new NotComplete();
        }
        return new Passed();
    }
}
