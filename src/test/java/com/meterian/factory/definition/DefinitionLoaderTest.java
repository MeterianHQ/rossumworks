package com.meterian.factory.definition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.meterian.factory.state.IssueStatus;
import com.meterian.factory.state.ReviewKind;
import com.meterian.factory.state.StepType;
import com.meterian.factory.state.WaitReason;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class DefinitionLoaderTest {

    private static final String VALID_YAML = """
            version: 1
            workflow:
              start: prepare
              states:
                prepare:
                  status: in-progress
                  step: { type: prepare }
                  on: { done: implement }
                implement:
                  status: in-progress
                  step:
                    type: agent
                    prompts: { implement: prompts/implement.md, fix: prompts/fix.md, continue: prompts/continue.md }
                  on:
                    changed: build
                    question: { wait: question }
                    no-change: block
                    failed: block
                    conflict-left: fix-loop
                build:
                  status: in-progress
                  step: { type: build }
                  on: { passed: review, failed: fix-loop }
                review:
                  status: in-review
                  step:
                    type: reviews
                    prompts:
                      correctness: prompts/review-correctness.md
                      security: prompts/review-security.md
                      architecture: prompts/review-architecture.md
                      quality: prompts/review-quality.md
                  on: { approved: risk, rejected: fix-loop }
                risk:
                  status: in-review
                  step: { type: risk, prompts: { risk: prompts/risk.md } }
                  on: { assessed: merge }
                merge:
                  status: in-review
                  step: { type: merge-gate }
                  on:
                    merged: { to: done, keep-status: true }
                    needs-approval: { wait: merge-needs-approval }
                    needs-new-reviews: { to: build, keep-status: true }
                    conflict: fix-loop
                    factory-changed: { wait: factory-change-needs-approval }
                done:
                  status: done
                  step: { type: finish }
              loops:
                fix-loop: { to: implement, limit: fix-loops, exit: { block: fix-loop-limit } }
            limits:
              fix-loops: 2
            build:
              command: ./mvnw verify
              environment: { LANG: C.utf8 }
            claude:
              allowed-tools: [Read, Edit]
            merge:
              target-branch: main
            accounts:
              allowed: [alice]
            repositories: [example/one]
            """;

    @Test
    void loadsAValidDefinition() throws Exception {
        FactoryDefinition definition = DefinitionLoader.load(filesWith(VALID_YAML));

        WorkflowDefinition workflow = definition.workflow();
        assertThat(workflow.start()).isEqualTo("prepare");
        assertThat(workflow.states().keySet())
                .containsExactly("prepare", "implement", "build", "review", "risk", "merge", "done");
        StateDefinition build = workflow.state("build");
        assertThat(build.status()).isEqualTo(IssueStatus.IN_PROGRESS);
        assertThat(build.step().type()).isEqualTo(StepType.BUILD);
        assertThat(build.transitions().get("failed")).isEqualTo(new Transition.EnterLoop("fix-loop"));
        assertThat(build.transitions().get("passed")).isEqualTo(new Transition.GoTo("review", false));
        StateDefinition implement = workflow.state("implement");
        assertThat(implement.transitions().get("question")).isEqualTo(new Transition.Wait(WaitReason.QUESTION));
        assertThat(implement.transitions().get("failed")).isEqualTo(new Transition.Block(WaitReason.FAILURE));
        assertThat(workflow.state("merge").transitions().get("merged"))
                .isEqualTo(new Transition.GoTo("done", true));
        assertThat(workflow.state("merge").status()).isEqualTo(IssueStatus.IN_REVIEW);
        assertThat(workflow.state("review").step().reviewKinds())
                .containsExactly(ReviewKind.CORRECTNESS, ReviewKind.SECURITY, ReviewKind.ARCHITECTURE, ReviewKind.QUALITY);
        assertThat(workflow.stateWithStep(StepType.FINISH).name()).isEqualTo("done");
        LoopDefinition loop = workflow.loop("fix-loop").orElseThrow();
        assertThat(loop.limit()).isEqualTo(2);
        assertThat(loop.to()).isEqualTo("implement");
        assertThat(loop.exit()).isEqualTo(new Transition.Block(WaitReason.FIX_LOOP_LIMIT));
        assertThat(workflow.loop("other")).isEmpty();
        assertThat(definition.limits()).containsEntry("fix-loops", 2);
        assertThat(definition.build().command()).isEqualTo("./mvnw verify");
        assertThat(definition.build().environment()).containsEntry("LANG", "C.utf8");
        assertThat(definition.allowedTools()).containsExactly("Read", "Edit");
        assertThat(definition.mergeTargetBranch()).isEqualTo("main");
        assertThat(definition.allowedAccounts()).containsExactly("alice");
        assertThat(definition.repositories()).containsExactly("example/one");
        assertThat(definition.promptText("prompts/fix.md")).isEqualTo("text of prompts/fix.md");
    }

    @Test
    void refusesAPromptPathThatTheDefinitionDoesNotHold() throws Exception {
        FactoryDefinition definition = DefinitionLoader.load(filesWith(VALID_YAML));

        assertThatThrownBy(() -> definition.promptText("prompts/other.md"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void refusesAnUnknownStepType() {
        assertRefused(VALID_YAML.replace("type: agent", "type: deploy"), "unknown step type deploy");
    }

    @Test
    void refusesAStateWithoutATransitionForAnOutcome() {
        assertRefused(VALID_YAML.replace("on: { passed: review, failed: fix-loop }", "on: { passed: review }"),
                "state build has no transition for the outcome failed");
    }

    @Test
    void refusesAnOutcomeThatTheStepTypeDoesNotHave() {
        assertRefused(VALID_YAML.replace("passed: review", "pased: review"),
                "state build has the outcome pased, but the step type build has no such outcome");
    }

    @Test
    void refusesALoopWithoutALimit() {
        assertRefused(VALID_YAML.replace("fix-loop: { to: implement, limit: fix-loops, exit: { block: fix-loop-limit } }",
                "fix-loop: { to: implement, exit: block }"), "loop fix-loop has no limit");
    }

    @Test
    void refusesALoopLimitThatIsNotInLimits() {
        assertRefused(VALID_YAML.replace("limit: fix-loops", "limit: retries"),
                "loop fix-loop names the limit retries, which is not in limits");
    }

    @Test
    void refusesAMissingPromptFile() {
        assertRefused(VALID_YAML.replace("fix: prompts/fix.md", "fix: prompts/missing.md"),
                "prompt file prompts/missing.md does not exist");
    }

    @Test
    void refusesAMissingPromptKey() {
        assertRefused(VALID_YAML.replace(", continue: prompts/continue.md", ""),
                "state implement needs the prompt continue");
    }

    @Test
    void refusesAnUnknownPromptKey() {
        assertRefused(VALID_YAML.replace("security: prompts", "style: prompts"),
                "state review has the unknown prompt style");
    }

    @Test
    void refusesATransitionToAnUnknownState() {
        assertRefused(VALID_YAML.replace("passed: review", "passed: revue"),
                "state build goes to revue, which is not a state or a loop");
    }

    @Test
    void refusesAnUnknownStartState() {
        assertRefused(VALID_YAML.replace("start: prepare", "start: begin"), "start state begin is not a state");
    }

    @Test
    void refusesASecondLoop() {
        String loopLine = "fix-loop: { to: implement, limit: fix-loops, exit: { block: fix-loop-limit } }";
        assertRefused(VALID_YAML.replace(loopLine,
                loopLine + "\n    other-loop: { to: implement, limit: fix-loops, exit: block }"),
                "version 1 supports one loop");
    }

    @Test
    void refusesAMissingStateOfARequiredStepType() {
        String withoutFinish = VALID_YAML
                .replace("    done:\n      status: done\n      step: { type: finish }\n", "")
                .replace("merged: { to: done, keep-status: true }", "merged: block");
        assertRefused(withoutFinish, "needs exactly one state with the step type finish");
    }

    @Test
    void refusesAWorkflowWithoutAStateOfAnEngineStepType() {
        String withoutRisk = VALID_YAML
                .replace("    risk:\n      status: in-review\n      step: { type: risk, prompts: { risk: prompts/risk.md } }\n      on: { assessed: merge }\n", "")
                .replace("approved: risk", "approved: merge");
        assertRefused(withoutRisk, "needs exactly one state with the step type risk, but it has 0");
    }

    @Test
    void refusesAWorkflowWithTwoStatesOfAnEngineStepType() {
        String secondRisk = VALID_YAML.replace("    merge:\n",
                "    second-risk:\n      status: in-review\n      step: { type: risk, prompts: { risk: prompts/risk.md } }\n      on: { assessed: merge }\n    merge:\n");
        assertRefused(secondRisk, "needs exactly one state with the step type risk, but it has 2");
    }

    @Test
    void refusesAReviewsStepWithoutAllReviewKinds() {
        assertRefused(VALID_YAML.replace("          quality: prompts/review-quality.md\n", ""),
                "state review needs the prompt quality");
    }

    @Test
    void refusesALoopExitThatEntersALoop() {
        assertRefused(VALID_YAML.replace("exit: { block: fix-loop-limit }", "exit: fix-loop"),
                "loop fix-loop exit cannot enter a loop");
    }

    @Test
    void refusesAStartStateThatIsNotAPrepareStep() {
        assertRefused(VALID_YAML.replace("start: prepare", "start: implement"),
                "start state implement needs the step type prepare");
    }

    @Test
    void refusesAFinishStateWithTransitions() {
        assertRefused(VALID_YAML.replace("step: { type: finish }", "step: { type: finish }\n      on: { done: prepare }"),
                "state done has the step type finish and cannot have transitions");
    }

    @Test
    void refusesAnUnknownKey() {
        assertRefused(VALID_YAML.replace("repositories:", "repositorys:"), "unknown key repositorys");
    }

    @Test
    void refusesAMissingClaudeSection() {
        assertRefused(VALID_YAML.replace("claude:\n  allowed-tools: [Read, Edit]\n", ""), "claude.allowed-tools");
    }

    @Test
    void refusesAMissingAllowedTools() {
        assertRefused(VALID_YAML.replace("allowed-tools: [Read, Edit]", "{}"), "claude.allowed-tools");
    }

    @Test
    void refusesAnEmptyAllowedTools() {
        assertRefused(VALID_YAML.replace("allowed-tools: [Read, Edit]", "allowed-tools: []"),
                "claude.allowed-tools");
    }

    @Test
    void refusesAnUnknownVersion() {
        assertRefused(VALID_YAML.replace("version: 1", "version: 2"), "version 2 is not supported");
    }

    @Test
    void refusesAYamlError() {
        assertRefused("workflow: [", "factory.yaml");
    }

    @Test
    void refusesAMissingMainFile() {
        assertThatThrownBy(() -> DefinitionLoader.load(path -> Optional.empty()))
                .isInstanceOf(DefinitionException.class)
                .hasMessageContaining(".factory/factory.yaml does not exist");
    }

    private static void assertRefused(String yaml, String expectedMessage) {
        assertThatThrownBy(() -> DefinitionLoader.load(filesWith(yaml)))
                .isInstanceOf(DefinitionException.class)
                .hasMessageContaining(expectedMessage);
    }

    private static DefinitionFiles filesWith(String mainYaml) {
        Map<String, String> files = new HashMap<>();
        files.put("factory.yaml", mainYaml);
        for (String name : List.of("implement", "fix", "continue", "review-correctness", "review-security", "review-architecture",
                "review-quality", "risk")) {
            files.put("prompts/" + name + ".md", "text of prompts/" + name + ".md");
        }
        return path -> Optional.ofNullable(files.get(path));
    }
}
