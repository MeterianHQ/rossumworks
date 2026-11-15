package com.meterian.factory.worker;

import com.meterian.factory.github.GitHubClient;
import com.meterian.factory.github.Issue;
import com.meterian.factory.github.PullRequest;
import com.meterian.factory.state.HandOver;
import com.meterian.factory.state.IssueRecord;
import com.meterian.factory.state.IssueStatus;
import com.meterian.factory.state.WaitReason;
import com.meterian.factory.workflow.IssueText;
import com.meterian.factory.workflow.StatusPublisher;
import com.meterian.factory.workflow.WorkspaceLayout;
import java.util.Optional;
import java.util.Set;

/**
 * Writes the first record of a new owner in a new status comment. The record continues the pushed branch and keeps
 * a block or a wait of the earlier record.
 */
final class OwnershipTakeover {

    private static final String OPEN_QUESTION_NOTE =
            "The earlier owner had an open question. The question stays on the machine of the earlier owner.";
    /** These waits need the session or the worktree of the earlier owner, so the new owner cannot keep them. */
    private static final Set<WaitReason> WAITS_ON_THE_EARLIER_MACHINE =
            Set.of(WaitReason.QUESTION, WaitReason.LIVE_CONVERSATION);

    private final GitHubClient gitHub;
    private final StatusPublisher statusPublisher;
    private final String workerLogin;

    OwnershipTakeover(GitHubClient gitHub, StatusPublisher statusPublisher, String workerLogin) {
        this.gitHub = gitHub;
        this.statusPublisher = statusPublisher;
        this.workerLogin = workerLogin;
    }

    /** The new status comment is the newest one of the worker, so the next poll does not take over again. */
    IssueRecord takeOver(Issue issue, Optional<IssueRecord> earlierRecord) {
        IssueRecord record = IssueRecord.queued(issue.number()).withIssueTextHash(IssueText.computeHash(issue));
        record = continuePushedBranch(issue.number(), record)
                .withNote("The factory of " + workerLogin + " continues the work of an earlier owner.");
        if (earlierRecord.isPresent()) {
            record = keepBlockOrWait(record, earlierRecord.get());
        }
        return statusPublisher.publishInNewComment(issue, record);
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

    /** Only the status and the wait reason pass on. The other fields of an earlier record stay behind. */
    private static IssueRecord keepBlockOrWait(IssueRecord record, IssueRecord earlierRecord) {
        if (isWaitOnTheEarlierMachine(earlierRecord)) {
            return record.withNote(OPEN_QUESTION_NOTE);
        }
        if (!isBlockOrWait(earlierRecord.status()) || isApprovalWaitWithoutBranch(record, earlierRecord)) {
            return record;
        }
        IssueRecord withStatus = record.withStatus(earlierRecord.status());
        return earlierRecord.waitReason().map(withStatus::withWaitReason).orElse(withStatus);
    }

    private static boolean isWaitOnTheEarlierMachine(IssueRecord earlierRecord) {
        return earlierRecord.status() == IssueStatus.WAITS_FOR_YOU
                && earlierRecord.waitReason().filter(WAITS_ON_THE_EARLIER_MACHINE::contains).isPresent();
    }

    /** An approval without the pushed branch has no pull request to merge, so the work starts again. */
    private static boolean isApprovalWaitWithoutBranch(IssueRecord record, IssueRecord earlierRecord) {
        return record.handOver().isEmpty() && earlierRecord.status() == IssueStatus.WAITS_FOR_YOU
                && earlierRecord.waitReason().equals(Optional.of(WaitReason.MERGE_NEEDS_APPROVAL));
    }

    private static boolean isBlockOrWait(IssueStatus status) {
        return status == IssueStatus.BLOCKED || status == IssueStatus.WAITS_FOR_YOU;
    }
}
