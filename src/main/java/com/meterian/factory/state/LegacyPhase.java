package com.meterian.factory.state;

/** The phases of the records from before the states. Only the JSON reader uses it. */
public enum LegacyPhase {
    PREPARE("prepare", StepType.PREPARE),
    IMPLEMENT("implement", StepType.AGENT),
    BUILD("build", StepType.BUILD),
    REVIEW("review", StepType.REVIEWS),
    RISK("risk", StepType.RISK),
    MERGE("merge", StepType.MERGE_GATE),
    DONE("done", StepType.FINISH);

    private final String stateName;
    private final StepType stepType;

    LegacyPhase(String stateName, StepType stepType) {
        this.stateName = stateName;
        this.stepType = stepType;
    }

    public String stateName() {
        return stateName;
    }

    public StepType stepType() {
        return stepType;
    }
}
