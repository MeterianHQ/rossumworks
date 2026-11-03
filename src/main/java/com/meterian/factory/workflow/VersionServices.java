package com.meterian.factory.workflow;

import com.meterian.factory.build.BuildSettings;
import com.meterian.factory.configuration.FactoryConfiguration;
import com.meterian.factory.definition.FactoryDefinition;
import com.meterian.factory.merge.Merger;
import com.meterian.factory.merge.ReviewedCommit;
import com.meterian.factory.prompt.PromptLibrary;
import com.meterian.factory.prompt.PromptTexts;
import java.nio.file.Path;
import java.util.List;

/**
 * The helpers of one definition version. They use the merge target, the prompts, the build, and the allowed tools
 * of the version, so an issue keeps them when a new version comes.
 */
record VersionServices(PromptLibrary prompts, BuildSettings build, List<String> allowedTools,
                       String mergeTargetBranch, Merger merger, ConflictResolution conflictResolution,
                       IssueWorkspace workspace, PullRequestPublisher pullRequestPublisher,
                       ReviewedCommit reviewedCommit, ReviewRound reviewRound, TransitionRunner transitionRunner) {

    /** The build variables of the machine replace the variables of the definition with the same name. */
    static VersionServices create(FactoryDefinition definition, StepServices services) {
        FactoryConfiguration configuration = services.configuration();
        String mergeTargetBranch = definition.mergeTargetBranch();
        Path clone = configuration.cloneDirectory();
        Path gatekeeperDirectory = configuration.gatekeeperDirectory().toAbsolutePath();
        BuildSettings build = BuildSettings.combine(definition.build(), configuration.buildEnvironment());
        PromptLibrary prompts = new PromptLibrary(PromptTexts.from(definition), mergeTargetBranch);
        Merger merger = new Merger(services.git(), services.gitHub(), services.buildRunner(), clone,
                mergeTargetBranch, build, gatekeeperDirectory, services.mergeRetryDelay());
        TransitionRunner transitionRunner = new TransitionRunner(new FixLoop(prompts, services.ownerApproval()),
                services.gitHub(), services.liveMode());
        return new VersionServices(prompts, build, definition.allowedTools(), mergeTargetBranch, merger,
                new ConflictResolution(services.git(), mergeTargetBranch),
                new IssueWorkspace(configuration, services.git(), mergeTargetBranch),
                new PullRequestPublisher(services.gitHub(), services.git(), mergeTargetBranch, gatekeeperDirectory),
                new ReviewedCommit(services.git(), clone, mergeTargetBranch),
                new ReviewRound(services.phaseRunner(), prompts, definition.allowedTools()), transitionRunner);
    }

    String mergeTargetReference() {
        return "origin/" + mergeTargetBranch;
    }
}
