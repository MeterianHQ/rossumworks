package com.meterian.factory.workflow;

import com.meterian.factory.agent.AgentException;
import com.meterian.factory.agent.ApiKeyDetectedException;
import com.meterian.factory.github.Issue;
import com.meterian.factory.state.IssueRecord;
import java.nio.file.Path;
import java.util.Optional;

/** One action of a workflow. An action does its work for one record and tells the workflow what happened. */
interface Action {

    ActionResult run(Issue issue, IssueRecord record, Path worktree) throws AgentException, ApiKeyDetectedException;

    /**
     * The work when the action starts, before its first run. A result ends the advance, and the action runs at a
     * later advance. Most actions have no such work.
     */
    default Optional<ActionResult> start(Issue issue, IssueRecord record) {
        return Optional.empty();
    }
}
