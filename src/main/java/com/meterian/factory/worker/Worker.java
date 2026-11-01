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
    private final Set<Integer> blockedIssues = new HashSet<>();
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

    /** Polls at the poll interval until an API key stops the worker. */
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

    /** A GitHub failure keeps the issue out of work for this poll only. */
    private Optional<IssueRecord> checkIssue(Issue issue) {
        try {
            return findStartableRecord(issue);
        } catch (GitHubException exception) {
            logGitHubFailure("The check of the issue #" + issue.number(), exception);
            return Optional.empty();
        }
    }

    private Optional<IssueRecord> findStartableRecord(Issue issue) {
        if (isBlockRequested(issue)) {
            requestBlock(issue.number());
            return Optional.empty();
        }
        if (isInWork(issue.number())) {
            return Optional.empty();
        }
        List<IssueComment> comments = gitHub.comments(issue.number());
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

    private boolean isBlockRequested(Issue issue) {
        return issue.labels().contains(BLOCK_LABEL) && requests.isGivenByAllowedAccount(issue.number(), BLOCK_LABEL);
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
            noteIgnoredIntake(issue.number(), comments);
            return Optional.empty();
        }
        return Optional.of(publishFirstRecord(issue, IssueRecord.queued(issue.number())));
    }

    /** Writes the note once: a comment of the worker with the same text means the note is there. */
    private void noteIgnoredIntake(int issueNumber, List<IssueComment> comments) {
        String actor = requests.findLabelActor(issueNumber, FACTORY_LABEL).orElse("an unknown account");
        String note = "The label " + FACTORY_LABEL + " from " + actor
                + " is ignored: the account is not in accounts.allowed.";
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
            releaseIssue(start);
        }
    }

    private synchronized boolean reserveSlot(int issueNumber) {
        boolean hasFreeSlot = issuesInWork.size() < configuration.limits().parallelIssues();
        return hasFreeSlot && !isStopped() && !blockedIssues.contains(issueNumber) && issuesInWork.add(issueNumber);
    }

    private synchronized boolean isInWork(int issueNumber) {
        return issuesInWork.contains(issueNumber);
    }

    /** A restart runs the phase again from its start. Without the worktree, the issue prepares again. */
    private IssueRecord prepareRestart(IssueRecord record) {
        boolean needsWorktree = PHASES_WITH_A_WORKTREE.contains(record.phase());
        if (needsWorktree && !Files.isDirectory(layout.worktree(record.issueNumber()))) {
            return record.withSessionId(UUID.randomUUID()).withPhase(Phase.PREPARE);
        }
        return record;
    }

    /** Keeps the result of a step only when no block and no stop came during the step. */
    private void runIssue(IssueRecord start) {
        int issueNumber = start.issueNumber();
        IssueRecord kept = start;
        try {
            do {
                if (!mayContinue(issueNumber)) {
                    break;
                }
                IssueRecord next = workflow.advance(kept);
                if (!mayContinue(issueNumber) || next.equals(kept)) {
                    break;
                }
                kept = next;
            } while (!workflow.isWaiting(kept));
        } catch (ApiKeyDetectedException exception) {
            stopOnApiKey(issueNumber, exception);
        } catch (GitHubException exception) {
            logGitHubFailure("The step of the issue #" + issueNumber, exception);
        } catch (RuntimeException exception) {
            blockOnFailure(kept, exception);
        } finally {
            releaseIssue(kept);
        }
    }

    private synchronized boolean mayContinue(int issueNumber) {
        return !isStopped() && !blockedIssues.contains(issueNumber);
    }

    /** Exactly one of requestBlock and releaseIssue sees the other, so the block is written once. */
    private void requestBlock(int issueNumber) {
        boolean isRunning;
        synchronized (this) {
            blockedIssues.add(issueNumber);
            isRunning = issuesInWork.contains(issueNumber);
        }
        agentRunner.stopPhase(issueNumber);
        if (!isRunning) {
            finishBlock(issueNumber, Optional.empty());
        }
    }

    private void releaseIssue(IssueRecord kept) {
        boolean isBlocked;
        synchronized (this) {
            issuesInWork.remove(kept.issueNumber());
            isBlocked = blockedIssues.contains(kept.issueNumber());
        }
        if (isBlocked) {
            finishBlock(kept.issueNumber(), Optional.of(kept));
        }
    }

    /** The stopped step can start the agent session. The next run must resume that session, not start it again. */
    private static IssueRecord keepSessionStart(IssueRecord kept, Optional<IssueRecord> onGitHub) {
        boolean sessionStarted = onGitHub.filter(record -> record.sessionId().equals(kept.sessionId()))
                .map(IssueRecord::agentSessionStarted)
                .orElse(false);
        return sessionStarted && !kept.agentSessionStarted() ? kept.withAgentSessionStarted() : kept;
    }

    /**
     * Writes BLOCKED over the record. The record of a loop is the last record that the loop kept; without a
     * loop, the record on GitHub is the last result.
     */
    private void finishBlock(int issueNumber, Optional<IssueRecord> keptByLoop) {
        try {
            Optional<IssueRecord> onGitHub = readRecord(issueNumber);
            IssueRecord base = keptByLoop.map(kept -> keepSessionStart(kept, onGitHub))
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
        stopSignal.countDown();
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
