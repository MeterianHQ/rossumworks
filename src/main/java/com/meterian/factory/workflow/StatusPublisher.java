package com.meterian.factory.workflow;

import com.meterian.factory.board.BoardUpdates;
import com.meterian.factory.definition.DefinitionLookup;
import com.meterian.factory.github.GitHubClient;
import com.meterian.factory.github.Issue;
import com.meterian.factory.github.IssueComment;
import com.meterian.factory.state.IssueRecord;
import com.meterian.factory.state.StatusComment;
import java.util.List;
import java.util.Optional;

/** Writes the record to the one status comment of the worker, sets the column label, and updates the board. */
public final class StatusPublisher {

    private final GitHubClient gitHub;
    private final String workerLogin;
    private final IssueDefinitionFacts facts;
    private final BoardUpdates board;

    /** @param definitions the definitions of the records: the limits of the states come from them */
    public StatusPublisher(GitHubClient gitHub, String workerLogin, DefinitionLookup definitions,
                           BoardUpdates board) {
        this.gitHub = gitHub;
        this.workerLogin = workerLogin;
        this.facts = new IssueDefinitionFacts(definitions);
        this.board = board;
    }

    /** Writes nothing when the action did not change the record. Returns the record with the comment id. */
    public IssueRecord publish(Issue issue, IssueRecord before, IssueRecord after) {
        if (after.equals(before)) {
            return after;
        }
        IssueRecord published = after.statusCommentId().isPresent() ? after
                : after.withStatusComment(findOrCreateStatusComment(issue.number(), after));
        gitHub.editComment(published.statusCommentId().orElseThrow(), StatusComment.render(published, facts.limitsOf(published),
                facts.reviewNamesOf(published)));
        gitHub.setColumn(issue, published.column());
        board.recordIssue(issue, published);
        return published;
    }

    private long findOrCreateStatusComment(int issueNumber, IssueRecord record) {
        return findStatusComment(issueNumber)
                .orElseGet(() -> gitHub.createComment(issueNumber, StatusComment.render(record, facts.limitsOf(record), facts.reviewNamesOf(record))));
    }

    private Optional<Long> findStatusComment(int issueNumber) {
        return gitHub.comments(issueNumber).stream()
                .filter(comment -> StatusComment.findRecord(List.of(comment), workerLogin).isPresent())
                .map(IssueComment::id)
                .max(Long::compare);
    }
}
