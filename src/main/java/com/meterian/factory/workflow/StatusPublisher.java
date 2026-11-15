package com.meterian.factory.workflow;

import com.meterian.factory.board.BoardUpdates;
import com.meterian.factory.definition.DefinitionLookup;
import com.meterian.factory.github.GitHubClient;
import com.meterian.factory.github.Issue;
import com.meterian.factory.github.IssueComment;
import com.meterian.factory.ownership.LostIssues;
import com.meterian.factory.state.IssueRecord;
import com.meterian.factory.state.StatusComment;
import java.util.List;
import java.util.Optional;

/**
 * Writes the record to the one status comment of the worker, sets the column label, and updates the board. The
 * column label and the board belong to the owner, so a factory that lost the issue writes only its status comment.
 */
public final class StatusPublisher {

    private final GitHubClient gitHub;
    private final String workerLogin;
    private final IssueDefinitionFacts facts;
    private final BoardUpdates board;
    private final LostIssues lostIssues;

    /**
     * @param definitions the definitions of the records: the limits of the states come from them
     * @param lostIssues  the issues that another account owns now
     */
    public StatusPublisher(GitHubClient gitHub, String workerLogin, DefinitionLookup definitions,
                           BoardUpdates board, LostIssues lostIssues) {
        this.gitHub = gitHub;
        this.workerLogin = workerLogin;
        this.facts = new IssueDefinitionFacts(definitions);
        this.board = board;
        this.lostIssues = lostIssues;
    }

    /** Writes nothing when the action did not change the record. Returns the record with the comment id. */
    public IssueRecord publish(Issue issue, IssueRecord before, IssueRecord after) {
        if (after.equals(before)) {
            return after;
        }
        IssueRecord published = writeStatusComment(issue.number(), after);
        if (!lostIssues.isLost(issue.number())) {
            gitHub.setColumn(issue, published.column());
            board.recordIssue(issue, published);
        }
        return published;
    }

    /** Writes only the status comment: the column label and the board stay as they are. */
    public IssueRecord publishToTheStatusComment(int issueNumber, IssueRecord before, IssueRecord after) {
        return after.equals(before) ? after : writeStatusComment(issueNumber, after);
    }

    private IssueRecord writeStatusComment(int issueNumber, IssueRecord record) {
        IssueRecord published = record.statusCommentId().isPresent() ? record
                : record.withStatusComment(findOrCreateStatusComment(issueNumber, record));
        gitHub.editComment(published.statusCommentId().orElseThrow(), render(published));
        return published;
    }

    /** Writes the record to a new status comment, also when the worker has an older one. */
    public IssueRecord publishInNewComment(Issue issue, IssueRecord record) {
        long commentId = gitHub.createComment(issue.number(), render(record));
        return publish(issue, null, record.withStatusComment(commentId));
    }

    private long findOrCreateStatusComment(int issueNumber, IssueRecord record) {
        return findStatusComment(issueNumber)
                .orElseGet(() -> gitHub.createComment(issueNumber, render(record)));
    }

    private String render(IssueRecord record) {
        return StatusComment.render(record, facts.limitsOf(record), facts.reviewNamesOf(record));
    }

    private Optional<Long> findStatusComment(int issueNumber) {
        return gitHub.comments(issueNumber).stream()
                .filter(comment -> StatusComment.findRecord(List.of(comment), workerLogin).isPresent())
                .map(IssueComment::id)
                .max(Long::compare);
    }
}
