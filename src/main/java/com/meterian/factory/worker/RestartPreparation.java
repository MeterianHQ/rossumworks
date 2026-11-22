package com.meterian.factory.worker;

import com.meterian.factory.definition.DefinitionLookup;
import com.meterian.factory.definition.WorkflowDefinition;
import com.meterian.factory.github.GitHubClient;
import com.meterian.factory.state.ActionType;
import com.meterian.factory.state.IssueRecord;
import com.meterian.factory.workflow.WorkspaceLayout;
import java.nio.file.Files;
import java.util.UUID;

/** Prepares the record of an issue that starts again, for example after a restart of the worker. */
final class RestartPreparation {

    private final GitHubClient gitHub;
    private final WorkspaceLayout layout;
    private final DefinitionLookup definitions;
    private final WorkerMove workerMove;

    RestartPreparation(GitHubClient gitHub, WorkspaceLayout layout, DefinitionLookup definitions,
                       WorkerMove workerMove) {
        this.gitHub = gitHub;
        this.workerMove = workerMove;
        this.layout = layout;
        this.definitions = definitions;
    }

    /**
     * A restart runs the action again from its start. Without the worktree, the issue goes back to the start state
     * in a new session, and the results that belong to the old worktree go away. The counts of the limits stay. A
     * pull request that GitHub merged makes the issue go to the finish state, so the change never gets made again.
     * A record whose definition does not load stays as it is: the workflow blocks it with the reason.
     */
    IssueRecord prepare(IssueRecord record) {
        boolean needsWorktree = record.action().filter(ActionType::needsWorktree).isPresent();
        if (!needsWorktree || Files.isDirectory(layout.worktree(record.issueNumber()))) {
            return record;
        }
        WorkflowDefinition workflow;
        try {
            workflow = definitions.definitionOf(record).workflow();
        } catch (IllegalStateException exception) {
            return record;
        }
        if (record.pullRequestNumber().map(gitHub::isMerged).orElse(false)) {
            return workerMove.moveTo(workflow, record, workflow.finishState());
        }
        IssueRecord cleared = record.withSessionId(UUID.randomUUID())
                .withoutReviews()
                .withoutConflictedPaths()
                .withoutRisk()
                .withoutBuiltTree()
                .withoutLive();
        return workerMove.moveTo(workflow, cleared, workflow.state(workflow.start()));
    }
}
