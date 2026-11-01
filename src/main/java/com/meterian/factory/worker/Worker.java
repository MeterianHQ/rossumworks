package com.meterian.factory.worker;

import com.meterian.factory.agent.AgentRunner;
import com.meterian.factory.agent.ApiKeyDetectedException;
import com.meterian.factory.configuration.FactoryConfiguration;
import com.meterian.factory.github.GitHubClient;
import com.meterian.factory.github.GitHubException;
import com.meterian.factory.github.Issue;
import com.meterian.factory.github.IssueComment;
import com.meterian.factory.state.IssueRecord;
import com.meterian.factory.state.IssueStatus;
import com.meterian.factory.state.Phase;
import com.meterian.factory.state.StatusComment;
import com.meterian.factory.state.WaitReason;
import com.meterian.factory.workflow.IssueWorkflow;
import com.meterian.factory.workflow.RequestReader;
import com.meterian.factory.workflow.StatusPublisher;
import com.meterian.factory.workflow.WorkspaceLayout;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

/** Polls GitHub, takes the labeled issues into the queue, and runs each issue in a loop on the executor. */
public final class Worker {

    private static final System.Logger LOGGER = System.getLogger(Worker.class.getName());
    private static final String FACTORY_LABEL = "factory";
    private static final String APPROVE_LABEL = "factory:approve";
    private static final String BLOCK_LABEL = "factory:block";
    private static final String ENTER_LABEL = "factory:enter";
    private static final String BROKEN_RECORD_NOTE =
            "The status record of the factory is broken. The factory does not work on this issue.";
    private static final Set<Phase> PHASES_WITH_A_WORKTREE =
            EnumSet.of(Phase.IMPLEMENT, Phase.BUILD, Phase.REVIEW, Phase.RISK, Phase.MERGE);

    private final FactoryConfiguration configuration;
    private final GitHubClient gitHub;
    private final IssueWorkflow workflow;
    private final AgentRunner agentRunner;
    private final RequestReader requests;
    private final String workerLogin;
    private final ExecutorService executor;
    private final WorkspaceLayout layout;
    private final StatusPublisher statusPublisher;
    private final CountDownLatch stopSignal = new CountDownLatch(1);
    private final Set<Integer> issuesInWork = new HashSet<>();
    /** The issues with a block that is requested or in progress. */
    private final Set<Integer> blockedIssues = new HashSet<>();
    private volatile boolean apiKeyFound;
    private boolean labelsEnsured;

    public Worker(FactoryConfiguration configuration, GitHubClient gitHub, IssueWorkflow workflow,
                  AgentRunner agentRunner, RequestReader requests, String workerLogin, ExecutorService executor) {
        this.configuration = configuration;
        this.gitHub = gitHub;
        this.workflow = workflow;
        this.agentRunner = agentRunner;
        this.requests = requests;
        this.workerLogin = workerLogin;
        this.executor = executor;
        this.layout = new WorkspaceLayout(configuration.workDirectory());
        this.statusPublisher = new StatusPublisher(gitHub, workerLogin, configuration.limits().fixLoops());
    }

    /** Reads the labeled issues once: block requests, intake, and the start of the issues up to the limit. */
    public void pollOnce() {
        if (isStopped()) {
            return;
        }
        try {
            ensureLabelsOnce();
            List<IssueRecord> startable = new ArrayList<>();
            for (Issue issue : readLabeledIssues()) {
                checkIssue(issue).ifPresent(startable::add);
            }
            startable.forEach(this::startIssue);
        } catch (GitHubException exception) {
            logGitHubFailure("The poll", exception);
        }
    }

    /** Polls at the poll interval until the worker stops. */
    public void runUntilStopped() throws InterruptedException {
        long pollIntervalMillis = configuration.limits().pollInterval().toMillis();
        while (!isStopped()) {
            pollOnce();
            stopSignal.await(pollIntervalMillis, TimeUnit.MILLISECONDS);
        }
    }

    public boolean isStopped() {
        return stopSignal.getCount() == 0;
    }

    /** Tells if the worker stopped because a claude session used an API key. */
    public boolean foundAnApiKey() {
        return apiKeyFound;
    }

