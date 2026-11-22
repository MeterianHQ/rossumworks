package com.meterian.factory.workflow;

import com.meterian.factory.agent.AgentException;
import com.meterian.factory.agent.ApiKeyDetectedException;
import com.meterian.factory.agent.SessionReport;
import com.meterian.factory.github.Issue;
import com.meterian.factory.prompt.Finding;
import com.meterian.factory.prompt.PromptLibrary;
import com.meterian.factory.prompt.ReviewResult;
import com.meterian.factory.prompt.ReviewResultReader;
import com.meterian.factory.state.IssueRecord;
import com.meterian.factory.state.Verdict;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Runs the reviews of the definition on one tree, each in a new session. A result for another tree is a reject. */
final class ReviewRound {

    /**
     * The record with the verdicts, the findings of the rejecting reviews, and the notes of all reviews, or a
     * session that failed or that the worker stopped.
     *
     * @param findingCounts the count of the findings of each review, by review name, in the order of the reviews
     */
    record Outcome(IssueRecord record, List<Finding> findings, List<ReviewNote> notes, List<String> reviewNames,
                   Map<String, Integer> findingCounts, boolean sessionFailed, boolean sessionStopped) {

        static Outcome failedSession(IssueRecord record) {
            return new Outcome(record, List.of(), List.of(), List.of(), Map.of(), true, false);
        }

        static Outcome stoppedSession(IssueRecord record) {
            return new Outcome(record, List.of(), List.of(), List.of(), Map.of(), false, true);
        }

        boolean allApprove() {
            return reviewNames.stream().allMatch(reviewName -> record.reviews().get(reviewName) == Verdict.APPROVE);
        }
    }

    private final PhaseRunner phaseRunner;
    private final PromptLibrary prompts;
    private final List<String> allowedTools;

    ReviewRound(PhaseRunner phaseRunner, PromptLibrary prompts, List<String> allowedTools) {
        this.phaseRunner = phaseRunner;
        this.prompts = prompts;
        this.allowedTools = List.copyOf(allowedTools);
    }

    Outcome review(Issue issue, IssueRecord record, Path worktree, String treeHash)
            throws AgentException, ApiKeyDetectedException {
        IssueRecord reviewed = record.withoutReviews();
        List<Finding> findings = new ArrayList<>();
        List<ReviewNote> notes = new ArrayList<>();
        Map<String, Integer> findingCounts = new LinkedHashMap<>();
        List<String> reviewNames = prompts.reviewNames();
        for (String reviewName : reviewNames) {
            SessionReport report = runReview(issue, reviewName, worktree, treeHash);
            if (report.stopped()) {
                return Outcome.stoppedSession(record);
            }
            if (!report.success()) {
                return Outcome.failedSession(record);
            }
            ReviewResult result = ReviewResultReader.read(worktree, reviewName);
            Verdict verdict = judge(result, treeHash);
            reviewed = reviewed.withReview(reviewName, verdict);
            if (isForThisTree(result, treeHash)) {
                result.notes().forEach(note -> notes.add(new ReviewNote(reviewName, note)));
            }
            List<Finding> rejection = verdict == Verdict.REJECT ? describeRejection(reviewName, result, treeHash)
                    : List.of();
            findings.addAll(rejection);
            findingCounts.put(reviewName, rejection.size());
        }
        return new Outcome(reviewed, findings, notes, reviewNames, findingCounts, false, false);
    }

    private SessionReport runReview(Issue issue, String reviewName, Path worktree, String treeHash)
            throws AgentException, ApiKeyDetectedException {
        new FactoryFolder(worktree).deleteReviewResult(reviewName);
        String prompt = prompts.review(reviewName, issue, treeHash);
        return phaseRunner.runInNewSession(issue.number(), worktree, prompt, allowedTools);
    }

    private static Verdict judge(ReviewResult result, String treeHash) {
        return result.verdict() == Verdict.APPROVE && isForThisTree(result, treeHash) ? Verdict.APPROVE
                : Verdict.REJECT;
    }

    /** The notes of a result for another tree are not about this change, so they are dropped. */
    private static boolean isForThisTree(ReviewResult result, String treeHash) {
        return !result.treeHash().isEmpty() && result.treeHash().equals(treeHash);
    }

    private static List<Finding> describeRejection(String reviewName, ReviewResult result, String treeHash) {
        String prefix = "The review " + reviewName + ": ";
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
