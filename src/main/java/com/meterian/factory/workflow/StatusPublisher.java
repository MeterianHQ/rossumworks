package com.meterian.factory.workflow;

import com.meterian.factory.board.BoardUpdates;
import com.meterian.factory.github.GitHubClient;
import com.meterian.factory.github.Issue;
import com.meterian.factory.github.IssueComment;
import com.meterian.factory.ownership.LostIssues;
import com.meterian.factory.state.IssueRecord;
import com.meterian.factory.state.StatusComment;
import java.util.List;
import java.util.Optional;

/**
 * Writes the record to the one status comment of the worker, sets the status label, and updates the board. The
 * status label and the board belong to the owner, so a factory that lost the issue writes only its status comment.
 */
public final class StatusPublisher {

    private final GitHubClient gitHub;
    private final String workerLogin;
    private final int fixLoopLimit;
    private final BoardUpdates board;
    private final LostIssues lostIssues;

    public StatusPublisher(GitHubClient gitHub, String workerLogin, int fixLoopLimit, BoardUpdates board,
                           LostIssues lostIssues) {
        this.gitHub = gitHub;
        this.workerLogin = workerLogin;
        this.fixLoopLimit = fixLoopLimit;
        this.board = board;
        this.lostIssues = lostIssues;
    }

    /** Writes nothing when the step did not change the record. Returns the record with the comment id. */
    public IssueRecord publish(Issue issue, IssueRecord before, IssueRecord after) {
        if (after.equals(before)) {
            return after;
        }
        IssueRecord published = writeStatusComment(issue.number(), after);
        if (!lostIssues.isLost(issue.number())) {
            gitHub.setStatus(issue, published.status());
            board.recordIssue(issue, published);
        }
        return published;
    }

    /** Writes only the status comment: the status label and the board stay as they are. */
    public IssueRecord publishToTheStatusComment(int issueNumber, IssueRecord before, IssueRecord after) {
        return after.equals(before) ? after : writeStatusComment(issueNumber, after);
    }

    private IssueRecord writeStatusComment(int issueNumber, IssueRecord record) {
        IssueRecord published = record.statusCommentId().isPresent() ? record
                : record.withStatusComment(findOrCreateStatusComment(issueNumber, record));
        gitHub.editComment(published.statusCommentId().orElseThrow(), StatusComment.render(published, fixLoopLimit));
        return published;
    }

    /** Writes the record to a new status comment, also when the worker has an older one. */
    public IssueRecord publishInNewComment(Issue issue, IssueRecord record) {
        long commentId = gitHub.createComment(issue.number(), StatusComment.render(record, fixLoopLimit));
        return publish(issue, null, record.withStatusComment(commentId));
    }

    private long findOrCreateStatusComment(int issueNumber, IssueRecord record) {
        return findStatusComment(issueNumber)
                .orElseGet(() -> gitHub.createComment(issueNumber, StatusComment.render(record, fixLoopLimit)));
    }

    private Optional<Long> findStatusComment(int issueNumber) {
        return gitHub.comments(issueNumber).stream()
                .filter(comment -> StatusComment.findRecord(List.of(comment), workerLogin).isPresent())
                .map(IssueComment::id)
                .max(Long::compare);
    }
}