    /**
     * Stops the worker, ends the phases in work, and waits for the loops. A loop that ends because of the stop
     * writes back the record from before the stopped step.
     *
     * @return true when all loops ended in the timeout
     */
    public boolean shutDown(Duration timeout) {
        stopSignal.countDown();
        stopPhasesInWork();
        executor.shutdown();
        try {
            return executor.awaitTermination(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    public synchronized Set<Integer> issuesInWork() {
        return Set.copyOf(issuesInWork);
    }

    private void ensureLabelsOnce() {
        if (labelsEnsured) {
            return;
        }
        gitHub.ensureLabel(FACTORY_LABEL, "1d76db", "The factory works on this issue.");
        gitHub.ensureLabel(APPROVE_LABEL, "0e8a16", "The owner approves the merge of the factory.");
        gitHub.ensureLabel(BLOCK_LABEL, "b60205", "The owner stops the factory on this issue.");
        gitHub.ensureLabel(ENTER_LABEL, "fbca04", "The owner enters the session of the agent.");
        gitHub.ensureStatusLabels();
        labelsEnsured = true;
    }

    private List<Issue> readLabeledIssues() {
        return gitHub.openIssuesWithLabel(FACTORY_LABEL).stream()
                .sorted(Comparator.comparingInt(Issue::number))
                .toList();
    }

    /** A failure keeps the issue out of work for this poll only. The next issue still gets its check. */
    private Optional<IssueRecord> checkIssue(Issue issue) {
        try {
            return findStartableRecord(issue);
        } catch (GitHubException exception) {
            logGitHubFailure("The check of the issue #" + issue.number(), exception);
            return Optional.empty();
        } catch (RuntimeException exception) {
            LOGGER.log(System.Logger.Level.ERROR, "The check of the issue #" + issue.number() + " failed.",
                    exception);
            return Optional.empty();
        }
    }

    private Optional<IssueRecord> findStartableRecord(Issue issue) {
        int issueNumber = issue.number();
        if (isBlockInProgress(issueNumber)) {
            return Optional.empty();
        }
        if (issue.labels().contains(BLOCK_LABEL)) {
            if (requests.isGivenByAllowedAccount(issueNumber, BLOCK_LABEL)) {
                requestBlock(issueNumber);
                return Optional.empty();
            }
            noteIgnoredLabel(issueNumber, BLOCK_LABEL, gitHub.comments(issueNumber));
        }
        if (isInWork(issueNumber)) {
            return Optional.empty();
        }
        List<IssueComment> comments = gitHub.comments(issueNumber);
        if (StatusComment.hasBrokenRecord(comments, workerLogin)) {
            blockBrokenRecord(issue);
            return Optional.empty();
        }
        Optional<IssueRecord> record = StatusComment.findRecord(comments, workerLogin);
        if (record.isEmpty()) {
            return takeIntoQueue(issue, comments);
        }
        return record.filter(found -> isStartable(issue, found));
    }

    private boolean isStartable(Issue issue, IssueRecord record) {
        return !workflow.isWaiting(record) || waitsForAGivenApprovalLabel(issue, record);
    }

    /** The workflow checks who gave the label, and notes a label from another account. */
    private static boolean waitsForAGivenApprovalLabel(Issue issue, IssueRecord record) {
        return record.status() == IssueStatus.WAITS_FOR_YOU
                && record.waitReason().equals(Optional.of(WaitReason.MERGE_NEEDS_APPROVAL))
                && issue.labels().contains(APPROVE_LABEL);
    }

    private Optional<IssueRecord> takeIntoQueue(Issue issue, List<IssueComment> comments) {
        if (!requests.isGivenByAllowedAccount(issue.number(), FACTORY_LABEL)) {
            noteIgnoredLabel(issue.number(), FACTORY_LABEL, comments);
            return Optional.empty();
        }
        return Optional.of(publishFirstRecord(issue, IssueRecord.queued(issue.number())));
    }

    /** Writes the note once: a comment of the worker with the same text means the note is there. */
    private void noteIgnoredLabel(int issueNumber, String label, List<IssueComment> comments) {
        String actor = requests.findLabelActor(issueNumber, label).orElse("an unknown account");
        String note = "The label " + label + " from " + actor + " is ignored: the account is not in accounts.allowed.";
        boolean isNoted = comments.stream()
                .anyMatch(comment -> workerLogin.equals(comment.authorLogin()) && note.equals(comment.body()));
        if (!isNoted) {
            gitHub.createComment(issueNumber, note);
        }
    }

    /** The new status comment becomes the newest record, so the broken record never counts again. */
    private void blockBrokenRecord(Issue issue) {
        IssueRecord blocked = IssueRecord.queued(issue.number())
                .withStatus(IssueStatus.BLOCKED)
                .withWaitReason(WaitReason.FAILURE)
                .withNote(BROKEN_RECORD_NOTE);
        publishFirstRecord(issue, blocked);
    }

    /** There is no record before the first record, so the publisher always writes it. */
    private IssueRecord publishFirstRecord(Issue issue, IssueRecord record) {
        return statusPublisher.publish(issue, null, record);
    }

    private void startIssue(IssueRecord record) {
        int issueNumber = record.issueNumber();
        if (!reserveSlot(issueNumber)) {
            return;
        }
        IssueRecord start = prepareRestart(record);
        try {
            executor.submit(() -> runIssue(start));
        } catch (RejectedExecutionException exception) {
            LOGGER.log(System.Logger.Level.WARNING, "The executor refuses the issue #{0}.", issueNumber);
            releaseIssue(start, Optional.empty());
        }
    }

    private synchronized boolean reserveSlot(int issueNumber) {
        boolean hasFreeSlot = issuesInWork.size() < configuration.limits().parallelIssues();
        return hasFreeSlot && !isStopped() && !blockedIssues.contains(issueNumber) && issuesInWork.add(issueNumber);
    }

    private synchronized boolean isInWork(int issueNumber) {
        return issuesInWork.contains(issueNumber);
    }

    private synchronized boolean isBlockInProgress(int issueNumber) {
        return blockedIssues.contains(issueNumber);
    }

    /**
     * A restart runs the phase again from its start. Without the worktree, the issue prepares again in a new
     * session, and the results that belong to the old worktree go away. The fix-loop count stays.
     */
    private IssueRecord prepareRestart(IssueRecord record) {
        boolean needsWorktree = PHASES_WITH_A_WORKTREE.contains(record.phase());
        if (!needsWorktree || Files.isDirectory(layout.worktree(record.issueNumber()))) {
            return record;
        }
        return record.withSessionId(UUID.randomUUID())
                .withoutReviews()
                .withoutConflictedPaths()
                .withoutRisk()
                .withoutBuiltTree()
                .withPhase(Phase.PREPARE);
    }

    /** Keeps the result of a step only when no block and no stop came during the step, or when it merged. */
    private void runIssue(IssueRecord start) {
        int issueNumber = start.issueNumber();
        IssueRecord kept = start;
        Optional<IssueRecord> interruptedResult = Optional.empty();
        try {
            while (mayContinue(issueNumber)) {
                IssueRecord next = workflow.advance(kept);
                if (!mayContinue(issueNumber) && !keepsTheMerge(issueNumber, next)) {
                    interruptedResult = Optional.of(next);
                    break;
                }
                if (next.equals(kept)) {
                    break;
                }
                kept = next;
                if (workflow.isWaiting(kept)) {
                    break;
                }
            }
        } catch (ApiKeyDetectedException exception) {
            stopOnApiKey(issueNumber, exception);
        } catch (GitHubException exception) {
            logGitHubFailure("The step of the issue #" + issueNumber, exception);
        } catch (RuntimeException exception) {
            blockOnFailure(kept, exception);
        } catch (Error error) {
            LOGGER.log(System.Logger.Level.ERROR, "The loop of the issue #" + issueNumber + " ends.", error);
            throw error;
        } finally {
            releaseIssue(kept, interruptedResult);
        }
    }

    private synchronized boolean mayContinue(int issueNumber) {
        return !isStopped() && !blockedIssues.contains(issueNumber);
    }

    /** A merged change cannot be blocked: the issue continues to DONE and the block request goes away. */
    private boolean keepsTheMerge(int issueNumber, IssueRecord result) {
        if (result.phase() != Phase.DONE) {
            return false;
        }
        if (isBlockInProgress(issueNumber)) {
            gitHub.removeLabel(issueNumber, BLOCK_LABEL);
            synchronized (this) {
                blockedIssues.remove(issueNumber);
            }
        }
        return true;
    }

    /** Exactly one of requestBlock and releaseIssue sees the other, so the block is written once. */
    private void requestBlock(int issueNumber) {
        boolean isRunning;
        synchronized (this) {
            if (!blockedIssues.add(issueNumber)) {
                return;
            }
            isRunning = issuesInWork.contains(issueNumber);
        }
        agentRunner.stopPhase(issueNumber);
        if (!isRunning) {
            finishBlock(issueNumber, Optional.empty(), Optional.empty());
        }
    }

    private void releaseIssue(IssueRecord kept, Optional<IssueRecord> interruptedResult) {
        boolean isBlocked;
        synchronized (this) {
            issuesInWork.remove(kept.issueNumber());
            isBlocked = blockedIssues.contains(kept.issueNumber());
        }
        if (isBlocked) {
            finishBlock(kept.issueNumber(), Optional.of(kept), interruptedResult);
        } else if (interruptedResult.isPresent()) {
            restoreKeptRecord(kept, interruptedResult.get());
        }
    }

    /**
     * Writes BLOCKED over the record. The record of a loop is the last record that the loop kept; without a
     * loop, the record on GitHub is the last result.
     */
    private void finishBlock(int issueNumber, Optional<IssueRecord> keptByLoop,
                             Optional<IssueRecord> interruptedResult) {
        try {
            Optional<IssueRecord> onGitHub = readRecord(issueNumber);
            Optional<IssueRecord> laterRecord = interruptedResult.or(() -> onGitHub);
            IssueRecord base = keptByLoop.map(kept -> keepFacts(kept, laterRecord))
                    .orElseGet(() -> onGitHub.orElseGet(() -> IssueRecord.queued(issueNumber)));
            IssueRecord blocked = base.withStatus(IssueStatus.BLOCKED).withWaitReason(WaitReason.BLOCKED_BY_OWNER);
            statusPublisher.publish(gitHub.issue(issueNumber), onGitHub.orElse(null), blocked);
            gitHub.removeLabel(issueNumber, BLOCK_LABEL);
        } catch (GitHubException exception) {
            logGitHubFailure("The block of the issue #" + issueNumber, exception);
        } finally {
            synchronized (this) {
                blockedIssues.remove(issueNumber);
            }
        }
    }

    /** The stop came during a step, so the record from before the step goes back to GitHub. */
    private void restoreKeptRecord(IssueRecord kept, IssueRecord interruptedResult) {
        int issueNumber = kept.issueNumber();
        try {
            IssueRecord restored = keepFacts(kept, Optional.of(interruptedResult));
            statusPublisher.publish(gitHub.issue(issueNumber), interruptedResult, restored);
        } catch (GitHubException exception) {
            logGitHubFailure("The restore of the issue #" + issueNumber, exception);
        }
    }

    /**
     * Keeps the facts of a later record that the kept record must not lose: the start of the agent session
     * (the next run must resume it) and the pull request that the step opened.
     */
    private static IssueRecord keepFacts(IssueRecord kept, Optional<IssueRecord> laterRecord) {
        if (laterRecord.isEmpty()) {
            return kept;
        }
        IssueRecord later = laterRecord.get();
        IssueRecord result = kept;
        boolean isSameSession = later.sessionId().equals(kept.sessionId());
        if (isSameSession && later.agentSessionStarted() && !kept.agentSessionStarted()) {
            result = result.withAgentSessionStarted();
        }
        if (kept.pullRequestNumber().isEmpty() && later.pullRequestNumber().isPresent()) {
            result = result.withPullRequest(later.pullRequestNumber().get());
        }
        return result;
    }

    /** Fail closed: the issue becomes BLOCKED with the cause, and the worker keeps running. */
    private void blockOnFailure(IssueRecord kept, RuntimeException exception) {
        int issueNumber = kept.issueNumber();
        LOGGER.log(System.Logger.Level.ERROR, "The issue #" + issueNumber + " failed.", exception);
        String note = "An unexpected failure stopped the issue: " + exception.getClass().getName()
                + ": " + exception.getMessage();
        try {
            Optional<IssueRecord> onGitHub = readRecord(issueNumber);
            IssueRecord blocked = onGitHub.orElse(kept)
                    .withStatus(IssueStatus.BLOCKED)
                    .withWaitReason(WaitReason.FAILURE)
                    .withNote(note);
            statusPublisher.publish(gitHub.issue(issueNumber), onGitHub.orElse(null), blocked);
        } catch (GitHubException publishException) {
            logGitHubFailure("The block of the issue #" + issueNumber, publishException);
        }
    }

    /** Fail closed: the worker stops and ends the phases that run. */
    private void stopOnApiKey(int issueNumber, ApiKeyDetectedException exception) {
        LOGGER.log(System.Logger.Level.ERROR, "The issue #{0} found an API key: {1}", issueNumber,
                exception.getMessage());
        apiKeyFound = true;
        stopSignal.countDown();
        stopPhasesInWork();
    }

    private void stopPhasesInWork() {
        issuesInWork().forEach(agentRunner::stopPhase);
    }

    private Optional<IssueRecord> readRecord(int issueNumber) {
        return StatusComment.findRecord(gitHub.comments(issueNumber), workerLogin);
    }

    /** The message of a GitHubException never holds the token. */
    private static void logGitHubFailure(String step, GitHubException exception) {
        LOGGER.log(System.Logger.Level.WARNING, "{0} failed: {1}", step, exception.getMessage());
    }
}
