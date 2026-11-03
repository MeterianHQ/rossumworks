package com.meterian.factory.workflow;

import com.meterian.factory.definition.FactoryDefinition;
import com.meterian.factory.definition.LoopDefinition;
import com.meterian.factory.definition.StateDefinition;
import com.meterian.factory.definition.Transition;
import com.meterian.factory.definition.WorkflowDefinition;
import com.meterian.factory.github.GitHubClient;
import com.meterian.factory.github.Issue;
import com.meterian.factory.state.IssueRecord;
import com.meterian.factory.state.IssueStatus;
import com.meterian.factory.state.WaitReason;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Supplier;

/** Finds the transition of a step outcome in the workflow definition, and moves the issue by it. */
final class TransitionRunner {

    private final FixLoop fixLoop;
    private final GitHubClient gitHub;
    private final Supplier<List<String>> allowedAccounts;
    private final LiveMode liveMode;

    /** @param allowedAccounts the accounts of the newest definition version: the question mentions the first */
    TransitionRunner(FixLoop fixLoop, GitHubClient gitHub, Supplier<List<String>> allowedAccounts,
                     LiveMode liveMode) {
        this.fixLoop = fixLoop;
        this.gitHub = gitHub;
        this.allowedAccounts = allowedAccounts;
        this.liveMode = liveMode;
    }

    IssueRecord apply(FactoryDefinition definition, Issue issue, StepResult.Outcome result, Path worktree) {
        StateDefinition state = definition.workflow().state(result.record().state());
        Transition transition = state.transitions().get(result.outcome());
        if (transition == null) {
            throw new IllegalStateException("The state " + state.name() + " has no transition for the outcome "
                    + result.outcome() + ".");
        }
        return applyTransition(definition, issue, result, transition, worktree);
    }

    private IssueRecord applyTransition(FactoryDefinition definition, Issue issue, StepResult.Outcome result,
                                        Transition transition, Path worktree) {
        return switch (transition) {
            case Transition.GoTo goTo -> goTo(definition.workflow(), result.record(), goTo);
            case Transition.EnterLoop enterLoop -> enterLoop(definition, issue, result, enterLoop.loop(), worktree);
            case Transition.Wait wait -> waitForTheOwner(issue, result, wait.reason(), worktree);
            case Transition.Block block -> block(result, block.reason());
        };
    }

    private static IssueRecord goTo(WorkflowDefinition workflow, IssueRecord record, Transition.GoTo goTo) {
        StateDefinition target = workflow.state(goTo.state());
        IssueRecord moved = record.withState(target.name(), target.step().type());
        return goTo.keepStatus() ? moved : moved.withStatus(target.status());
    }

    /** Above the limit, the exit of the loop applies to the counted record. */
    private IssueRecord enterLoop(FactoryDefinition definition, Issue issue, StepResult.Outcome result,
                                  String loopName, Path worktree) {
        LoopDefinition loop = definition.workflow().loop(loopName).orElseThrow(
                () -> new IllegalStateException("The workflow has no loop " + loopName + "."));
        IssueRecord counted = fixLoop.countEntry(result.record());
        if (fixLoop.isAboveLimit(counted, loop)) {
            return applyLoopExit(definition, issue, result, counted, loop, worktree);
        }
        fixLoop.sendFindingsBack(counted.issueNumber(), worktree, result.findings());
        return goTo(definition.workflow(), counted, new Transition.GoTo(loop.to(), false));
    }

    private IssueRecord applyLoopExit(FactoryDefinition definition, Issue issue, StepResult.Outcome result,
                                      IssueRecord counted, LoopDefinition loop, Path worktree) {
        if (loop.exit() instanceof Transition.EnterLoop) {
            throw new IllegalStateException("The exit of the loop " + loop.name() + " cannot enter a loop.");
        }
        StepResult.Outcome countedResult =
                new StepResult.Outcome(counted, result.outcome(), result.reason(), result.findings());
        return applyTransition(definition, issue, countedResult, loop.exit(), worktree);
    }

    private IssueRecord waitForTheOwner(Issue issue, StepResult.Outcome result, WaitReason reason, Path worktree) {
        IssueRecord waiting = result.record().withStatus(IssueStatus.WAITS_FOR_YOU).withWaitReason(reason);
        if (reason != WaitReason.QUESTION) {
            return waiting;
        }
        String question = result.reason().orElseThrow(
                () -> new IllegalStateException("The outcome " + result.outcome() + " has no question text."));
        return askOwner(issue, waiting, question, worktree);
    }

    /** The owner answers in the live conversation. When it does not open, the question waits with a note. */
    private IssueRecord askOwner(Issue issue, IssueRecord waiting, String question, Path worktree) {
        gitHub.createComment(issue.number(), QuestionComment.render(question, allowedAccounts.get()));
        return liveMode.enter(waiting, worktree);
    }

    /** The reason of the step is also a note. The fix-loop limit has no reason, so it has no note. */
    private static IssueRecord block(StepResult.Outcome result, WaitReason reason) {
        IssueRecord blocked = result.record().withStatus(IssueStatus.BLOCKED).withWaitReason(reason);
        return result.reason().map(blocked::withNote).orElse(blocked);
    }
}
