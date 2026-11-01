package com.meterian.factory.worker;

import com.meterian.factory.agent.AgentRunner;
import com.meterian.factory.agent.LiveSessions;
import com.meterian.factory.build.BuildRunner;
import com.meterian.factory.workflow.WorkspaceLayout;

/** Stops the work of the factory on an issue: the agent phase, the build, and the live session. */
public final class PhaseStopper {

    private static final System.Logger LOGGER = System.getLogger(PhaseStopper.class.getName());

    private final AgentRunner agentRunner;
    private final BuildRunner buildRunner;
    private final WorkspaceLayout layout;
    private final LiveSessions liveSessions;

    public PhaseStopper(AgentRunner agentRunner, BuildRunner buildRunner, WorkspaceLayout layout,
                        LiveSessions liveSessions) {
        this.agentRunner = agentRunner;
        this.buildRunner = buildRunner;
        this.layout = layout;
        this.liveSessions = liveSessions;
    }

    /** A block stops all the work on the issue, also the live session of the owner. */
    public void stopIssue(int issueNumber) {
        stopRunningWork(issueNumber);
        closeLiveSession(issueNumber);
    }

    /** A stop of the worker keeps the live session: tmux keeps it, and the next worker finds it again. */
    public void stopRunningWork(int issueNumber) {
        agentRunner.stopPhase(issueNumber);
        buildRunner.stopBuild(layout.worktree(issueNumber));
    }

    /** After this call, no new agent phase and no new build starts. The running builds end. */
    public void refuseNewWork() {
        agentRunner.refuseNewPhases();
        buildRunner.stopAllBuilds();
    }

    /** A failure of tmux never stops the block: the issue still becomes BLOCKED. */
    public void closeLiveSession(int issueNumber) {
        try {
            liveSessions.close(issueNumber);
        } catch (RuntimeException exception) {
            LOGGER.log(System.Logger.Level.WARNING, "The live session of the issue #{0} did not close: {1}",
                    issueNumber, exception.getMessage());
        }
    }
}
