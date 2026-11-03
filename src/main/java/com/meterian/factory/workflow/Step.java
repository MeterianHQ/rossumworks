package com.meterian.factory.workflow;

import com.meterian.factory.agent.AgentException;
import com.meterian.factory.agent.ApiKeyDetectedException;
import com.meterian.factory.github.Issue;
import com.meterian.factory.state.IssueRecord;
import java.nio.file.Path;

/** One step of a workflow. A step does its work for one record and tells the workflow what happened. */
interface Step {

    StepResult run(Issue issue, IssueRecord record, Path worktree) throws AgentException, ApiKeyDetectedException;
}
