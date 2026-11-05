package com.meterian.factory.definition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.meterian.factory.state.ActionType;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class DefinitionLoaderTest {

    private static final String DEFAULT_YAML = readDefaultFile("factory.yaml");
    private static final List<String> PROMPT_FILES = List.of("implement", "fix", "continue", "resume",
            "review-correctness", "review-security", "review-architecture", "review-quality", "risk");

    private static final String BUILD_ACTION = "      action: { type: build }\n";
    private static final String BUILD_EVENTS = "on: { passed: review, failed: implement }";
    private static final String CODING_PROMPTS =
            "prompts: { implement: prompts/implement.md, fix: prompts/fix.md, continue: prompts/continue.md }";
    private static final String REVIEW_PROMPTS = """
                    prompts:
                      correctness: prompts/review-correctness.md
                      security: prompts/review-security.md
                      architecture: prompts/review-architecture.md
                      quality: prompts/review-quality.md
            """;
    private static final String ON_ANY_STATE =
            "on-any-state: { block: blocked, enter: live, issue-text-changed: text-changed }";

    @Test
    void loadsTheDefaultWorkflow() throws Exception {
        FactoryDefinition definition = DefinitionLoader.load(new ClasspathDefinitionFiles());

        WorkflowDefinition workflow = definition.workflow();
        assertThat(workflow.start()).isEqualTo("prepare");
        assertThat(workflow.onAnyState()).isEqualTo(new OnAnyState("blocked", "live", "text-changed"));
        assertThat(workflow.state("implement").limit())
                .isEqualTo(Optional.of(new LimitDefinition(3, new Target.ToState("blocked"))));
        assertThat(workflow.state("text-changed").on().get("approved")).isEqualTo(new Target.ToPrevious());
        assertThat(workflow.state("merge").action().orElseThrow().mergeMode()).isEqualTo(MergeMode.RISK);
        assertThat(workflow.columns()).containsExactly("in-progress", "in-review", "waits-for-you", "blocked", "done");
    }

    @Test
    void loadsTheOtherSectionsOfTheDefault() throws Exception {
        FactoryDefinition definition = DefinitionLoader.load(new ClasspathDefinitionFiles());

        assertThat(definition.build()).isEqualTo(new BuildDefinition("./mvnw verify", Map.of("LANG", "C.utf8")));
        assertThat(definition.allowedTools()).startsWith("Read", "Edit").hasSize(11);
        assertThat(definition.mergeTargetBranch()).isEqualTo("main");
        assertThat(definition.repositories()).isEmpty();
        assertThat(definition.promptText("prompts/fix.md")).isEqualTo(readDefaultFile("prompts/fix.md"));
    }

    @Test
    void givesTheStatesOfTheDefaultTheirActionsAndEvents() throws Exception {
        WorkflowDefinition workflow = DefinitionLoader.load(new ClasspathDefinitionFiles()).workflow();

        assertThat(workflow.finishState().name()).isEqualTo("done");
        assertThat(workflow.findStateWithAction(ActionType.APPROVAL).orElseThrow().name())
                .isEqualTo("waits-for-approval");
        assertThat(workflow.findStateWithAction(ActionType.CODING).orElseThrow().action().orElseThrow()
                .promptPaths()).containsOnlyKeys("implement", "fix", "continue");
        assertThat(workflow.state("blocked").actionType()).isEmpty();
        assertThat(workflow.state("blocked").waitsForYou()).isTrue();
        assertThat(workflow.state("blocked").events()).containsExactly("continue");
        assertThat(workflow.state("live").waitsForYou()).isTrue();
        assertThat(workflow.state("build").waitsForYou()).isFalse();
        assertThat(workflow.state("build").events()).containsExactlyInAnyOrder("passed", "failed");
        assertThat(workflow.state("done").on()).isEmpty();
    }

    @Test
    void refusesAnUnknownKeyOfAState() {
        assertRefused(DEFAULT_YAML.replace(BUILD_ACTION, BUILD_ACTION + "      step: { type: build }\n"),
                "factory.yaml: the state build has the unknown key step");
    }

    @Test
    void refusesTheRootKeyLimits() {
        assertRefused(DEFAULT_YAML + "limits: { fix-loops: 2 }\n", "factory.yaml: unknown key limits");
    }

    @Test
    void refusesAStateWithoutColumn() {
        assertRefused(DEFAULT_YAML.replace("    build:\n      column: in-progress\n", "    build:\n"),
                "factory.yaml: the state build has no column");
    }

    @Test
    void refusesTheColumnQueued() {
        assertRefused(DEFAULT_YAML.replace("    build:\n      column: in-progress\n", "    build:\n      column: queued\n"),
                "factory.yaml: the state build has the column queued, which the factory keeps for new issues");
    }

    @Test
    void refusesAColumnThatIsNotAName() {
        assertRefused(DEFAULT_YAML.replace("    review:\n      column: in-review\n", "    review:\n      column: In Review\n"),
                "factory.yaml: the state review has the column In Review;"
                        + " a column has only small letters, digits, and hyphens");
    }

    /** GitHub allows 50 characters in a label, and the label is factory:is: and the column. */
    @Test
    void refusesAColumnLongerThanTheLabelAllows() {
        String column = "a".repeat(40);
        assertRefused(DEFAULT_YAML.replace("    review:\n      column: in-review\n",
                        "    review:\n      column: " + column + "\n"),
                "factory.yaml: the state review has the column " + column
                        + "; a column has 39 characters or less, because a GitHub label has 50 characters or less");
    }

    @Test
    void acceptsAColumnOf39Characters() throws DefinitionException {
        String column = "a".repeat(39);
        FactoryDefinition definition = DefinitionLoader.load(filesWith(DEFAULT_YAML.replace(
                "    review:\n      column: in-review\n",
                "    review:\n      column: " + column + "\n")));

        assertThat(definition.workflow().state("review").column()).isEqualTo(column);
    }

    @Test
    void refusesAStartWithoutPrepare() {
        assertRefused(DEFAULT_YAML.replace("start: prepare", "start: build"),
                "factory.yaml: the start state build has the action build; it needs the action prepare");
    }

    @Test
    void refusesAWorkflowWithoutFinish() {
        assertRefused(DEFAULT_YAML.replace("      action: { type: finish }\n", "      on: { continue: blocked }\n"),
                "factory.yaml: no state has the action finish");
    }

    @Test
    void refusesTwoStatesWithTheSameAction() {
        String secondBuild = "    build-again:\n      column: in-progress\n" + BUILD_ACTION + "      " + BUILD_EVENTS + "\n";
        assertRefused(DEFAULT_YAML.replace("    review:\n", secondBuild + "    review:\n"),
                "factory.yaml: the states build and build-again both have the action build;"
                        + " an action other than approval is in one state at most");
    }

    @Test
    void acceptsTwoApprovalStates() throws Exception {
        String secondApproval = "    second-approval:\n      column: waits-for-you\n"
                + "      action: { type: approval }\n      on: { approved: merge }\n";

        FactoryDefinition definition = DefinitionLoader.load(
                filesWith(DEFAULT_YAML.replace("needs-new-reviews: build", "needs-new-reviews: second-approval")
                        .replace("    live:\n", secondApproval + "    live:\n")));

        assertThat(definition.workflow().states().values().stream()
                .filter(state -> state.actionType().equals(Optional.of(ActionType.APPROVAL)))).hasSize(4);
    }

    @Test
    void refusesAMissingOnAnyStateKey() {
        assertRefused(DEFAULT_YAML.replace(ON_ANY_STATE, "on-any-state: { block: blocked, issue-text-changed: text-changed }"),
                "factory.yaml: key workflow.on-any-state.enter is missing");
    }

    @Test
    void refusesAnOnAnyStateTargetThatIsNotAState() {
        assertRefused(DEFAULT_YAML.replace(ON_ANY_STATE, ON_ANY_STATE.replace("block: blocked", "block: stuck")),
                "factory.yaml: on-any-state block goes to the state stuck, which does not exist");
    }

    @Test
    void refusesABlockTargetWithAnAction() {
        assertRefused(DEFAULT_YAML.replace(ON_ANY_STATE, ON_ANY_STATE.replace("block: blocked", "block: build")),
                "factory.yaml: the block target build has the action build; the block target must have no action");
    }

    @Test
    void refusesABlockTargetWithTheFinishAction() {
        assertRefused(DEFAULT_YAML.replace(ON_ANY_STATE, ON_ANY_STATE.replace("block: blocked", "block: done")),
                "factory.yaml: the block target done has the action finish; the block target must have no action");
    }

    @Test
    void acceptsTheBlockTargetOfTheDefaultWorkflow() throws Exception {
        WorkflowDefinition workflow = DefinitionLoader.load(filesWith(DEFAULT_YAML)).workflow();

        assertThat(workflow.state(workflow.onAnyState().block()).actionType()).isEmpty();
    }

    @Test
    void refusesATextTargetWithoutApproval() {
        assertRefused(DEFAULT_YAML.replace(ON_ANY_STATE,
                        ON_ANY_STATE.replace("issue-text-changed: text-changed", "issue-text-changed: blocked")),
                "factory.yaml: the issue-text-changed target blocked needs the action approval");
    }

    @Test
    void refusesAnEnterTargetWithoutConversation() {
        assertRefused(DEFAULT_YAML.replace(ON_ANY_STATE, ON_ANY_STATE.replace("enter: live", "enter: blocked")),
                "factory.yaml: the enter target blocked needs the action conversation");
    }

    @Test
    void refusesAMissingEvent() {
        assertRefused(DEFAULT_YAML.replace(BUILD_EVENTS, "on: { passed: review }"),
                "factory.yaml: the state build has no next state for the event failed");
    }

    @Test
    void refusesAnUnknownEvent() {
        assertRefused(DEFAULT_YAML.replace(BUILD_EVENTS, "on: { passed: review, failed: implement, crashed: blocked }"),
                "factory.yaml: the state build has the event crashed, but the action build has no such event");
    }

    @Test
    void refusesAnEventOnFinish() {
        assertRefused(DEFAULT_YAML.replace("      action: { type: finish }\n",
                        "      action: { type: finish }\n      on: { done: blocked }\n"),
                "factory.yaml: the state done has the event done, but the action finish has no such event");
    }

    @Test
    void refusesATargetThatIsNotAState() {
        assertRefused(DEFAULT_YAML.replace(BUILD_EVENTS, "on: { passed: deploy, failed: implement }"),
                "factory.yaml: the state build goes to the state deploy for the event passed, but deploy does not exist");
    }

    @Test
    void refusesALimitThenPrevious() {
        assertRefused(DEFAULT_YAML.replace("limit: { entries: 3, then: blocked }", "limit: { entries: 3, then: previous }"),
                "factory.yaml: the state implement has the limit then previous; the then of a limit must be a state name");
    }

    @Test
    void refusesALimitThenThatIsNotAState() {
        assertRefused(DEFAULT_YAML.replace("limit: { entries: 3, then: blocked }", "limit: { entries: 3, then: stuck }"),
                "factory.yaml: the state implement goes to the state stuck at its limit, but stuck does not exist");
    }

    @Test
    void refusesALimitWithoutEntries() {
        assertRefused(DEFAULT_YAML.replace("limit: { entries: 3, then: blocked }", "limit: { then: blocked }"),
                "factory.yaml: key workflow.states.implement.limit.entries must be an integer of 1 or more");
    }

    @Test
    void refusesALimitBelowOne() {
        assertRefused(DEFAULT_YAML.replace("limit: { entries: 3, then: blocked }", "limit: { entries: 0, then: blocked }"),
                "factory.yaml: key workflow.states.implement.limit.entries must be an integer of 1 or more");
    }

    @Test
    void refusesTheStateNamePrevious() {
        assertRefused(DEFAULT_YAML.replace("    blocked:\n", "    previous:\n      column: blocked\n"
                        + "      on: { continue: blocked }\n    blocked:\n"),
                "factory.yaml: the state name previous is reserved");
    }

    @Test
    void refusesAMissingCodingPrompt() {
        assertRefused(DEFAULT_YAML.replace(CODING_PROMPTS, CODING_PROMPTS.replace(" fix: prompts/fix.md,", "")),
                "factory.yaml: the state implement needs the prompt fix");
    }

    @Test
    void refusesAnUnknownPrompt() {
        assertRefused(DEFAULT_YAML.replace(CODING_PROMPTS,
                        CODING_PROMPTS.replace(" }", ", review: prompts/review-quality.md }")),
                "factory.yaml: the state implement has the unknown prompt review");
    }

    @Test
    void refusesReviewsWithoutPrompt() {
        assertRefused(DEFAULT_YAML.replace(REVIEW_PROMPTS, ""),
                "factory.yaml: the state review needs one review prompt or more");
    }

    @Test
    void refusesAReviewNameThatIsNotAName() {
        assertRefused(DEFAULT_YAML.replace("security: prompts/review-security.md",
                        "Security Check: prompts/review-security.md"),
                "factory.yaml: the state review has the review Security Check;"
                        + " a review name has only small letters, digits, and hyphens");
    }

    @Test
    void refusesAMissingPromptFile() {
        Map<String, String> files = createFiles(DEFAULT_YAML);
        files.remove("prompts/fix.md");

        assertThatThrownBy(() -> DefinitionLoader.load(path -> Optional.ofNullable(files.get(path))))
                .isInstanceOf(DefinitionException.class)
                .hasMessage("factory.yaml: the state implement: prompt file prompts/fix.md does not exist");
    }

    @Test
    void refusesModeOnAnotherAction() {
        assertRefused(DEFAULT_YAML.replace(BUILD_ACTION, "      action: { type: build, mode: risk }\n"),
                "factory.yaml: the state build has the unknown key action.mode");
    }

    @Test
    void refusesAnUnknownMode() {
        assertRefused(DEFAULT_YAML.replace("mode: risk", "mode: yolo"),
                "factory.yaml: the state merge has the unknown mode yolo");
    }

    @Test
    void readsTheMergeModes() throws Exception {
        WorkflowDefinition workflow = DefinitionLoader.load(
                filesWith(DEFAULT_YAML.replace("mode: risk", "mode: always-ask"))).workflow();

        assertThat(workflow.state("merge").action().orElseThrow().mergeMode()).isEqualTo(MergeMode.ALWAYS_ASK);
        assertThat(workflow.state("build").action().orElseThrow().mergeMode()).isEqualTo(MergeMode.RISK);
    }

    @Test
    void requiresResumeForAnEntryWithoutFindings() {
        assertRefused(DEFAULT_YAML.replace("on: { assessed: merge }", "on: { assessed: implement }"),
                "factory.yaml: the state implement needs the prompt resume,"
                        + " because the state risk goes to it with the event assessed");
    }

    @Test
    void requiresResumeForPreviousFromAnActionThatDoesNotWaitForYou() {
        assertRefused(DEFAULT_YAML.replace("on: { assessed: merge }", "on: { assessed: previous }"),
                "factory.yaml: the state implement needs the prompt resume,"
                        + " because the state risk goes to previous with the event assessed");
    }

    @Test
    void requiresResumeForALimitThenToTheCodingState() {
        assertRefused(DEFAULT_YAML.replace("limit: { entries: 10, then: blocked }", "limit: { entries: 10, then: implement }"),
                "factory.yaml: the state implement needs the prompt resume,"
                        + " because the state review goes to it at the limit of review");
    }

    @Test
    void requiresResumeForContinueFromAStateWithoutActionThatIsNotTheBlockTarget() {
        String pausedState = "    paused:\n      column: blocked\n      on: { continue: implement }\n";
        assertRefused(DEFAULT_YAML.replace("    blocked:\n", pausedState + "    blocked:\n"),
                "factory.yaml: the state implement needs the prompt resume,"
                        + " because the state paused goes to it with the event continue");
    }

    @Test
    void acceptsAnEntryWithoutFindingsWhenResumeExists() throws Exception {
        String yaml = DEFAULT_YAML.replace("needs-new-reviews: build", "needs-new-reviews: implement")
                .replace(CODING_PROMPTS, CODING_PROMPTS.replace(" }", ", resume: prompts/resume.md }"));

        FactoryDefinition definition = DefinitionLoader.load(filesWith(yaml));

        assertThat(definition.promptText("prompts/resume.md")).isEqualTo("prompt resume");
    }

    @Test
    void doesNotRequireResumeForTheDefault() throws Exception {
        assertThat(readDefaultFile("prompts/resume.md")).contains("Continue the work of the issue.", "{{findings}}");

        FactoryDefinition definition = DefinitionLoader.load(new ClasspathDefinitionFiles());

        assertThat(definition.prompts()).doesNotContainKey("prompts/resume.md");
    }

    @Test
    void refusesAMissingBuildCommand() {
        assertRefused(DEFAULT_YAML.replace("  command: ./mvnw verify\n", ""),
                "factory.yaml: key build.command is missing");
    }

    @Test
    void refusesMissingAllowedTools() {
        String allowedTools = DEFAULT_YAML.substring(DEFAULT_YAML.indexOf("  allowed-tools:"),
                DEFAULT_YAML.indexOf("\nmerge:\n") + 1);
        assertRefused(DEFAULT_YAML.replace(allowedTools, "  allowed-tools: []\n"),
                "factory.yaml: key claude.allowed-tools is missing or has no tool");
    }

    @Test
    void refusesAMissingTargetBranch() {
        assertRefused(DEFAULT_YAML.replace("  target-branch: main\n", "  {}\n"),
                "factory.yaml: key merge.target-branch is missing");
    }

    private static void assertRefused(String yaml, String expectedMessage) {
        assertThatThrownBy(() -> DefinitionLoader.load(filesWith(yaml)))
                .isInstanceOf(DefinitionException.class)
                .hasMessage(expectedMessage);
    }

    private static DefinitionFiles filesWith(String mainYaml) {
        Map<String, String> files = createFiles(mainYaml);
        return path -> Optional.ofNullable(files.get(path));
    }

    private static Map<String, String> createFiles(String mainYaml) {
        Map<String, String> files = new HashMap<>();
        files.put("factory.yaml", mainYaml);
        for (String name : PROMPT_FILES) {
            files.put("prompts/" + name + ".md", "prompt " + name);
        }
        return files;
    }

    /** The default definition of the jar. */
    private static String readDefaultFile(String relativePath) {
        try {
            return new ClasspathDefinitionFiles().read(relativePath).orElseThrow();
        } catch (DefinitionException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
