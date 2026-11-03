package com.meterian.factory.workflow;

import com.meterian.factory.build.BuildRunner;
import com.meterian.factory.configuration.FactoryConfiguration;
import com.meterian.factory.git.Git;
import com.meterian.factory.github.GitHubClient;
import com.meterian.factory.merge.Merger;
import com.meterian.factory.merge.ReviewedCommit;
import com.meterian.factory.prompt.PromptLibrary;
import java.util.List;
import java.util.Map;

/**
 * The services that all versions share: GitHub, git, the agent runner, the build runner, the usage, the live mode.
 *
 * @param reviewNotesToPublish the notes of the review round of the current step, by issue number. Each issue
 *                             thread uses its own entry.
 */
record StepServices(FactoryConfiguration configuration, GitHubClient gitHub, Git git, PromptLibrary prompts,
                    BuildRunner buildRunner, Merger merger, OwnerApproval ownerApproval,
                    ConflictResolution conflictResolution, IssueWorkspace workspace,
                    PullRequestPublisher pullRequestPublisher, StatusPublisher statusPublisher,
                    ForwardCheckFailure forwardCheckFailure, ReviewedCommit reviewedCommit,
                    PhaseRunner phaseRunner, ReviewRound reviewRound,
                    Map<Integer, List<ReviewNote>> reviewNotesToPublish) {

    String mergeTargetReference() {
        return "origin/" + configuration.mergeTargetBranch();
    }
}
