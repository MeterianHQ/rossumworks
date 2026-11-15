package com.meterian.factory.workflow;

import com.meterian.factory.github.Issue;
import com.meterian.factory.state.IssueRecord;
import java.nio.file.Path;

final class FinishAction implements Action {

    private static final String FACTORY_LABEL = "factory";

    private final ActionServices services;
    private final VersionServices version;

    FinishAction(ActionServices services, VersionServices version) {
        this.services = services;
        this.version = version;
    }

    /**
     * Publishes the start first, then cleans up. The label factory goes away last: after a failure the issue keeps
     * it, so the next poll runs this action again.
     */
    @Override
    public ActionResult run(Issue issue, IssueRecord record, Path worktree) {
        IssueRecord done = services.statusPublisher().publish(issue, record, record.withActionStarted());
        version.workspace().remove(worktree);
        services.ownerApproval().withdraw(record.issueNumber());
        services.gitHub().removeLabel(record.issueNumber(), FACTORY_LABEL);
        return ActionResult.stay(done);
    }
}
