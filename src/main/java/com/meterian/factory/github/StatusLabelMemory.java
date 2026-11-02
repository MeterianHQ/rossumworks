package com.meterian.factory.github;

import com.meterian.factory.state.IssueStatus;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Remembers the status label that the client set last on each issue. A caller can give an issue that it read
 * before an earlier status change of the same step, so its labels alone can be old.
 */
final class StatusLabelMemory {

    private final Map<Integer, String> lastSetLabels = new ConcurrentHashMap<>();

    /** The status labels that can be on the issue: the labels of the caller, and the label set last. */
    Set<String> findPossibleStatusLabels(Issue issue) {
        Set<String> possible = new LinkedHashSet<>();
        issue.labels().stream().filter(IssueStatus::isStatusLabel).forEach(possible::add);
        findLastSetLabel(issue.number()).ifPresent(possible::add);
        return possible;
    }

    /** True only when the labels of the caller have the label and no later status change replaced it. */
    boolean isSurelyOnTheIssue(Issue issue, String statusLabel) {
        return issue.labels().contains(statusLabel)
                && findLastSetLabel(issue.number()).map(statusLabel::equals).orElse(true);
    }

    void remember(int issueNumber, String statusLabel) {
        lastSetLabels.put(issueNumber, statusLabel);
    }

    private Optional<String> findLastSetLabel(int issueNumber) {
        return Optional.ofNullable(lastSetLabels.get(issueNumber));
    }
}
