package com.meterian.factory.worker;

import com.meterian.factory.github.GitHubClient;
import com.meterian.factory.github.Issue;
import com.meterian.factory.state.IssueRecord;
import com.meterian.factory.state.Phase;
import com.meterian.factory.workflow.IssueText;
import com.meterian.factory.workflow.StartAgainCheck;
import com.meterian.factory.workflow.StatusPublisher;

/** The label factory:start-again, and the new record that removes it. */
final class StartAgainRequests {

    static final String START_AGAIN_LABEL = "factory:start-again";
    private static final String STARTED_AGAIN_NOTE = "The owner started the issue again. The earlier work is discarded.";
    private static final String NO_EFFECT_NOTE =
            "Start again has no effect: it works only on a Blocked issue without a live conversation.";
    private static final String MERGED_NOTE =
            "GitHub already merged the pull request, so the issue does not start again.";

    private final GitHubClient gitHub;
    private final StatusPublisher statusPublisher;

    StartAgainRequests(GitHubClient gitHub, StatusPublisher statusPublisher) {
        this.gitHub = gitHub;
        this.statusPublisher = statusPublisher;
    }

    static boolean isRequested(Issue issue) {
        return issue.labels().contains(START_AGAIN_LABEL);
    }

    /** The label goes away only after the record is on GitHub: a failure keeps it for the next poll. */
    IssueRecord startAgain(Issue issue, IssueRecord record) {
        IssueRecord published = statusPublisher.publish(issue, record, createNextRecord(issue, record));
        gitHub.removeLabel(issue.number(), START_AGAIN_LABEL);
        return published;
    }

    /** A pull request that GitHub merged makes the issue go to DONE, so the change never gets made again. */
    private IssueRecord createNextRecord(Issue issue, IssueRecord record) {
        if (!StartAgainCheck.isPossible(record)) {
            return record.withNote(NO_EFFECT_NOTE);
        }
        if (record.pullRequestNumber().map(gitHub::isMerged).orElse(false)) {
            return record.withPhase(Phase.DONE).withNote(MERGED_NOTE);
        }
        return createQueuedRecord(issue, record);
    }

    /** The phase PREPARE removes the old worktree and resets the branch, so nothing of the earlier work stays. */
    private static IssueRecord createQueuedRecord(Issue issue, IssueRecord record) {
        IssueRecord queued = IssueRecord.queued(issue.number())
                .withIssueTextHash(IssueText.computeHash(issue))
                .withNote(STARTED_AGAIN_NOTE);
        return record.statusCommentId().map(queued::withStatusComment).orElse(queued);
    }
}
