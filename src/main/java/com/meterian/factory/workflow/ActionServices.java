package com.meterian.factory.workflow;

import com.meterian.factory.agent.LiveSessions;
import com.meterian.factory.build.BuildRunner;
import com.meterian.factory.configuration.FactoryConfiguration;
import com.meterian.factory.events.WorkerEvents;
import com.meterian.factory.git.Git;
import com.meterian.factory.github.GitHubClient;
import com.meterian.factory.ownership.OwnershipGuard;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * The services that all versions share: GitHub, git, the agent runner, the build runner, the usage, the live mode,
 * and the live sessions.
 *
 * @param retrySettings        the tries of the two technical retries
 * @param mergeRetryDelay      the wait between two attempts of a squash merge
 * @param reviewNotesToPublish the notes of the review round of the current action, by issue number. Each issue
 *                             thread uses its own entry.
 * @param ownershipGuard       the check that this factory still owns the issue before a change on GitHub
 * @param events               the lines of the file events.jsonl, for a monitoring agent
 */
record ActionServices(FactoryConfiguration configuration, GitHubClient gitHub, Git git, BuildRunner buildRunner,
                    Duration mergeRetryDelay, OwnerApproval ownerApproval, StatusPublisher statusPublisher,
                    ForwardCheckFailure forwardCheckFailure, PhaseRunner phaseRunner, LiveMode liveMode,
                    LiveSessions liveSessions, Map<Integer, List<ReviewNote>> reviewNotesToPublish,
                    RetrySettings retrySettings, OwnershipGuard ownershipGuard, WorkerEvents events) {
}
