package com.meterian.factory.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import com.meterian.factory.definition.BuiltInDefinition;
import com.meterian.factory.definition.DefinitionException;
import com.meterian.factory.definition.DefinitionLoader;
import com.meterian.factory.definition.DefinitionLookup;
import com.meterian.factory.definition.FactoryDefinition;
import com.meterian.factory.git.ProcessGit;
import com.meterian.factory.github.Issue;
import com.meterian.factory.prompt.Finding;
import com.meterian.factory.prompt.PromptLibrary;
import com.meterian.factory.prompt.PromptTexts;
import com.meterian.factory.state.IssueRecord;
import com.meterian.factory.state.IssueStatus;
import com.meterian.factory.state.StepType;
import com.meterian.factory.state.WaitReason;
import com.meterian.factory.testing.FakeGitHubClient;
import com.meterian.factory.testing.FakeLiveSessions;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TransitionRunnerTest {

    private static final int ISSUE = 7;
    private static final String APPROVE_LABEL = "factory:approve";
    private static final String SMALL_YAML = """
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
                    changed: { to: build, keep-status: true }
                    question: { wait: question }
                    no-change: { wait: merge-needs-approval }
                    failed: block
                    conflict-left: fix-loop
                build:
                  status: in-review
                  step: { type: build }
                  on: { passed: done, failed: fix-loop }
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
                    merged: done
                    needs-approval: { wait: merge-needs-approval }
                    needs-new-reviews: build
                    conflict: fix-loop
                    factory-changed: { wait: factory-change-needs-approval }
                done:
                  status: done
                  step: { type: finish }
              loops:
                fix-loop: { to: implement, limit: fix-loops, exit: { block: fix-loop-limit } }
            limits:
              fix-loops: 1
            build:
              command: "true"
            claude:
              allowed-tools: [Read]
            merge:
              target-branch: main
            accounts:
              allowed: [bbossola]
            """;
    private static final List<Finding> FINDINGS = List.of(new Finding("The build failed", "a test fails"));

    @TempDir
    Path worktree;

    private final FakeGitHubClient gitHub = new FakeGitHubClient();
    private final PromptLibrary prompts = new PromptLibrary(PromptTexts.from(BuiltInDefinition.load()), "main");
    private FactoryDefinition definition;
    private TransitionRunner runner;
    private Issue issue;

    @BeforeEach
    void createRunner() throws DefinitionException {
        definition = loadSmallDefinition();
        gitHub.setAuthenticatedLogin("factory-bot");
        gitHub.addIssue(ISSUE, "Change the greeting", "The greeting must change.");
        issue = gitHub.issue(ISSUE);
        OwnerApproval ownerApproval = new OwnerApproval(gitHub, new RequestReader(gitHub, () -> List.of("bbossola")));
        LiveMode liveMode = new LiveMode(new ProcessGit(), gitHub, new FakeLiveSessions(), ownerApproval,
                () -> List.of("bbossola"), new PageRequests(Clock.systemUTC()), DefinitionLookup.builtInOnly(definition));
        runner = new TransitionRunner(new FixLoop(prompts, ownerApproval), gitHub, () -> List.of("bbossola"),
                liveMode);
    }

    private static FactoryDefinition loadSmallDefinition() throws DefinitionException {
        Map<String, String> files = new HashMap<>();
        files.put("factory.yaml", SMALL_YAML);
        for (String name : List.of("implement", "fix", "continue", "review-correctness", "review-security",
                "review-architecture", "review-quality", "risk")) {
            files.put("prompts/" + name + ".md", "text of prompts/" + name + ".md");
        }
        return DefinitionLoader.load(path -> Optional.ofNullable(files.get(path)));
    }

    private IssueRecord recordIn(String stateName, IssueStatus status) {
        StepType step = definition.workflow().state(stateName).step().type();
        return IssueRecord.queued(ISSUE).withState(stateName, step).withStatus(status);
    }

    private IssueRecord apply(IssueRecord record, String outcome, Optional<String> reason, List<Finding> findings) {
        return runner.apply(definition, issue, new StepResult.Outcome(record, outcome, reason, findings), worktree);
    }

    @Test
    void goesToTheTargetStateWithItsStatus() {
        IssueRecord next = apply(recordIn("build", IssueStatus.IN_PROGRESS), "passed", Optional.empty(), List.of());

        assertThat(next.state()).isEqualTo("done");
        assertThat(next.step()).isEqualTo(StepType.FINISH);
        assertThat(next.status()).isEqualTo(IssueStatus.DONE);
    }

    @Test
    void keepsTheStatusWhenTheTransitionSaysSo() {
        IssueRecord next = apply(recordIn("implement", IssueStatus.IN_PROGRESS), "changed", Optional.empty(),
                List.of());

        assertThat(next.state()).isEqualTo("build");
        assertThat(next.step()).isEqualTo(StepType.BUILD);
        assertThat(next.status()).isEqualTo(IssueStatus.IN_PROGRESS);
    }

    @Test
    void entersTheLoopAndWritesTheFixPrompt() throws IOException {
        gitHub.addLabelBy(ISSUE, APPROVE_LABEL, "bbossola");

        IssueRecord next = apply(recordIn("build", IssueStatus.IN_REVIEW), "failed", Optional.empty(), FINDINGS);

        assertThat(next.state()).isEqualTo("implement");
        assertThat(next.step()).isEqualTo(StepType.AGENT);
        assertThat(next.status()).isEqualTo(IssueStatus.IN_PROGRESS);
        assertThat(next.fixLoops()).isEqualTo(1);
        assertThat(Files.readString(worktree.resolve(ScratchFolder.NAME).resolve("fix-prompt.md")))
                .startsWith("Your earlier work has findings")
                .contains("- The build failed: a test fails");
        assertThat(gitHub.labelsOf(ISSUE)).doesNotContain(APPROVE_LABEL);
    }

    @Test
    void appliesTheExitOfTheLoopAboveTheLimit() {
        IssueRecord first = apply(recordIn("build", IssueStatus.IN_PROGRESS), "failed", Optional.empty(), FINDINGS);
        IssueRecord inBuild = first.withState("build", StepType.BUILD);

        IssueRecord second = apply(inBuild, "failed", Optional.empty(), FINDINGS);

        assertThat(second.state()).isEqualTo("build");
        assertThat(second.fixLoops()).isEqualTo(2);
        assertThat(second.status()).isEqualTo(IssueStatus.BLOCKED);
        assertThat(second.waitReason()).contains(WaitReason.FIX_LOOP_LIMIT);
        assertThat(second.notes()).isEmpty();
    }

    @Test
    void waitsWithTheReason() {
        IssueRecord next = apply(recordIn("implement", IssueStatus.IN_PROGRESS), "no-change",
                Optional.of("The agent made no change."), List.of());

        assertThat(next.state()).isEqualTo("implement");
        assertThat(next.status()).isEqualTo(IssueStatus.WAITS_FOR_YOU);
        assertThat(next.waitReason()).contains(WaitReason.MERGE_NEEDS_APPROVAL);
        assertThat(next.notes()).isEmpty();
    }

    @Test
    void asksTheOwnerOnAQuestion() {
        IssueRecord next = apply(recordIn("implement", IssueStatus.IN_PROGRESS), "question",
                Optional.of("Which greeting do you want?"), List.of());

        assertThat(next.status()).isEqualTo(IssueStatus.WAITS_FOR_YOU);
        assertThat(next.waitReason()).contains(WaitReason.QUESTION);
        assertThat(gitHub.commentsOf(ISSUE)).singleElement()
                .satisfies(comment -> assertThat(comment.body()).contains("Which greeting do you want?"));
    }

    @Test
    void blocksWithTheReasonOfTheStepAsANote() {
        IssueRecord next = apply(recordIn("implement", IssueStatus.IN_PROGRESS), "failed",
                Optional.of("The agent session failed: no answer"), List.of());

        assertThat(next.state()).isEqualTo("implement");
        assertThat(next.status()).isEqualTo(IssueStatus.BLOCKED);
        assertThat(next.waitReason()).contains(WaitReason.FAILURE);
        assertThat(next.notes()).containsExactly("The agent session failed: no answer");
    }
}
