package com.meterian.factory.workflow;

import com.meterian.factory.build.BuildRunner;
import com.meterian.factory.configuration.FactoryConfiguration;
import com.meterian.factory.git.Git;
import com.meterian.factory.github.GitHubClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * The services that all versions share: GitHub, git, the agent runner, the build runner, the usage, the live mode.
 *
 * @param mergeRetryDelay      the wait between two attempts of a squash merge
 * @param reviewNotesToPublish the notes of the review round of the current step, by issue number. Each issue
 *                             thread uses its own entry.
 */
record StepServices(FactoryConfiguration configuration, GitHubClient gitHub, Git git, BuildRunner buildRunner,
                    Duration mergeRetryDelay, OwnerApproval ownerApproval, StatusPublisher statusPublisher,
                    ForwardCheckFailure forwardCheckFailure, PhaseRunner phaseRunner, LiveMode liveMode,
                    Map<Integer, List<ReviewNote>> reviewNotesToPublish) {
}
