package com.meterian.factory.workflow;

import com.meterian.factory.agent.AgentException;
import com.meterian.factory.agent.ApiKeyDetectedException;
import com.meterian.factory.github.Issue;
import com.meterian.factory.prompt.Finding;
import com.meterian.factory.prompt.PromptLibrary;
import com.meterian.factory.prompt.ReviewResult;
import com.meterian.factory.prompt.ReviewResultReader;
import com.meterian.factory.state.IssueRecord;
import com.meterian.factory.state.ReviewKind;
import com.meterian.factory.state.Verdict;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Runs the four reviews of one tree, each in a new session. A result for another tree is a reject. */
final class ReviewRound {

    /** The record with the four verdicts, and the findings of the rejecting reviews. */
    record Outcome(IssueRecord record, List<Finding> findings) {

        boolean allApprove() {
            return record.reviews().values().stream().allMatch(Verdict.APPROVE::equals)
                    && record.reviews().size() == ReviewKind.values().length;
        }
    }

    private final PhaseRunner phaseRunner;
    private final PromptLibrary prompts;

    ReviewRound(PhaseRunner phaseRunner, PromptLibrary prompts) {
        this.phaseRunner = phaseRunner;
        this.prompts = prompts;
    }

    Outcome review(Issue issue, IssueRecord record, Path worktree, String treeHash)
            throws AgentException, ApiKeyDetectedException {
        IssueRecord reviewed = record.withoutReviews();
        List<Finding> findings = new ArrayList<>();
        for (ReviewKind kind : ReviewKind.values()) {
            ReviewResult result = runReview(issue, kind, worktree, treeHash);
            Verdict verdict = judge(result, treeHash);
            reviewed = reviewed.withReview(kind, verdict);
            if (verdict == Verdict.REJECT) {
                findings.addAll(describeRejection(kind, result, treeHash));
            }
        }
        return new Outcome(reviewed, findings);
    }

    private ReviewResult runReview(Issue issue, ReviewKind kind, Path worktree, String treeHash)
            throws AgentException, ApiKeyDetectedException {
        new FactoryFolder(worktree).deleteReviewResult(kind);
        phaseRunner.runInNewSession(issue.number(), worktree, prompts.review(kind, issue, treeHash));
        return ReviewResultReader.read(worktree, kind);
    }

    private static Verdict judge(ReviewResult result, String treeHash) {
        boolean isForThisTree = !result.treeHash().isEmpty() && result.treeHash().equals(treeHash);
        return result.verdict() == Verdict.APPROVE && isForThisTree ? Verdict.APPROVE : Verdict.REJECT;
    }

    private static List<Finding> describeRejection(ReviewKind kind, ReviewResult result, String treeHash) {
        String prefix = "The review " + kind.name().toLowerCase(Locale.ROOT) + ": ";
        List<Finding> findings = new ArrayList<>();
        result.findings().forEach(finding -> findings.add(new Finding(prefix + finding.title(), finding.detail())));
        if (!result.treeHash().equals(treeHash)) {
            findings.add(new Finding(prefix + "the result is for another tree",
                    "The review must check the tree " + treeHash + "."));
        }
        if (findings.isEmpty()) {
            findings.add(new Finding(prefix + "a reject with no finding", "The review gave no reason."));
        }
        return findings;
    }
}
