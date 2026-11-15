package com.meterian.factory.workflow;

import com.meterian.factory.definition.WorkflowDefinition;
import com.meterian.factory.github.Issue;
import com.meterian.factory.state.ActionType;
import com.meterian.factory.state.HandOver;
import com.meterian.factory.state.IssueRecord;
import java.nio.file.Path;
import java.util.Optional;

final class PrepareAction implements Action {

    private static final String DONE_EVENT = "done";

    private final VersionServices version;
    private final WorkflowDefinition workflow;

    PrepareAction(VersionServices version, WorkflowDefinition workflow) {
        this.version = version;
        this.workflow = workflow;
    }

    @Override
    public ActionResult run(Issue issue, IssueRecord record, Path worktree) {
        if (record.handOver().equals(Optional.of(HandOver.CONTINUES_PUSHED_BRANCH))) {
            return continuePushedBranch(record, worktree);
        }
        version.workspace().create(record.issueNumber(), worktree);
        return ActionResult.endWith(record, DONE_EVENT);
    }

    /**
     * An earlier owner pushed the branch of the issue: the worktree starts from it, and the issue goes to the first
     * build state, because the build proves the branch on this machine. A workflow without a build state goes on
     * with the event done.
     */
    private ActionResult continuePushedBranch(IssueRecord record, Path worktree) {
        version.workspace().createFromPushedBranch(record.issueNumber(), worktree);
        IssueRecord continuing = record.withoutHandOver();
        return workflow.findFirstStateFromStart(ActionType.BUILD)
                .map(buildState -> ActionResult.moveTo(continuing, buildState.name()))
                .orElseGet(() -> ActionResult.endWith(continuing, DONE_EVENT));
    }
}
