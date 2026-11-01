package com.meterian.factory.worker;

import com.meterian.factory.agent.AgentRunner;
import com.meterian.factory.build.BuildRunner;
import com.meterian.factory.workflow.WorkspaceLayout;

/** Stops the work of the factory on an issue: the agent phase and the build that run now. */
public final class PhaseStopper {

    private final AgentRunner agentRunner;
    private final BuildRunner buildRunner;
    private final WorkspaceLayout layout;

    public PhaseStopper(AgentRunner agentRunner, BuildRunner buildRunner, WorkspaceLayout layout) {
        this.agentRunner = agentRunner;
        this.buildRunner = buildRunner;
        this.layout = layout;
    }

    public void stopIssue(int issueNumber) {
        agentRunner.stopPhase(issueNumber);
        buildRunner.stopBuild(layout.worktree(issueNumber));
    }

    /** After this call, no new agent phase and no new build starts. The running builds end. */
    public void refuseNewWork() {
        agentRunner.refuseNewPhases();
        buildRunner.stopAllBuilds();
    }
}
