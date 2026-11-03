package com.meterian.factory.workflow;

import com.meterian.factory.github.Issue;
import com.meterian.factory.state.IssueRecord;
import java.nio.file.Path;

final class PrepareStep implements Step {

    private final VersionServices version;

    PrepareStep(VersionServices version) {
        this.version = version;
    }

    @Override
    public StepResult run(Issue issue, IssueRecord record, Path worktree) {
        version.workspace().create(record.issueNumber(), worktree);
        return StepResult.endWith(record, "done");
    }
}
