package com.meterian.factory.workflow;

import java.nio.file.Path;

/** Names the worktree and the branch of an issue. */
public final class WorkspaceLayout {

    private final Path workDirectory;

    public WorkspaceLayout(Path workDirectory) {
        this.workDirectory = workDirectory;
    }

    public Path worktree(int issueNumber) {
        return workDirectory.resolve("issue-" + issueNumber);
    }

    public static String branch(int issueNumber) {
        return "factory/issue-" + issueNumber;
    }
}
