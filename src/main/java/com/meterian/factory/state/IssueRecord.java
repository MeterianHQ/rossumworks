package com.meterian.factory.state;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * The record of one issue in the status comment of the worker.
 *
 * @param state         the name of the current state of the workflow
 * @param action        the action type of the state; empty for a state without an action
 * @param column        the column of the board of the state
 * @param previousState the value of previous: the last state with an action that does not wait for you
 * @param entries       the count of the entries of each state with a limit
 * @param pendingPrompt the prompt of the next coding action
 * @param actionStarted true when the action of the current state started, so its entry counts already
 * @param approvedTree  the tree that the owner approved for the merge; empty when no approval counts
 * @param handOver      the reason why the record passes to another factory or to a later action
 */
public record IssueRecord(
        int issueNumber,
        UUID sessionId,
        String state,
        Optional<ActionType> action,
        String column,
        Optional<String> previousState,
        Map<String, Integer> entries,
        Optional<PendingPrompt> pendingPrompt,
        boolean actionStarted,
        Map<String, Verdict> reviews,
        Optional<RiskLevel> risk,
        boolean small,
        List<String> riskReasons,
        Optional<String> reviewedTreeHash,
        Optional<String> reviewedPatchId,
        Optional<String> builtTreeHash,
        Optional<Integer> pullRequestNumber,
        Optional<Long> statusCommentId,
        List<String> notes,
        boolean agentSessionStarted,
        int failedReviewRounds,
        List<String> conflictedPaths,
        Optional<String> issueTextHash,
        Optional<String> reviewedHeadCommit,
        int failedForwardChecks,
        Optional<String> failedForwardTree,
        Optional<LiveSession> live,
        boolean afterConversation,
        @JsonProperty("definitionCommit") Optional<String> definitionVersion,
        Optional<String> approvedTree,
        Optional<HandOver> handOver,
        Optional<CheckRunWait> checkRunWait) {

    private static final String QUEUED_COLUMN = "queued";

    /** A record from before a new field reads the field as empty. */
    public IssueRecord {
        action = action == null ? Optional.empty() : action;
        previousState = previousState == null ? Optional.empty() : previousState;
        entries = entries == null ? Map.of() : Map.copyOf(entries);
        pendingPrompt = pendingPrompt == null ? Optional.empty() : pendingPrompt;
        reviews = Collections.unmodifiableMap(new LinkedHashMap<>(reviews));
        riskReasons = riskReasons == null ? List.of() : List.copyOf(riskReasons);
        notes = List.copyOf(notes);
        conflictedPaths = conflictedPaths == null ? List.of() : List.copyOf(conflictedPaths);
        issueTextHash = issueTextHash == null ? Optional.empty() : issueTextHash;
        reviewedHeadCommit = reviewedHeadCommit == null ? Optional.empty() : reviewedHeadCommit;
        failedForwardTree = failedForwardTree == null ? Optional.empty() : failedForwardTree;
        live = live == null ? Optional.empty() : live;
        definitionVersion = definitionVersion == null ? Optional.empty() : definitionVersion;
        approvedTree = approvedTree == null ? Optional.empty() : approvedTree;
        handOver = handOver == null ? Optional.empty() : handOver;
        checkRunWait = checkRunWait == null ? Optional.empty() : checkRunWait;
    }

    /** A new issue in the start state, whose action is always prepare. previous is the start state. */
    public static IssueRecord queued(int issueNumber, String startState) {
        return new IssueRecord(issueNumber, UUID.randomUUID(), startState, Optional.of(ActionType.PREPARE),
                QUEUED_COLUMN, Optional.of(startState), Map.of(), Optional.empty(), false, Map.of(),
                Optional.empty(), false, List.of(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), List.of(), false, 0, List.of(), Optional.empty(), Optional.empty(), 0,
                Optional.empty(), Optional.empty(), false, Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty());
    }

    /** The count of the entries into the state, 0 when the issue did not enter it yet. */
    public int entriesOf(String stateName) {
        return entries.getOrDefault(stateName, 0);
    }

    /** The newest note, which the card shows. */
    public Optional<String> newestNote() {
        return notes.isEmpty() ? Optional.empty() : Optional.of(notes.get(notes.size() - 1));
    }

    /** The action of the new state did not start yet. */
    public IssueRecord withMove(String newState, Optional<ActionType> newAction, String newColumn) {
        return copy(fields -> {
            fields.state = newState;
            fields.action = newAction;
            fields.column = newColumn;
            fields.actionStarted = false;
        });
    }

    public IssueRecord withPreviousState(String newPreviousState) {
        return copy(fields -> fields.previousState = Optional.of(newPreviousState));
    }

    public IssueRecord withEntryCounted(String stateName) {
        Map<String, Integer> newEntries = new LinkedHashMap<>(entries);
        newEntries.merge(stateName, 1, Integer::sum);
        return copy(fields -> fields.entries = newEntries);
    }

    public IssueRecord withoutEntries() {
        return copy(fields -> fields.entries = Map.of());
    }

    public IssueRecord withPendingPrompt(PendingPrompt newPendingPrompt) {
        return copy(fields -> fields.pendingPrompt = Optional.of(newPendingPrompt));
    }

    public IssueRecord withActionStarted() {
        return copy(fields -> fields.actionStarted = true);
    }

    /** A new session has not started yet, so the next agent run starts it. */
    public IssueRecord withSessionId(UUID newSessionId) {
        return copy(fields -> {
            fields.sessionId = newSessionId;
            fields.agentSessionStarted = false;
        });
    }

    public IssueRecord withFailedReviewRound() {
        return copy(fields -> fields.failedReviewRounds = failedReviewRounds + 1);
    }

    public IssueRecord withoutFailedReviewRounds() {
        return copy(fields -> fields.failedReviewRounds = 0);
    }

    /** Keeps the paths with a conflict while the merge is open. The check of the markers reads them. */
    public IssueRecord withConflictedPaths(List<String> newConflictedPaths) {
        return copy(fields -> fields.conflictedPaths = newConflictedPaths);
    }

    public IssueRecord withoutConflictedPaths() {
        return withConflictedPaths(List.of());
    }

    public IssueRecord withAgentSessionStarted() {
        return copy(fields -> fields.agentSessionStarted = true);
    }

    public IssueRecord withDefinitionVersion(String version) {
        return copy(fields -> fields.definitionVersion = Optional.of(version));
    }

    /** The reviews keep the order of their first verdict, which is the order of the definition. */
    public IssueRecord withReview(String reviewName, Verdict verdict) {
        Map<String, Verdict> newReviews = new LinkedHashMap<>(reviews);
        newReviews.put(reviewName, verdict);
        return copy(fields -> fields.reviews = newReviews);
    }

    public IssueRecord withoutReviews() {
        return copy(fields -> {
            fields.reviews = Map.of();
            fields.reviewedTreeHash = Optional.empty();
            fields.reviewedPatchId = Optional.empty();
            fields.reviewedHeadCommit = Optional.empty();
        });
    }

    /** Each new risk result replaces the reasons of the old one. */
    public IssueRecord withRisk(RiskLevel newRisk, boolean newSmall, List<String> newReasons) {
        return copy(fields -> {
            fields.risk = Optional.of(newRisk);
            fields.small = newSmall;
            fields.riskReasons = newReasons;
        });
    }

    public IssueRecord withoutRisk() {
        return copy(fields -> {
            fields.risk = Optional.empty();
            fields.small = false;
            fields.riskReasons = List.of();
        });
    }

    public IssueRecord withoutBuiltTree() {
        return copy(fields -> fields.builtTreeHash = Optional.empty());
    }

    public IssueRecord withReviewedTree(String treeHash, String patchId) {
        return copy(fields -> {
            fields.reviewedTreeHash = Optional.of(treeHash);
            fields.reviewedPatchId = Optional.of(patchId);
        });
    }

    /** The merge is approved for the files of this tree. A change of the code removes the approval. */
    public IssueRecord withApprovedTree(String treeHash) {
        return copy(fields -> fields.approvedTree = Optional.of(treeHash));
    }

    public IssueRecord withoutApprovedTree() {
        return copy(fields -> fields.approvedTree = Optional.empty());
    }

    public IssueRecord withBuiltTree(String treeHash) {
        return copy(fields -> fields.builtTreeHash = Optional.of(treeHash));
    }

    public IssueRecord withPullRequest(int number) {
        return copy(fields -> fields.pullRequestNumber = Optional.of(number));
    }

    public IssueRecord withStatusComment(long commentId) {
        return copy(fields -> fields.statusCommentId = Optional.of(commentId));
    }

    /** The hash of the issue text that the owner accepted: at the intake, or with the label factory:approve. */
    public IssueRecord withIssueTextHash(String newIssueTextHash) {
        return copy(fields -> fields.issueTextHash = Optional.of(newIssueTextHash));
    }

    /** The commit that the reviews approved. A merge action that does not end goes back to it. */
    public IssueRecord withReviewedHeadCommit(String commit) {
        return copy(fields -> fields.reviewedHeadCommit = Optional.of(commit));
    }

    /**
     * The merger rebased the reviewed commit with the same patch id, built it, and pushed it. The reviews and an
     * approval of the reviewed tree stay valid for the rebased tree, so the next merge attempt continues on the
     * rebased commit and does not rebase or build again.
     */
    public IssueRecord withRebasedCommit(String commit, String treeHash) {
        boolean isApprovedForTheReviewedTree = approvedTree.isPresent() && approvedTree.equals(reviewedTreeHash);
        return copy(fields -> {
            fields.reviewedHeadCommit = Optional.of(commit);
            fields.reviewedTreeHash = Optional.of(treeHash);
            fields.builtTreeHash = Optional.of(treeHash);
            if (isApprovedForTheReviewedTree) {
                fields.approvedTree = Optional.of(treeHash);
            }
        });
    }

    /** The merge waits for the required check runs of the commit. A wait on the same commit keeps its start. */
    public IssueRecord withCheckRunWait(String commit, Instant now) {
        if (checkRunWait.filter(wait -> wait.commit().equals(commit)).isPresent()) {
            return this;
        }
        return copy(fields -> fields.checkRunWait = Optional.of(CheckRunWait.startNow(commit, now)));
    }

    public IssueRecord withoutCheckRunWait() {
        return copy(fields -> fields.checkRunWait = Optional.empty());
    }

    /**
     * Counts a check that did not find the pushed commit on GitHub. The key is the tree, because each rebase
     * makes a new commit of the same change. A new tree starts the count again.
     */
    public IssueRecord withFailedForwardCheck(String pushedTree) {
        boolean isSameTree = failedForwardTree.equals(Optional.of(pushedTree));
        return copy(fields -> {
            fields.failedForwardChecks = isSameTree ? failedForwardChecks + 1 : 1;
            fields.failedForwardTree = Optional.of(pushedTree);
        });
    }

    public IssueRecord withoutFailedForwardChecks() {
        return copy(fields -> {
            fields.failedForwardChecks = 0;
            fields.failedForwardTree = Optional.empty();
        });
    }

    /** Moves the note to the end when the list has it already, so that the card shows it as the newest note. */
    public IssueRecord withNewestNote(String note) {
        List<String> newNotes = new ArrayList<>(notes);
        newNotes.remove(note);
        newNotes.add(note);
        return copy(fields -> fields.notes = newNotes);
    }

    public IssueRecord withNote(String note) {
        if (notes.contains(note)) {
            return this;
        }
        List<String> newNotes = new ArrayList<>(notes);
        newNotes.add(note);
        return copy(fields -> fields.notes = newNotes);
    }

    public IssueRecord withLive(LiveSession newLive) {
        return copy(fields -> fields.live = Optional.of(newLive));
    }

    public IssueRecord withoutLive() {
        return copy(fields -> fields.live = Optional.empty());
    }

    /** Sets the link of the live conversation. Does nothing when the issue is not live. */
    public IssueRecord withLiveLink(String link) {
        return live.map(session -> withLive(session.withLink(link))).orElse(this);
    }

    public IssueRecord withoutLiveLink() {
        return live.map(session -> withLive(session.withoutLink())).orElse(this);
    }

    public IssueRecord withAfterConversation() {
        return copy(fields -> fields.afterConversation = true);
    }

    public IssueRecord withoutAfterConversation() {
        return copy(fields -> fields.afterConversation = false);
    }

    public IssueRecord withHandOver(HandOver newHandOver) {
        return copy(fields -> fields.handOver = Optional.of(newHandOver));
    }

    public IssueRecord withoutHandOver() {
        return copy(fields -> fields.handOver = Optional.empty());
    }

    private IssueRecord copy(Consumer<Fields> change) {
        Fields fields = new Fields(this);
        change.accept(fields);
        return fields.toRecord();
    }

    /** The fields of a record. A with method changes some of them, and the copy becomes a new record. */
    private static final class Fields {

        private final int issueNumber;
        private UUID sessionId;
        private String state;
        private Optional<ActionType> action;
        private String column;
        private Optional<String> previousState;
        private Map<String, Integer> entries;
        private Optional<PendingPrompt> pendingPrompt;
        private boolean actionStarted;
        private Map<String, Verdict> reviews;
        private Optional<RiskLevel> risk;
        private boolean small;
        private List<String> riskReasons;
        private Optional<String> reviewedTreeHash;
        private Optional<String> reviewedPatchId;
        private Optional<String> builtTreeHash;
        private Optional<Integer> pullRequestNumber;
        private Optional<Long> statusCommentId;
        private List<String> notes;
        private boolean agentSessionStarted;
        private int failedReviewRounds;
        private List<String> conflictedPaths;
        private Optional<String> issueTextHash;
        private Optional<String> reviewedHeadCommit;
        private int failedForwardChecks;
        private Optional<String> failedForwardTree;
        private Optional<LiveSession> live;
        private boolean afterConversation;
        private Optional<String> definitionVersion;
        private Optional<String> approvedTree;
        private Optional<HandOver> handOver;
        private Optional<CheckRunWait> checkRunWait;

        private Fields(IssueRecord record) {
            issueNumber = record.issueNumber;
            sessionId = record.sessionId;
            state = record.state;
            action = record.action;
            column = record.column;
            previousState = record.previousState;
            entries = record.entries;
            pendingPrompt = record.pendingPrompt;
            actionStarted = record.actionStarted;
            reviews = record.reviews;
            risk = record.risk;
            small = record.small;
            riskReasons = record.riskReasons;
            reviewedTreeHash = record.reviewedTreeHash;
            reviewedPatchId = record.reviewedPatchId;
            builtTreeHash = record.builtTreeHash;
            pullRequestNumber = record.pullRequestNumber;
            statusCommentId = record.statusCommentId;
            notes = record.notes;
            agentSessionStarted = record.agentSessionStarted;
            failedReviewRounds = record.failedReviewRounds;
            conflictedPaths = record.conflictedPaths;
            issueTextHash = record.issueTextHash;
            reviewedHeadCommit = record.reviewedHeadCommit;
            failedForwardChecks = record.failedForwardChecks;
            failedForwardTree = record.failedForwardTree;
            live = record.live;
            afterConversation = record.afterConversation;
            definitionVersion = record.definitionVersion;
            approvedTree = record.approvedTree;
            handOver = record.handOver;
            checkRunWait = record.checkRunWait;
        }

        private IssueRecord toRecord() {
            return new IssueRecord(issueNumber, sessionId, state, action, column, previousState, entries,
                    pendingPrompt, actionStarted, reviews, risk, small, riskReasons, reviewedTreeHash, reviewedPatchId,
                    builtTreeHash, pullRequestNumber, statusCommentId, notes, agentSessionStarted,
                    failedReviewRounds, conflictedPaths, issueTextHash, reviewedHeadCommit, failedForwardChecks,
                    failedForwardTree, live, afterConversation, definitionVersion, approvedTree, handOver,
                    checkRunWait);
        }
    }
}
