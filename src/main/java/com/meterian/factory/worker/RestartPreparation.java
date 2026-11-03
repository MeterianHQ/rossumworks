package com.meterian.factory.worker;

import com.meterian.factory.definition.DefinitionLookup;
import com.meterian.factory.definition.StateDefinition;
import com.meterian.factory.definition.WorkflowDefinition;
import com.meterian.factory.github.GitHubClient;
import com.meterian.factory.state.IssueRecord;
import com.meterian.factory.state.StepType;
import com.meterian.factory.workflow.WorkspaceLayout;
import java.nio.file.Files;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

/** Prepares the record of an issue that starts again, for example after a restart of the worker. */
final class RestartPreparation {

    private static final Set<StepType> STEPS_WITH_A_WORKTREE =
            EnumSet.of(StepType.AGENT, StepType.BUILD, StepType.REVIEWS, StepType.RISK, StepType.MERGE_GATE);

    private final GitHubClient gitHub;
    private final WorkspaceLayout layout;
    private final DefinitionLookup definitions;

    RestartPreparation(GitHubClient gitHub, WorkspaceLayout layout, DefinitionLookup definitions) {
        this.gitHub = gitHub;
        this.layout = layout;
        this.definitions = definitions;
    }

    /**
     * A restart runs the step again from its start. Without the worktree, the issue goes back to the start state
     * in a new session, and the results that belong to the old worktree go away. The fix-loop count stays. A pull
     * request that GitHub merged makes the issue go to the finish state, so the change never gets made again.
     */
    IssueRecord prepare(IssueRecord record) {
        boolean needsWorktree = STEPS_WITH_A_WORKTREE.contains(record.step());
        if (!needsWorktree || Files.isDirectory(layout.worktree(record.issueNumber()))) {
            return record;
        }
        WorkflowDefinition workflow = definitions.definitionOf(record).workflow();
        if (record.pullRequestNumber().map(gitHub::isMerged).orElse(false)) {
            return moveTo(record, workflow.stateWithStep(StepType.FINISH));
        }
        IssueRecord cleared = record.withSessionId(UUID.randomUUID())
                .withoutReviews()
                .withoutConflictedPaths()
                .withoutRisk()
                .withoutBuiltTree();
        return moveTo(cleared, workflow.state(workflow.start()));
    }

    private static IssueRecord moveTo(IssueRecord record, StateDefinition state) {
        return record.withState(state.name(), state.step().type());
    }
}
