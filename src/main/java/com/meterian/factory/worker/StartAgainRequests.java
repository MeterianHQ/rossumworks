package com.meterian.factory.worker;

import com.meterian.factory.definition.DefinitionSource;
import com.meterian.factory.definition.DefinitionVersion;
import com.meterian.factory.definition.WorkflowDefinition;
import com.meterian.factory.github.GitHubClient;
import com.meterian.factory.github.Issue;
import com.meterian.factory.state.IssueRecord;
import com.meterian.factory.workflow.IssueDefinitionFacts;
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
    private static final String DEFINITION_NOTE =
            "Start again has no effect: the workflow definition of the issue does not load.";
    private static final String NO_CURRENT_VERSION_NOTE =
            "Start again has no effect: no version of .factory on the default branch loads.";

    private final GitHubClient gitHub;
    private final StatusPublisher statusPublisher;
    private final DefinitionSource definitions;
    private final IssueDefinitionFacts facts;
    private final WorkerMove workerMove;

    StartAgainRequests(GitHubClient gitHub, StatusPublisher statusPublisher, DefinitionSource definitions,
                       WorkerMove workerMove) {
        this.gitHub = gitHub;
        this.workerMove = workerMove;
        this.statusPublisher = statusPublisher;
        this.definitions = definitions;
        this.facts = new IssueDefinitionFacts(definitions);
    }

    /** The label counts from any account: the GitHub permissions control who can add a label. */
    static boolean isRequested(Issue issue) {
        return issue.labels().contains(START_AGAIN_LABEL);
    }

    /** The label goes away only after the record is on GitHub: a failure keeps it for the next poll. */
    IssueRecord startAgain(Issue issue, IssueRecord record) {
        IssueRecord published = statusPublisher.publish(issue, record, createNextRecord(issue, record));
        gitHub.removeLabel(issue.number(), START_AGAIN_LABEL);
        return published;
    }

    /**
     * A pull request that GitHub merged makes the issue go to the finish state, so the change never gets made again.
     * A record whose definition does not load stays as it is.
     */
    private IssueRecord createNextRecord(Issue issue, IssueRecord record) {
        WorkflowDefinition workflow;
        try {
            workflow = definitions.definitionOf(record).workflow();
        } catch (IllegalStateException exception) {
            return record.withNote(DEFINITION_NOTE);
        }
        if (!StartAgainCheck.isPossible(record, facts.isInBlockTarget(record))) {
            return record.withNote(NO_EFFECT_NOTE);
        }
        if (record.pullRequestNumber().map(gitHub::isMerged).orElse(false)) {
            return workerMove.moveTo(workflow, record, workflow.finishState()).withNote(MERGED_NOTE);
        }
        return definitions.current()
                .map(current -> createQueuedRecord(issue, record, current))
                .orElseGet(() -> record.withNote(NO_CURRENT_VERSION_NOTE));
    }

    /**
     * The start state has the action prepare. It removes the old worktree and resets the branch, so nothing of the
     * earlier work stays. The issue takes the current version of the definition, so a correction of .factory
     * reaches it. All the counts of the limits are 0.
     */
    private IssueRecord createQueuedRecord(Issue issue, IssueRecord record, DefinitionVersion current) {
        WorkflowDefinition workflow = current.definition().workflow();
        IssueRecord queued = IssueRecord.queued(issue.number(), workflow.start())
                .withDefinitionVersion(current.identifier())
                .withIssueTextHash(IssueText.computeHash(issue))
                .withNote(STARTED_AGAIN_NOTE);
        workerMove.writeMoveOfAnotherState(workflow, record, queued);
        return record.statusCommentId().map(queued::withStatusComment).orElse(queued);
    }
}
