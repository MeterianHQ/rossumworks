package com.meterian.factory.state;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public record IssueRecord(
        int issueNumber,
        UUID sessionId,
        IssueStatus status,
        Phase phase,
        Map<ReviewKind, Verdict> reviews,
        int fixLoops,
        Optional<RiskLevel> risk,
        boolean small,
        Optional<String> reviewedTreeHash,
        Optional<String> reviewedPatchId,
        Optional<String> builtTreeHash,
        Optional<WaitReason> waitReason,
        Optional<Integer> pullRequestNumber,
        Optional<Long> statusCommentId,
        List<String> notes,
        boolean agentSessionStarted,
        int failedReviewRounds,
        List<String> conflictedPaths,
        Optional<String> issueTextHash,
        Optional<String> reviewedHeadCommit,
        int failedForwardChecks,
        Optional<String> failedForwardTree) {

    /** A record from before a new field reads the field as empty. */
    public IssueRecord {
        reviews = Map.copyOf(reviews);
        notes = List.copyOf(notes);
        conflictedPaths = conflictedPaths == null ? List.of() : List.copyOf(conflictedPaths);
        issueTextHash = issueTextHash == null ? Optional.empty() : issueTextHash;
        reviewedHeadCommit = reviewedHeadCommit == null ? Optional.empty() : reviewedHeadCommit;
        failedForwardTree = failedForwardTree == null ? Optional.empty() : failedForwardTree;
    }

    public static IssueRecord queued(int issueNumber) {
        return new IssueRecord(issueNumber, UUID.randomUUID(), IssueStatus.QUEUED, Phase.PREPARE,
                Map.of(), 0, Optional.empty(), false, Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), List.of(), false, 0, List.of(),
                Optional.empty(), Optional.empty(), 0, Optional.empty());
    }

    /** A new session has not started yet, so the next agent run starts it. */
    public IssueRecord withSessionId(UUID newSessionId) {
        return new IssueRecord(issueNumber, newSessionId, status, phase, reviews, fixLoops, risk, small,
                reviewedTreeHash, reviewedPatchId, builtTreeHash, waitReason, pullRequestNumber,
                statusCommentId, notes, false, failedReviewRounds, conflictedPaths,
                issueTextHash, reviewedHeadCommit, failedForwardChecks, failedForwardTree);
    }

    public IssueRecord withFailedReviewRound() {
        return new IssueRecord(issueNumber, sessionId, status, phase, reviews, fixLoops, risk, small,
                reviewedTreeHash, reviewedPatchId, builtTreeHash, waitReason, pullRequestNumber,
                statusCommentId, notes, agentSessionStarted, failedReviewRounds + 1, conflictedPaths,
                issueTextHash, reviewedHeadCommit, failedForwardChecks, failedForwardTree);
    }

    public IssueRecord withoutFailedReviewRounds() {
        return new IssueRecord(issueNumber, sessionId, status, phase, reviews, fixLoops, risk, small,
                reviewedTreeHash, reviewedPatchId, builtTreeHash, waitReason, pullRequestNumber,
                statusCommentId, notes, agentSessionStarted, 0, conflictedPaths,
                issueTextHash, reviewedHeadCommit, failedForwardChecks, failedForwardTree);
    }

    /** Keeps the paths with a conflict while the merge is open. The check of the markers reads them. */
    public IssueRecord withConflictedPaths(List<String> newConflictedPaths) {
        return new IssueRecord(issueNumber, sessionId, status, phase, reviews, fixLoops, risk, small,
                reviewedTreeHash, reviewedPatchId, builtTreeHash, waitReason, pullRequestNumber,
                statusCommentId, notes, agentSessionStarted, failedReviewRounds, newConflictedPaths,
                issueTextHash, reviewedHeadCommit, failedForwardChecks, failedForwardTree);
    }

    public IssueRecord withoutConflictedPaths() {
        return withConflictedPaths(List.of());
    }

    public IssueRecord withAgentSessionStarted() {
        return new IssueRecord(issueNumber, sessionId, status, phase, reviews, fixLoops, risk, small,
                reviewedTreeHash, reviewedPatchId, builtTreeHash, waitReason, pullRequestNumber,
                statusCommentId, notes, true, failedReviewRounds, conflictedPaths,
                issueTextHash, reviewedHeadCommit, failedForwardChecks, failedForwardTree);
    }

    public IssueRecord withStatus(IssueStatus newStatus) {
        return new IssueRecord(issueNumber, sessionId, newStatus, phase, reviews, fixLoops, risk, small,
                reviewedTreeHash, reviewedPatchId, builtTreeHash, waitReason, pullRequestNumber,
                statusCommentId, notes, agentSessionStarted, failedReviewRounds, conflictedPaths,
                issueTextHash, reviewedHeadCommit, failedForwardChecks, failedForwardTree);
    }

    public IssueRecord withPhase(Phase newPhase) {
        return new IssueRecord(issueNumber, sessionId, status, newPhase, reviews, fixLoops, risk, small,
                reviewedTreeHash, reviewedPatchId, builtTreeHash, waitReason, pullRequestNumber,
                statusCommentId, notes, agentSessionStarted, failedReviewRounds, conflictedPaths,
                issueTextHash, reviewedHeadCommit, failedForwardChecks, failedForwardTree);
    }

    public IssueRecord withReview(ReviewKind kind, Verdict verdict) {
        Map<ReviewKind, Verdict> newReviews = new LinkedHashMap<>(reviews);
        newReviews.put(kind, verdict);
        return new IssueRecord(issueNumber, sessionId, status, phase, newReviews, fixLoops, risk, small,
                reviewedTreeHash, reviewedPatchId, builtTreeHash, waitReason, pullRequestNumber,
                statusCommentId, notes, agentSessionStarted, failedReviewRounds, conflictedPaths,
                issueTextHash, reviewedHeadCommit, failedForwardChecks, failedForwardTree);
    }

    public IssueRecord withoutReviews() {
        return new IssueRecord(issueNumber, sessionId, status, phase, Map.of(), fixLoops, risk, small,
                Optional.empty(), Optional.empty(), builtTreeHash, waitReason, pullRequestNumber,
                statusCommentId, notes, agentSessionStarted, failedReviewRounds, conflictedPaths,
                issueTextHash, Optional.empty(), failedForwardChecks, failedForwardTree);
    }

    public IssueRecord withFixLoop() {
        return new IssueRecord(issueNumber, sessionId, status, phase, reviews, fixLoops + 1, risk, small,
                reviewedTreeHash, reviewedPatchId, builtTreeHash, waitReason, pullRequestNumber,
                statusCommentId, notes, agentSessionStarted, failedReviewRounds, conflictedPaths,
                issueTextHash, reviewedHeadCommit, failedForwardChecks, failedForwardTree);
    }

    public IssueRecord withRisk(RiskLevel newRisk, boolean newSmall) {
        return new IssueRecord(issueNumber, sessionId, status, phase, reviews, fixLoops,
                Optional.of(newRisk), newSmall, reviewedTreeHash, reviewedPatchId, builtTreeHash,
                waitReason, pullRequestNumber, statusCommentId, notes, agentSessionStarted, failedReviewRounds,
                conflictedPaths,
                issueTextHash, reviewedHeadCommit, failedForwardChecks, failedForwardTree);
    }

    public IssueRecord withoutRisk() {
        return new IssueRecord(issueNumber, sessionId, status, phase, reviews, fixLoops, Optional.empty(), false,
                reviewedTreeHash, reviewedPatchId, builtTreeHash, waitReason, pullRequestNumber,
                statusCommentId, notes, agentSessionStarted, failedReviewRounds, conflictedPaths,
                issueTextHash, reviewedHeadCommit, failedForwardChecks, failedForwardTree);
    }

    public IssueRecord withoutBuiltTree() {
        return new IssueRecord(issueNumber, sessionId, status, phase, reviews, fixLoops, risk, small,
                reviewedTreeHash, reviewedPatchId, Optional.empty(), waitReason, pullRequestNumber,
                statusCommentId, notes, agentSessionStarted, failedReviewRounds, conflictedPaths,
                issueTextHash, reviewedHeadCommit, failedForwardChecks, failedForwardTree);
    }

    public IssueRecord withReviewedTree(String treeHash, String patchId) {
        return new IssueRecord(issueNumber, sessionId, status, phase, reviews, fixLoops, risk, small,
                Optional.of(treeHash), Optional.of(patchId), builtTreeHash, waitReason, pullRequestNumber,
                statusCommentId, notes, agentSessionStarted, failedReviewRounds, conflictedPaths,
                issueTextHash, reviewedHeadCommit, failedForwardChecks, failedForwardTree);
    }

    public IssueRecord withBuiltTree(String treeHash) {
        return new IssueRecord(issueNumber, sessionId, status, phase, reviews, fixLoops, risk, small,
                reviewedTreeHash, reviewedPatchId, Optional.of(treeHash), waitReason, pullRequestNumber,
                statusCommentId, notes, agentSessionStarted, failedReviewRounds, conflictedPaths,
                issueTextHash, reviewedHeadCommit, failedForwardChecks, failedForwardTree);
    }

    public IssueRecord withWaitReason(WaitReason newWaitReason) {
        return new IssueRecord(issueNumber, sessionId, status, phase, reviews, fixLoops, risk, small,
                reviewedTreeHash, reviewedPatchId, builtTreeHash, Optional.of(newWaitReason),
                pullRequestNumber, statusCommentId, notes, agentSessionStarted, failedReviewRounds, conflictedPaths,
                issueTextHash, reviewedHeadCommit,
                failedForwardChecks, failedForwardTree);
    }

    public IssueRecord withoutWaitReason() {
        return new IssueRecord(issueNumber, sessionId, status, phase, reviews, fixLoops, risk, small,
                reviewedTreeHash, reviewedPatchId, builtTreeHash, Optional.empty(),
                pullRequestNumber, statusCommentId, notes, agentSessionStarted, failedReviewRounds, conflictedPaths,
                issueTextHash, reviewedHeadCommit,
                failedForwardChecks, failedForwardTree);
    }

    public IssueRecord withPullRequest(int number) {
        return new IssueRecord(issueNumber, sessionId, status, phase, reviews, fixLoops, risk, small,
                reviewedTreeHash, reviewedPatchId, builtTreeHash, waitReason, Optional.of(number),
                statusCommentId, notes, agentSessionStarted, failedReviewRounds, conflictedPaths,
                issueTextHash, reviewedHeadCommit, failedForwardChecks, failedForwardTree);
    }

    public IssueRecord withStatusComment(long commentId) {
        return new IssueRecord(issueNumber, sessionId, status, phase, reviews, fixLoops, risk, small,
                reviewedTreeHash, reviewedPatchId, builtTreeHash, waitReason, pullRequestNumber,
                Optional.of(commentId), notes, agentSessionStarted, failedReviewRounds, conflictedPaths,
                issueTextHash, reviewedHeadCommit, failedForwardChecks, failedForwardTree);
    }

    /** The hash of the issue text that the owner accepted: at the intake, or with the label factory:approve. */
    public IssueRecord withIssueTextHash(String newIssueTextHash) {
        return new IssueRecord(issueNumber, sessionId, status, phase, reviews, fixLoops, risk, small,
                reviewedTreeHash, reviewedPatchId, builtTreeHash, waitReason, pullRequestNumber,
                statusCommentId, notes, agentSessionStarted, failedReviewRounds, conflictedPaths,
                Optional.of(newIssueTextHash), reviewedHeadCommit, failedForwardChecks, failedForwardTree);
    }

    /** The commit that the four reviews approved. A merge step that does not end goes back to it. */
    public IssueRecord withReviewedHeadCommit(String commit) {
        return new IssueRecord(issueNumber, sessionId, status, phase, reviews, fixLoops, risk, small,
                reviewedTreeHash, reviewedPatchId, builtTreeHash, waitReason, pullRequestNumber,
                statusCommentId, notes, agentSessionStarted, failedReviewRounds, conflictedPaths, issueTextHash,
                Optional.of(commit), failedForwardChecks, failedForwardTree);
    }

    /**
     * Counts a check that did not find the pushed commit on GitHub. The key is the tree, because each rebase
     * makes a new commit of the same change. A new tree starts the count again.
     */
    public IssueRecord withFailedForwardCheck(String pushedTree) {
        boolean isSameTree = failedForwardTree.equals(Optional.of(pushedTree));
        return new IssueRecord(issueNumber, sessionId, status, phase, reviews, fixLoops, risk, small,
                reviewedTreeHash, reviewedPatchId, builtTreeHash, waitReason, pullRequestNumber,
                statusCommentId, notes, agentSessionStarted, failedReviewRounds, conflictedPaths, issueTextHash,
                reviewedHeadCommit, isSameTree ? failedForwardChecks + 1 : 1, Optional.of(pushedTree));
    }

    public IssueRecord withoutFailedForwardChecks() {
        return new IssueRecord(issueNumber, sessionId, status, phase, reviews, fixLoops, risk, small,
                reviewedTreeHash, reviewedPatchId, builtTreeHash, waitReason, pullRequestNumber,
                statusCommentId, notes, agentSessionStarted, failedReviewRounds, conflictedPaths, issueTextHash,
                reviewedHeadCommit, 0, Optional.empty());
    }

    public IssueRecord withNote(String note) {
        if (notes.contains(note)) {
            return this;
        }
        List<String> newNotes = new ArrayList<>(notes);
        newNotes.add(note);
        return new IssueRecord(issueNumber, sessionId, status, phase, reviews, fixLoops, risk, small,
                reviewedTreeHash, reviewedPatchId, builtTreeHash, waitReason, pullRequestNumber,
                statusCommentId, newNotes, agentSessionStarted, failedReviewRounds, conflictedPaths,
                issueTextHash, reviewedHeadCommit, failedForwardChecks, failedForwardTree);
    }
}
