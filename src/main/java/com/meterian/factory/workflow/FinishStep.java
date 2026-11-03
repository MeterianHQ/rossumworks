package com.meterian.factory.workflow;

import com.meterian.factory.github.Issue;
import com.meterian.factory.state.IssueRecord;
import com.meterian.factory.state.IssueStatus;
import java.nio.file.Path;

final class FinishStep implements Step {

    private static final String FACTORY_LABEL = "factory";

    private final StepServices services;
    private final VersionServices version;

    FinishStep(StepServices services, VersionServices version) {
        this.services = services;
        this.version = version;
    }

    /**
     * Publishes DONE first, then cleans up. The label factory goes away last: after a failure the issue keeps
     * it, so the next poll runs this step again.
     */
    @Override
    public StepResult run(Issue issue, IssueRecord record, Path worktree) {
        IssueRecord done = services.statusPublisher().publish(issue, record, record.withStatus(IssueStatus.DONE));
        version.workspace().remove(worktree);
        services.ownerApproval().withdraw(record.issueNumber());
        services.gitHub().removeLabel(record.issueNumber(), FACTORY_LABEL);
        return StepResult.stay(done);
    }
}
