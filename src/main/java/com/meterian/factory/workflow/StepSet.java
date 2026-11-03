package com.meterian.factory.workflow;

import com.meterian.factory.definition.FactoryDefinition;
import com.meterian.factory.state.StepType;
import java.util.EnumMap;
import java.util.Map;

/** The steps of one definition version, with the helpers that depend on the version, for example the merge target. */
final class StepSet {

    private final FactoryDefinition definition;
    private final VersionServices version;
    private final Map<StepType, Step> steps = new EnumMap<>(StepType.class);

    private StepSet(FactoryDefinition definition, VersionServices version) {
        this.definition = definition;
        this.version = version;
    }

    static StepSet create(FactoryDefinition definition, StepServices services) {
        StepSet stepSet = new StepSet(definition, VersionServices.create(definition, services));
        VersionServices version = stepSet.version;
        ReviewSessionRetry retry = new ReviewSessionRetry();
        stepSet.steps.put(StepType.PREPARE, new PrepareStep(version));
        stepSet.steps.put(StepType.AGENT, new AgentStep(services, version));
        stepSet.steps.put(StepType.BUILD, new BuildStep(services, version));
        stepSet.steps.put(StepType.REVIEWS, new ReviewsStep(services, version, retry));
        stepSet.steps.put(StepType.RISK, new RiskStep(services, version, retry));
        stepSet.steps.put(StepType.MERGE_GATE, new MergeGateStep(services, version));
        stepSet.steps.put(StepType.FINISH, new FinishStep(services, version));
        return stepSet;
    }

    Step stepOf(StepType type) {
        return steps.get(type);
    }

    FactoryDefinition definition() {
        return definition;
    }

    TransitionRunner transitionRunner() {
        return version.transitionRunner();
    }

    PullRequestPublisher pullRequestPublisher() {
        return version.pullRequestPublisher();
    }
}
