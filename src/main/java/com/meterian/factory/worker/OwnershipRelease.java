package com.meterian.factory.worker;

import com.meterian.factory.github.GitHubClient;
import com.meterian.factory.github.Issue;
import com.meterian.factory.state.HandOver;
import com.meterian.factory.state.IssueRecord;
import com.meterian.factory.state.Phase;
import com.meterian.factory.state.StatusComment;
import com.meterian.factory.workflow.StatusPublisher;
import java.util.Optional;

/** Writes the stop of the factory when another account owns the issue. */
final class OwnershipRelease {

    private final GitHubClient gitHub;
    private final StatusPublisher statusPublisher;
    private final String workerLogin;

    OwnershipRelease(GitHubClient gitHub, StatusPublisher statusPublisher, String workerLogin) {
        this.gitHub = gitHub;
        this.statusPublisher = statusPublisher;
        this.workerLogin = workerLogin;
    }

    /** Does nothing when the factory has no record, already released the issue, or merged the change. */
    void release(Issue issue) {
        Optional<IssueRecord> ownRecord = StatusComment.findRecord(gitHub.comments(issue.number()), workerLogin);
        if (ownRecord.isEmpty() || isReleased(ownRecord.get()) || ownRecord.get().phase() == Phase.DONE) {
            return;
        }
        IssueRecord released = ownRecord.get().withHandOver(HandOver.RELEASED).withNote(createStopNote());
        statusPublisher.publish(issue, ownRecord.get(), released);
        gitHub.createComment(issue.number(), createStopComment());
    }

    private static boolean isReleased(IssueRecord record) {
        return record.handOver().equals(Optional.of(HandOver.RELEASED));
    }

    private String createStopNote() {
        return "The factory of " + workerLogin + " stops work: the issue has a new assignee.";
    }

    private String createStopComment() {
        return "The factory of `" + workerLogin + "` stops work on this issue, because the issue has a new assignee."
                + " The branch and the pull request stay. The factory of the new assignee continues the work.";
    }
}
