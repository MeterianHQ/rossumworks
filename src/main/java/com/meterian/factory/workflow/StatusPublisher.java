package com.meterian.factory.workflow;

import com.meterian.factory.github.GitHubClient;
import com.meterian.factory.github.Issue;
import com.meterian.factory.github.IssueComment;
import com.meterian.factory.state.IssueRecord;
import com.meterian.factory.state.StatusComment;
import java.util.List;
import java.util.Optional;

/** Writes the record to the one status comment of the worker and sets the Project status. */
final class StatusPublisher {

    private final GitHubClient gitHub;
    private final String workerLogin;
    private final int fixLoopLimit;

    StatusPublisher(GitHubClient gitHub, String workerLogin, int fixLoopLimit) {
        this.gitHub = gitHub;
        this.workerLogin = workerLogin;
        this.fixLoopLimit = fixLoopLimit;
    }

    /** Writes nothing when the step did not change the record. Returns the record with the comment id. */
    IssueRecord publish(Issue issue, IssueRecord before, IssueRecord after) {
        if (after.equals(before)) {
            return after;
        }
        IssueRecord published = after.statusCommentId().isPresent() ? after
                : after.withStatusComment(findOrCreateStatusComment(issue.number(), after));
        gitHub.editComment(published.statusCommentId().orElseThrow(), StatusComment.render(published, fixLoopLimit));
        gitHub.setStatus(issue, published.status());
        return published;
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
