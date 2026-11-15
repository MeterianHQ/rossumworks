package com.meterian.factory.github;

import com.meterian.factory.state.ColumnLabels;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Remembers the column label that the client set last on each issue, and the column labels that the client
 * created. A caller can give an issue that it read before an earlier column change of the same action, so its
 * labels alone can be old.
 */
final class ColumnLabelMemory {

    private final Map<Integer, String> lastSetLabels = new ConcurrentHashMap<>();
    private final Set<String> createdLabels = ConcurrentHashMap.newKeySet();

    /** The column labels that can be on the issue: the labels of the caller, and the label set last. */
    Set<String> findPossibleColumnLabels(Issue issue) {
        Set<String> possible = new LinkedHashSet<>();
        issue.labels().stream().filter(ColumnLabels::isColumnLabel).forEach(possible::add);
        findLastSetLabel(issue.number()).ifPresent(possible::add);
        return possible;
    }

    /** True only when the labels of the caller have the label and no later column change replaced it. */
    boolean isSurelyOnTheIssue(Issue issue, String columnLabel) {
        return issue.labels().contains(columnLabel)
                && findLastSetLabel(issue.number()).map(columnLabel::equals).orElse(true);
    }

    void remember(int issueNumber, String columnLabel) {
        lastSetLabels.put(issueNumber, columnLabel);
    }

    boolean isCreated(String columnLabel) {
        return createdLabels.contains(columnLabel);
    }

    void rememberCreated(String columnLabel) {
        createdLabels.add(columnLabel);
    }

    private Optional<String> findLastSetLabel(int issueNumber) {
        return Optional.ofNullable(lastSetLabels.get(issueNumber));
    }
}
