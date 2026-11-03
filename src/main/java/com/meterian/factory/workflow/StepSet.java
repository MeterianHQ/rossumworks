package com.meterian.factory.workflow;

import com.meterian.factory.definition.FactoryDefinition;
import com.meterian.factory.state.StepType;
import java.util.EnumMap;
import java.util.Map;

/** The steps of one definition version, with the helpers that depend on the version, for example the merge target. */
final class StepSet {

    private final FactoryDefinition definition;
    private final Map<StepType, Step> steps = new EnumMap<>(StepType.class);

    private StepSet(FactoryDefinition definition) {
        this.definition = definition;
    }

    static StepSet create(FactoryDefinition definition, StepServices services) {
        StepSet stepSet = new StepSet(definition);
        ReviewSessionRetry retry = new ReviewSessionRetry();
        stepSet.steps.put(StepType.PREPARE, new PrepareStep(services));
        stepSet.steps.put(StepType.AGENT, new AgentStep(services));
        stepSet.steps.put(StepType.BUILD, new BuildStep(services));
        stepSet.steps.put(StepType.REVIEWS, new ReviewsStep(services, retry));
        stepSet.steps.put(StepType.RISK, new RiskStep(services, retry));
        stepSet.steps.put(StepType.MERGE_GATE, new MergeGateStep(services));
        stepSet.steps.put(StepType.FINISH, new FinishStep(services));
        return stepSet;
    }

    Step stepOf(StepType type) {
        return steps.get(type);
    }

    FactoryDefinition definition() {
        return definition;
    }
}
