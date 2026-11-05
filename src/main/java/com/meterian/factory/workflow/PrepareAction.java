package com.meterian.factory.workflow;

import com.meterian.factory.github.Issue;
import com.meterian.factory.state.IssueRecord;
import java.nio.file.Path;

final class PrepareAction implements Action {

    private final VersionServices version;

    PrepareAction(VersionServices version) {
        this.version = version;
    }

    @Override
    public ActionResult run(Issue issue, IssueRecord record, Path worktree) {
        version.workspace().create(record.issueNumber(), worktree);
        return ActionResult.endWith(record, "done");
    }
}
