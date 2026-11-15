package com.meterian.factory.worker;

import com.meterian.factory.github.AssignmentEvent;
import com.meterian.factory.github.GitHubClient;
import com.meterian.factory.github.IssueComment;
import com.meterian.factory.state.AuthoredRecord;
import com.meterian.factory.state.IssueRecord;
import com.meterian.factory.state.StatusComment;
import java.util.List;
import java.util.Optional;

/**
 * Finds the status record of an earlier owner of the issue. A record of another account counts only when that
 * account was an assignee of the issue once, so that a comment of a stranger cannot set the state.
 */
final class EarlierOwnerRecord {

    private final GitHubClient gitHub;
    private final String workerLogin;

    EarlierOwnerRecord(GitHubClient gitHub, String workerLogin) {
        this.gitHub = gitHub;
        this.workerLogin = workerLogin;
    }

    /** Reads the events only when another account wrote a status comment after the newest one of the worker. */
    Optional<IssueRecord> findNewerThanOwnRecord(int issueNumber, List<IssueComment> comments) {
        List<AuthoredRecord> newerRecords = findNewerRecordsOfOtherAuthors(comments);
        if (newerRecords.isEmpty()) {
            return Optional.empty();
        }
        List<AssignmentEvent> events = gitHub.assignmentEvents(issueNumber);
        return newerRecords.stream()
                .filter(authoredRecord -> wasAssigned(authoredRecord.authorLogin(), events))
                .findFirst()
                .map(AuthoredRecord::record);
    }

    private List<AuthoredRecord> findNewerRecordsOfOtherAuthors(List<IssueComment> comments) {
        Optional<Long> ownCommentId = StatusComment.findNewestStatusCommentId(comments, workerLogin);
        return StatusComment.findRecordsOfOtherAuthors(comments, workerLogin).stream()
                .filter(authoredRecord -> ownCommentId.isEmpty() || authoredRecord.commentId() > ownCommentId.get())
                .toList();
    }

    private static boolean wasAssigned(String login, List<AssignmentEvent> events) {
        return events.stream().anyMatch(event -> event.assignee().equalsIgnoreCase(login));
    }
}
