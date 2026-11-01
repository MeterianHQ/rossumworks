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
        List<String> notes) {

    public IssueRecord {
        reviews = Map.copyOf(reviews);
        notes = List.copyOf(notes);
    }

    public static IssueRecord queued(int issueNumber) {
        return new IssueRecord(issueNumber, UUID.randomUUID(), IssueStatus.QUEUED, Phase.PREPARE,
                Map.of(), 0, Optional.empty(), false, Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), List.of());
    }

    public IssueRecord withSessionId(UUID newSessionId) {
        return new IssueRecord(issueNumber, newSessionId, status, phase, reviews, fixLoops, risk, small,
                reviewedTreeHash, reviewedPatchId, builtTreeHash, waitReason, pullRequestNumber,
                statusCommentId, notes);
    }

    public IssueRecord withStatus(IssueStatus newStatus) {
        return new IssueRecord(issueNumber, sessionId, newStatus, phase, reviews, fixLoops, risk, small,
                reviewedTreeHash, reviewedPatchId, builtTreeHash, waitReason, pullRequestNumber,
                statusCommentId, notes);
    }

    public IssueRecord withPhase(Phase newPhase) {
        return new IssueRecord(issueNumber, sessionId, status, newPhase, reviews, fixLoops, risk, small,
                reviewedTreeHash, reviewedPatchId, builtTreeHash, waitReason, pullRequestNumber,
                statusCommentId, notes);
    }

    public IssueRecord withReview(ReviewKind kind, Verdict verdict) {
        Map<ReviewKind, Verdict> newReviews = new LinkedHashMap<>(reviews);
        newReviews.put(kind, verdict);
        return new IssueRecord(issueNumber, sessionId, status, phase, newReviews, fixLoops, risk, small,
                reviewedTreeHash, reviewedPatchId, builtTreeHash, waitReason, pullRequestNumber,
                statusCommentId, notes);
    }

    public IssueRecord withoutReviews() {
        return new IssueRecord(issueNumber, sessionId, status, phase, Map.of(), fixLoops, risk, small,
                Optional.empty(), Optional.empty(), builtTreeHash, waitReason, pullRequestNumber,
                statusCommentId, notes);
    }

    public IssueRecord withFixLoop() {
        return new IssueRecord(issueNumber, sessionId, status, phase, reviews, fixLoops + 1, risk, small,
                reviewedTreeHash, reviewedPatchId, builtTreeHash, waitReason, pullRequestNumber,
                statusCommentId, notes);
    }

    public IssueRecord withRisk(RiskLevel newRisk, boolean newSmall) {
        return new IssueRecord(issueNumber, sessionId, status, phase, reviews, fixLoops,
                Optional.of(newRisk), newSmall, reviewedTreeHash, reviewedPatchId, builtTreeHash,
                waitReason, pullRequestNumber, statusCommentId, notes);
    }

    public IssueRecord withReviewedTree(String treeHash, String patchId) {
        return new IssueRecord(issueNumber, sessionId, status, phase, reviews, fixLoops, risk, small,
                Optional.of(treeHash), Optional.of(patchId), builtTreeHash, waitReason, pullRequestNumber,
                statusCommentId, notes);
    }

    public IssueRecord withBuiltTree(String treeHash) {
        return new IssueRecord(issueNumber, sessionId, status, phase, reviews, fixLoops, risk, small,
                reviewedTreeHash, reviewedPatchId, Optional.of(treeHash), waitReason, pullRequestNumber,
                statusCommentId, notes);
    }

    public IssueRecord withWaitReason(WaitReason newWaitReason) {
        return new IssueRecord(issueNumber, sessionId, status, phase, reviews, fixLoops, risk, small,
                reviewedTreeHash, reviewedPatchId, builtTreeHash, Optional.of(newWaitReason),
                pullRequestNumber, statusCommentId, notes);
    }

    public IssueRecord withoutWaitReason() {
        return new IssueRecord(issueNumber, sessionId, status, phase, reviews, fixLoops, risk, small,
                reviewedTreeHash, reviewedPatchId, builtTreeHash, Optional.empty(),
                pullRequestNumber, statusCommentId, notes);
    }

    public IssueRecord withPullRequest(int number) {
        return new IssueRecord(issueNumber, sessionId, status, phase, reviews, fixLoops, risk, small,
                reviewedTreeHash, reviewedPatchId, builtTreeHash, waitReason, Optional.of(number),
                statusCommentId, notes);
    }

    public IssueRecord withStatusComment(long commentId) {
        return new IssueRecord(issueNumber, sessionId, status, phase, reviews, fixLoops, risk, small,
                reviewedTreeHash, reviewedPatchId, builtTreeHash, waitReason, pullRequestNumber,
                Optional.of(commentId), notes);
    }

    public IssueRecord withNote(String note) {
        if (notes.contains(note)) {
            return this;
        }
        List<String> newNotes = new ArrayList<>(notes);
        newNotes.add(note);
        return new IssueRecord(issueNumber, sessionId, status, phase, reviews, fixLoops, risk, small,
                reviewedTreeHash, reviewedPatchId, builtTreeHash, waitReason, pullRequestNumber,
                statusCommentId, newNotes);
    }
}
