package com.meterian.factory.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.meterian.factory.definition.DefinitionException;
import com.meterian.factory.definition.DefinitionFiles;
import com.meterian.factory.definition.DefinitionLoader;
import com.meterian.factory.definition.FactoryDefinition;
import com.meterian.factory.definition.StateDefinition;
import com.meterian.factory.definition.Target;
import com.meterian.factory.prompt.Finding;
import com.meterian.factory.prompt.RiskResult;
import com.meterian.factory.state.ActionType;
import com.meterian.factory.state.IssueRecord;
import com.meterian.factory.state.PendingPrompt;
import com.meterian.factory.state.RiskLevel;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MoveRunnerTest {

    /** A small workflow with other names than the default, and with each action type. */
    private static final String WORKFLOW = """
            version: 1
            workflow:
              start: begin
              on-any-state: { block: stuck, enter: talk, issue-text-changed: text }
              states:
                begin: { column: doing, action: { type: prepare }, on: { done: code } }
                code:
                  column: doing
                  action:
                    type: coding
                    prompts: { implement: p/i.md, fix: p/f.md, continue: p/c.md, resume: p/r.md }
                  limit: { entries: 2, then: stuck }
                  on: { changed: check, no-change: stuck, question: talk, conflict-left: code, failed: stuck }
                check: { column: doing, action: { type: build }, on: { passed: judge, failed: code } }
                judge:
                  column: review
                  action: { type: reviews, prompts: { correctness: p/rc.md } }
                  on: { approved: weigh, rejected: code }
                weigh: { column: review, action: { type: risk, prompts: { risk: p/k.md } }, on: { assessed: ship } }
                ship:
                  column: review
                  action: { type: merge-gate, mode: auto-merge }
                  on: { merged: done, needs-approval: sign, needs-new-reviews: code, conflict: code,
                        factory-changed: sign }
                sign: { column: needs-me, action: { type: approval }, on: { approved: ship } }
                text: { column: needs-me, action: { type: approval }, on: { approved: previous } }
                talk: { column: needs-me, action: { type: conversation }, on: { continue: previous, changed: check } }
                stuck: { column: stuck, on: { continue: previous } }
                done: { column: done, action: { type: finish } }
            build: { command: "true" }
            claude: { allowed-tools: [Read] }
            merge: { target-branch: main }
            """;
    private static final List<Finding> FINDINGS = List.of(new Finding("The build failed", "line 3"));

    @TempDir
    Path worktree;

    private final MoveRunner moveRunner = new MoveRunner();
    private final FactoryDefinition definition = loadWorkflow();

    private static FactoryDefinition loadWorkflow() {
        return loadWorkflow(WORKFLOW);
    }

    private static FactoryDefinition loadWorkflow(String yaml) {
        DefinitionFiles files = path -> path.equals("factory.yaml") ? Optional.of(yaml)
                : Optional.of("prompt " + path);
        try {
            return DefinitionLoader.load(files);
        } catch (DefinitionException exception) {
            throw new IllegalStateException(exception);
        }
    }

    /** A record in the state, as the engine leaves it after a move with the previous state. */
    private IssueRecord recordIn(String stateName, String previousState) {
        StateDefinition state = definition.workflow().state(stateName);
        return IssueRecord.queued(7, "begin").withMove(stateName, state.actionType(), state.column())
                .withPreviousState(previousState);
    }

    private IssueRecord move(IssueRecord record, String event) {
        return moveRunner.move(definition, new ActionResult.Event(record, event, Optional.empty(), List.of()),
                worktree);
    }

    private IssueRecord moveWithFindings(IssueRecord record, String event) {
        return moveRunner.move(definition, new ActionResult.Event(record, event, Optional.empty(), FINDINGS),
                worktree);
    }

    private Optional<List<Finding>> readPendingFindings() {
        return new FactoryFolder(worktree).readPendingFindings();
    }

    @Test
    void movesToTheNamedStateAndSetsItsColumn() {
        IssueRecord moved = move(recordIn("check", "check").withActionStarted(), "passed");

        assertThat(moved.state()).isEqualTo("judge");
        assertThat(moved.action()).contains(ActionType.REVIEWS);
        assertThat(moved.column()).isEqualTo("review");
        assertThat(moved.actionStarted()).isFalse();
    }

    @Test
    void writesTheReasonOfTheEventAsANote() {
        IssueRecord record = recordIn("code", "code").withNote("The reason.").withNote("An older note.");

        IssueRecord moved = moveRunner.move(definition,
                new ActionResult.Event(record, "no-change", Optional.of("The reason."), List.of()), worktree);

        assertThat(moved.state()).isEqualTo("stuck");
        assertThat(moved.notes()).containsExactly("An older note.", "The reason.");
        assertThat(moved.newestNote()).contains("The reason.");
    }

    @Test
    void refusesAnEventWithoutANextState() {
        assertThatThrownBy(() -> move(recordIn("check", "check"), "merged"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("The state check has no next state for the event merged.");
    }

    @Test
    void setsPreviousOnlyForAStateThatDoesNotWait() {
        IssueRecord inShip = move(recordIn("weigh", "weigh"), "assessed");
        IssueRecord inSign = move(inShip, "needs-approval");
        IssueRecord inStuck = moveRunner.moveTo(definition, recordIn("check", "check"), new Target.ToState("stuck"),
                worktree);
        IssueRecord inTalk = move(recordIn("code", "code"), "question");

        assertThat(inShip.previousState()).contains("ship");
        assertThat(inSign.previousState()).contains("ship");
        assertThat(inStuck.previousState()).contains("check");
        assertThat(inTalk.previousState()).contains("code");
    }

    @Test
    void resolvesPreviousToTheLastStateWithAnAction() {
        IssueRecord inText = recordIn("text", "judge");
        IssueRecord inStuck = recordIn("stuck", "ship");

        assertThat(move(inText, "approved").state()).isEqualTo("judge");
        assertThat(move(inStuck, "continue").state()).isEqualTo("ship");
    }

    @Test
    void resetsTheEntriesOnContinueFromTheBlockTarget() {
        IssueRecord blocked = recordIn("stuck", "check").withEntryCounted("code").withEntryCounted("code");

        IssueRecord continued = move(blocked, "continue");

        assertThat(continued.state()).isEqualTo("check");
        assertThat(continued.entries()).isEmpty();
    }

    @Test
    void keepsTheEntriesOnContinueFromTheEnterTarget() {
        IssueRecord live = recordIn("talk", "check").withEntryCounted("code");

        IssueRecord continued = move(live, "continue");

        assertThat(continued.state()).isEqualTo("check");
        assertThat(continued.entriesOf("code")).isEqualTo(1);
    }

    @Test
    void keepsTheEntriesOnAnotherEvent() {
        IssueRecord inCheck = recordIn("check", "check").withEntryCounted("code");

        assertThat(move(inCheck, "failed").entriesOf("code")).isEqualTo(1);
    }

    @Test
    void setsFixForTheEventsWithFindings() {
        Map<String, String> eventsWithFindings = Map.of("check", "failed", "judge", "rejected", "ship", "conflict",
                "code", "conflict-left");

        eventsWithFindings.forEach((stateName, event) -> {
            List<Finding> findings = List.of(new Finding("The event " + event, "of the state " + stateName));
            IssueRecord moved = moveRunner.move(definition,
                    new ActionResult.Event(recordIn(stateName, stateName), event, Optional.empty(), findings),
                    worktree);

            assertThat(moved.state()).isEqualTo("code");
            assertThat(moved.pendingPrompt()).contains(PendingPrompt.FIX);
            assertThat(readPendingFindings()).contains(findings);
        });
    }

    @Test
    void setsImplementAfterPrepare() {
        IssueRecord moved = move(recordIn("begin", "begin"), "done");

        assertThat(moved.state()).isEqualTo("code");
        assertThat(moved.pendingPrompt()).contains(PendingPrompt.IMPLEMENT);
        assertThat(readPendingFindings()).isEmpty();
    }

    @Test
    void setsResumeForAnotherEvent() {
        IssueRecord moved = moveWithFindings(recordIn("ship", "ship"), "needs-new-reviews");

        assertThat(moved.state()).isEqualTo("code");
        assertThat(moved.pendingPrompt()).contains(PendingPrompt.RESUME);
        assertThat(readPendingFindings()).contains(FINDINGS);
    }

    @Test
    void givesTheRiskAsTheFindingsOfResume() {
        FactoryDefinition riskToCode = loadWorkflow(WORKFLOW.replace("assessed: ship", "assessed: code")
                .replace("continue: previous, changed: check", "continue: previous, changed: ship"));
        RiskResult risk = new RiskResult(RiskLevel.HIGH, false, List.of("It changes the login.", "No test."));
        IssueRecord weighing = IssueRecord.queued(7, "begin").withMove("weigh", Optional.of(ActionType.RISK), "review")
                .withPreviousState("weigh");
        Finding finding = RiskAction.describeRisk(risk);

        IssueRecord moved = moveRunner.move(riskToCode, new ActionResult.Event(weighing, "assessed",
                Optional.empty(), List.of(finding)), worktree);

        assertThat(moved.pendingPrompt()).contains(PendingPrompt.RESUME);
        assertThat(readPendingFindings()).contains(List.of(new Finding("The risk: high, not small",
                "It changes the login.\nNo test.")));
    }

    @Test
    void setsResumeWithoutFindingsForALimitThatGoesToTheCodingState() {
        IssueRecord moved = moveRunner.moveTo(definition, recordIn("check", "check"), new Target.ToState("code"),
                worktree);

        assertThat(moved.pendingPrompt()).contains(PendingPrompt.RESUME);
        assertThat(readPendingFindings()).contains(List.of());
    }

    @Test
    void keepsThePendingPromptOnContinueFromTheBlockTarget() {
        IssueRecord blocked = recordIn("stuck", "code").withPendingPrompt(PendingPrompt.FIX);

        IssueRecord continued = move(blocked, "continue");

        assertThat(continued.state()).isEqualTo("code");
        assertThat(continued.pendingPrompt()).contains(PendingPrompt.FIX);
        assertThat(continued.afterConversation()).isFalse();
        assertThat(readPendingFindings()).isEmpty();
    }

    @Test
    void keepsThePendingPromptAfterTheTextApproval() {
        IssueRecord textChanged = recordIn("text", "code").withPendingPrompt(PendingPrompt.FIX);

        IssueRecord approved = move(textChanged, "approved");

        assertThat(approved.state()).isEqualTo("code");
        assertThat(approved.pendingPrompt()).contains(PendingPrompt.FIX);
        assertThat(readPendingFindings()).isEmpty();
    }

    @Test
    void setsTheReturnFromTheConversationOnlyOnContinueToTheCodingState() {
        IssueRecord live = recordIn("talk", "code").withPendingPrompt(PendingPrompt.FIX);

        IssueRecord continued = move(live, "continue");
        IssueRecord changed = move(live.withAfterConversation(), "changed");
        IssueRecord continuedToCheck = move(recordIn("talk", "check"), "continue");

        assertThat(continued.state()).isEqualTo("code");
        assertThat(continued.afterConversation()).isTrue();
        assertThat(continued.pendingPrompt()).contains(PendingPrompt.FIX);
        assertThat(changed.state()).isEqualTo("check");
        assertThat(changed.afterConversation()).isFalse();
        assertThat(continuedToCheck.afterConversation()).isFalse();
    }
}
