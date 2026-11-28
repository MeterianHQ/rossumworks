package com.meterian.factory.merge;

import com.meterian.factory.github.CheckRun;
import java.util.List;
import java.util.stream.Collectors;

/** The result of the checks of GitHub on the head commit of the pull request. A failed check wins over a wait. */
public sealed interface GitHubChecks {

    /** All the checks passed, or the commit has no check. */
    record Passed() implements GitHubChecks {
    }

    /** No check failed, but one or more checks are not complete. */
    record NotComplete() implements GitHubChecks {
    }

    /** One or more checks failed. */
    record Failed(List<CheckRun> failedCheckRuns) implements GitHubChecks {

        public Failed {
            failedCheckRuns = List.copyOf(failedCheckRuns);
        }

        /** Names each failed check and its link, for example "the check build failed (https://...)". */
        public String describe() {
            return failedCheckRuns.stream()
                    .map(checkRun -> "the check " + checkRun.name() + " failed (" + checkRun.url() + ")")
                    .collect(Collectors.joining(", "));
        }
    }

    static GitHubChecks evaluate(List<CheckRun> checkRuns) {
        List<CheckRun> failedCheckRuns = checkRuns.stream().filter(CheckRun::hasFailed).toList();
        if (!failedCheckRuns.isEmpty()) {
            return new Failed(failedCheckRuns);
        }
        if (checkRuns.stream().anyMatch(checkRun -> !checkRun.isComplete())) {
            return new NotComplete();
        }
        return new Passed();
    }
}
