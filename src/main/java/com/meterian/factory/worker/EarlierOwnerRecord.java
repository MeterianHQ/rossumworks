package com.meterian.factory.worker;

import com.meterian.factory.github.AssignmentEvent;
import com.meterian.factory.github.GitHubClient;
import com.meterian.factory.github.IssueComment;
import com.meterian.factory.state.AuthoredRecord;
import com.meterian.factory.state.IssueRecord;
import com.meterian.factory.state.StatusComment;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Finds the status record of an earlier owner of the issue. A record of another account counts only when that
 * account was an assignee of the issue once, so that a comment of a stranger cannot set the state.
 */
final class EarlierOwnerRecord {

    private final GitHubClient gitHub;
    private final String workerLogin;
    /** The trust decision of each issue, for the newest status comment of another author. Memory only. */
    private final Map<Integer, AssignedLogins> assignedLoginsByIssue = new ConcurrentHashMap<>();

    EarlierOwnerRecord(GitHubClient gitHub, String workerLogin) {
        this.gitHub = gitHub;
        this.workerLogin = workerLogin;
    }

    /** The logins that had an "assigned" event when the newest status comment of another author was known. */
    private record AssignedLogins(long newestCommentId, Set<String> logins) {
    }

    /**
     * Reads the events only when another account wrote a status comment after the newest one of the worker, and
     * only once for each new status comment of another author.
     */
    Optional<IssueRecord> findNewerThanOwnRecord(int issueNumber, List<IssueComment> comments) {
        List<AuthoredRecord> newerRecords = findNewerRecordsOfOtherAuthors(comments);
        if (newerRecords.isEmpty()) {
            return Optional.empty();
        }
        Set<String> assignedLogins = findAssignedLogins(issueNumber, findNewestCommentId(newerRecords));
        return newerRecords.stream()
                .filter(authoredRecord -> assignedLogins.contains(normalize(authoredRecord.authorLogin())))
                .findFirst()
                .map(AuthoredRecord::record);
    }

    private Set<String> findAssignedLogins(int issueNumber, long newestCommentId) {
        AssignedLogins known = assignedLoginsByIssue.get(issueNumber);
        if (known != null && known.newestCommentId() == newestCommentId) {
            return known.logins();
        }
        Set<String> logins = readAssignedLogins(issueNumber);
        assignedLoginsByIssue.put(issueNumber, new AssignedLogins(newestCommentId, logins));
        return logins;
    }

    private Set<String> readAssignedLogins(int issueNumber) {
        return gitHub.assignmentEvents(issueNumber).stream()
                .map(AssignmentEvent::assignee)
                .map(EarlierOwnerRecord::normalize)
                .collect(Collectors.toUnmodifiableSet());
    }

    private static long findNewestCommentId(List<AuthoredRecord> records) {
        return records.stream().mapToLong(AuthoredRecord::commentId).max().orElseThrow();
    }

    /** GitHub logins do not depend on the case of the letters. */
    private static String normalize(String login) {
        return login.toLowerCase(Locale.ROOT);
    }

    private List<AuthoredRecord> findNewerRecordsOfOtherAuthors(List<IssueComment> comments) {
        Optional<Long> ownCommentId = StatusComment.findNewestStatusCommentId(comments, workerLogin);
        return StatusComment.findRecordsOfOtherAuthors(comments, workerLogin).stream()
                .filter(authoredRecord -> ownCommentId.isEmpty() || authoredRecord.commentId() > ownCommentId.get())
                .toList();
    }
}
