package com.meterian.factory.worker;

import com.meterian.factory.github.GitHubClient;
import com.meterian.factory.github.Issue;
import com.meterian.factory.github.IssueComment;
import com.meterian.factory.github.PullRequest;
import com.meterian.factory.state.HandOver;
import com.meterian.factory.state.IssueRecord;
import com.meterian.factory.state.IssueStatus;
import com.meterian.factory.state.StatusComment;
import com.meterian.factory.workflow.IssueText;
import com.meterian.factory.workflow.StatusPublisher;
import com.meterian.factory.workflow.WorkspaceLayout;
import java.util.List;
import java.util.Optional;

/** Writes the first record of a new owner. The record continues the pushed branch and keeps a block or a wait. */
final class OwnershipTakeover {

    private final GitHubClient gitHub;
    private final StatusPublisher statusPublisher;
    private final String workerLogin;

    OwnershipTakeover(GitHubClient gitHub, StatusPublisher statusPublisher, String workerLogin) {
        this.gitHub = gitHub;
        this.statusPublisher = statusPublisher;
        this.workerLogin = workerLogin;
    }

    IssueRecord takeOver(Issue issue, List<IssueComment> comments, Optional<IssueRecord> ownRecord) {
        IssueRecord record = IssueRecord.queued(issue.number()).withIssueTextHash(IssueText.computeHash(issue));
        record = continuePushedBranch(issue.number(), record);
        record = keepBlockOrWait(record, findEarlierRecord(comments, ownRecord));
        record = record.withNote("The factory of " + workerLogin + " continues the work of an earlier owner.");
        IssueRecord withComment = ownRecord.flatMap(IssueRecord::statusCommentId).map(record::withStatusComment)
                .orElse(record);
        return statusPublisher.publish(issue, ownRecord.orElse(null), withComment);
    }

    private IssueRecord continuePushedBranch(int issueNumber, IssueRecord record) {
        String branch = WorkspaceLayout.branch(issueNumber);
        if (gitHub.findBranchHead(branch).isEmpty()) {
            return record;
        }
        IssueRecord continuing = record.withHandOver(HandOver.CONTINUES_PUSHED_BRANCH);
        return gitHub.openPullRequest(branch).map(PullRequest::number).map(continuing::withPullRequest)
                .orElse(continuing);
    }

    private Optional<IssueRecord> findEarlierRecord(List<IssueComment> comments, Optional<IssueRecord> ownRecord) {
        Optional<IssueRecord> ofAnotherAuthor = StatusComment.findNewestRecordOfAnotherAuthor(comments, workerLogin);
        return ofAnotherAuthor.isPresent() ? ofAnotherAuthor : ownRecord;
    }

    /** Only the status and the wait reason pass on. The other fields of an earlier record stay behind. */
    private IssueRecord keepBlockOrWait(IssueRecord record, Optional<IssueRecord> earlierRecord) {
        if (earlierRecord.isEmpty() || !isBlockOrWait(earlierRecord.get().status())) {
            return record;
        }
        IssueRecord withStatus = record.withStatus(earlierRecord.get().status());
        return earlierRecord.get().waitReason().map(withStatus::withWaitReason).orElse(withStatus);
    }

    private static boolean isBlockOrWait(IssueStatus status) {
        return status == IssueStatus.BLOCKED || status == IssueStatus.WAITS_FOR_YOU;
    }
}
