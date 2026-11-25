package com.meterian.factory.worker;

import com.meterian.factory.agent.CredentialMismatchException;
import com.meterian.factory.agent.LiveSessions;
import com.meterian.factory.board.BoardUpdates;
import com.meterian.factory.board.FactorySwitch;
import com.meterian.factory.configuration.FactoryConfiguration;
import com.meterian.factory.definition.ColumnNames;
import com.meterian.factory.definition.DefinitionVersion;
import com.meterian.factory.definition.DefinitionVersions;
import com.meterian.factory.definition.StateDefinition;
import com.meterian.factory.definition.WorkflowDefinition;
import com.meterian.factory.events.FactoryVersion;
import com.meterian.factory.events.WorkerEvents;
import com.meterian.factory.github.GitHubClient;
import com.meterian.factory.github.GitHubException;
import com.meterian.factory.github.GitHubRateLimitException;
import com.meterian.factory.github.Issue;
import com.meterian.factory.github.IssueComment;
import com.meterian.factory.ownership.IssueClaim;
import com.meterian.factory.ownership.LostIssues;
import com.meterian.factory.ownership.Ownership;
import com.meterian.factory.ownership.OwnershipLostException;
import com.meterian.factory.state.ActionType;
import com.meterian.factory.state.HandOver;
import com.meterian.factory.state.IssueRecord;
import com.meterian.factory.state.StatusComment;
import com.meterian.factory.workflow.IssueDefinitionFacts;
import com.meterian.factory.workflow.IssueText;
import com.meterian.factory.workflow.IssueWorkflow;
import com.meterian.factory.workflow.MergedIssue;
import com.meterian.factory.workflow.MoveReport;
import com.meterian.factory.workflow.OnAnyStateMoves;
import com.meterian.factory.workflow.PageRequests;
import com.meterian.factory.workflow.StatusPublisher;
import com.meterian.factory.workflow.WorkspaceLayout;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/** Polls GitHub, takes the labeled issues into the queue, and runs each issue in a loop on the executor. */
public final class Worker {

    private static final System.Logger LOGGER = System.getLogger(Worker.class.getName());
    private static final String FACTORY_LABEL = "factory";
    private static final String HUMAN_LABEL = "human";
    private static final String APPROVE_LABEL = "factory:approve";
    private static final String BLOCK_LABEL = "factory:block";
    private static final String BROKEN_RECORD_NOTE =
            "The status record of the factory is broken. The factory does not work on this issue.";
    private static final String DONE_BLOCK_NOTE = "The issue is already done; the block has no effect.";
    private static final String OWNER_BLOCK_NOTE = "The owner blocked the issue.";
    private static final String TAKE_OVER_WAITS_NOTE =
            "The work of the earlier owner waits for a free slot of parallel-issues.";
    private static final Duration EARLY_POLL_DELAY = Duration.ofSeconds(2);

    private final FactoryConfiguration configuration;
    private final GitHubClient gitHub;
    private final IssueWorkflow workflow;
    private final DefinitionVersions definitions;
    private final PhaseStopper phaseStopper;
    private final PageRequests pageRequests;
    private final String workerLogin;
    private final ExecutorService executor;
    private final WorkspaceLayout layout;
    private final StatusPublisher statusPublisher;
    private final OnAnyStateMoves onAnyStateMoves;
    private final RestartPreparation restartPreparation;
    private final EnterRequests enterRequests;
    private final StartAgainRequests startAgainRequests;
    private final LiveRecordCheck liveRecordCheck;
    private final LiveSessions liveSessions;
    private final RateLimitWait rateLimitWait;
    private final IssueClaim issueClaim;
    private final OwnershipRelease ownershipRelease;
    private final OwnershipTakeover ownershipTakeover;
    private final EarlierOwnerRecord earlierOwnerRecord;
    private final BoardUpdates board;
    private final FactorySwitch factorySwitch;
    private final LostIssues lostIssues;
    private final ReleasedIssues releasedIssues = new ReleasedIssues();
    private final CountDownLatch stopSignal = new CountDownLatch(1);
    private final PollWait pollWait;
    private final Set<Integer> issuesInWork = new HashSet<>();
    /** The issues with a block that is requested or in progress. */
    private final Set<Integer> blockedIssues = new HashSet<>();
    /** The issues in work with a request of factory:enter. The worker stops the action, and the loop then enters. */
    private final Set<Integer> enterRequestedIssues = new HashSet<>();
    /** The issues in work that another account owns now. The loop starts no next action, and then releases it. */
    private final Set<Integer> issuesOfAnotherOwner = new HashSet<>();
    /** The issues with a release in progress. The poll neither releases nor starts them again. */
    private final Set<Integer> releasesInProgress = new HashSet<>();
    private final LiveSessionSlots liveSessionSlots = new LiveSessionSlots();
    private final CardSlots cardSlots;
    private final WorkerEvents events;
    private final WorkerMove workerMove;
    private final AtomicBoolean stopReported = new AtomicBoolean();
    private final PollSlotChecks pollSlotChecks = new PollSlotChecks();
    private volatile boolean credentialMismatchFound;
    private boolean labelsEnsured;

    public Worker(FactoryConfiguration configuration, GitHubClient gitHub, IssueWorkflow workflow,
                  DefinitionVersions definitions, PhaseStopper phaseStopper, PageRequests pageRequests,
                  String workerLogin, ExecutorService executor, LiveSessions liveSessions, Clock clock,
                  BoardUpdates board, FactorySwitch factorySwitch, LostIssues lostIssues, WorkerEvents events) {
        this.configuration = configuration;
        this.events = events;
        this.gitHub = gitHub;
        this.workflow = workflow;
        this.definitions = definitions;
        this.phaseStopper = phaseStopper;
        this.pageRequests = pageRequests;
        this.workerLogin = workerLogin;
        this.executor = executor;
        this.board = board;
        this.factorySwitch = factorySwitch;
        this.lostIssues = lostIssues;
        this.layout = new WorkspaceLayout(configuration.workDirectory());
        this.statusPublisher = new StatusPublisher(gitHub, workerLogin, definitions, board, lostIssues);
        this.onAnyStateMoves = new OnAnyStateMoves(definitions, layout, events);
        this.workerMove = new WorkerMove(new MoveReport(events));
        this.restartPreparation = new RestartPreparation(gitHub, layout, definitions, workerMove);
        this.enterRequests = new EnterRequests(gitHub, workflow);
        this.startAgainRequests = new StartAgainRequests(gitHub, statusPublisher, definitions, workerMove);
        this.liveRecordCheck = new LiveRecordCheck(liveSessions, statusPublisher);
        this.liveSessions = liveSessions;
        this.rateLimitWait = new RateLimitWait(clock, events);
        this.issueClaim = new IssueClaim(gitHub, workerLogin);
        this.ownershipRelease = new OwnershipRelease(gitHub, statusPublisher, workerLogin);
        this.ownershipTakeover = new OwnershipTakeover(gitHub, statusPublisher, workerLogin, definitions,
                onAnyStateMoves, workerMove);
        this.earlierOwnerRecord = new EarlierOwnerRecord(gitHub, workerLogin);
        this.cardSlots = new CardSlots(new IssueDefinitionFacts(definitions));
        this.pollWait = new PollWait(configuration.limits().pollInterval(), EARLY_POLL_DELAY);
    }

    /**
     * Reads the definition of the default branch, then the labeled issues once: block requests, intake, and the
     * start of the issues up to the limit. In a wait for the reset of the GitHub rate limit, the poll sends no
     * request. A stopped factory starts no issue, and it leaves a new issue untouched, so that another factory can
     * take it.
     */
    public void pollOnce() {
        if (isStopped() || rateLimitWait.isWaiting()) {
            return;
        }
        pollSlotChecks.startPoll();
        try {
            definitions.refresh();
            verifyEnoughRequestsRemain();
            ensureLabelsOnce();
            List<IssueRecord> startable = new ArrayList<>();
            List<Issue> labeledIssues = readLabeledIssues();
            cardSlots.forgetAllExcept(numbersOf(labeledIssues));
            List<Issue> ownedIssues = claimOwnedIssues(labeledIssues);
            board.recordPoll(ownedIssues);
            Set<Integer> earlierLiveIssues = liveSessionSlots.startPoll();
            checkIssues(ownedIssues, startable);
            checkIssues(pollSlotChecks.finishChecks(), startable);
            liveSessionSlots.finishPoll(earlierLiveIssues, liveSessions::isOpen);
            startIssues(orderForTheSlots(startable, earlierLiveIssues));
        } catch (GitHubException exception) {
            logGitHubFailure("The poll", exception);
        } catch (RuntimeException exception) {
            LOGGER.log(System.Logger.Level.ERROR, "The poll failed.", exception);
        } finally {
            logRequestCounts();
        }
    }

    private void checkIssues(List<Issue> issues, List<IssueRecord> startable) {
        for (Issue issue : issues) {
            verifyEnoughRequestsRemain();
            checkIssue(issue).ifPresent(startable::add);
        }
    }

    /**
     * Fail closed: after a failed check, the worker does not know the slot of each card. So it starts no card that
     * takes a new slot in this poll. The other cards still start.
     */
    private void startIssues(List<IssueRecord> orderedRecords) {
        if (pollSlotChecks.hasAFailedCheck()) {
            LOGGER.log(System.Logger.Level.WARNING, "A check failed in this poll, so the worker takes no new card.");
        }
        for (IssueRecord record : orderedRecords) {
            verifyEnoughRequestsRemain();
            if (factorySwitch.isStopRequested()) {
                continue;
            }
            if (!pollSlotChecks.hasAFailedCheck() || !cardSlots.takesANewSlot(record)) {
                startIssue(record);
            }
        }
    }

    /**
     * The margin of the rate limit stops only the poll: the intake and new work. A running action keeps its
     * requests, so that it can publish its result.
     */
    private void verifyEnoughRequestsRemain() {
        gitHub.findLowRemainingReset().ifPresent(resetTime -> {
            throw new GitHubRateLimitException("Few GitHub requests remain until " + resetTime + ".", resetTime);
        });
    }

    /** The requests of the issue threads between two polls count in the line of the next poll. */
    private void logRequestCounts() {
        LOGGER.log(System.Logger.Level.INFO, PollRequestSummary.describe(gitHub.takeRequestCounts()));
    }

    /** Polls at the poll interval, and after each early poll request, until the worker stops. */
    public void runUntilStopped() throws InterruptedException {
        events.writeWorkerStart(FactoryVersion.current(), configuration.repository().fullName());
        while (!isStopped()) {
            pollOnce();
            pollWait.awaitNextPoll();
        }
    }

    /** The page asks for a poll after an action, so that the worker acts on its label in seconds. */
    public void requestEarlyPoll() {
        pollWait.requestEarlyPoll();
    }

    public boolean isStopped() {
        return stopSignal.getCount() == 0;
    }

    /** Tells if the worker stopped because a claude session did not use the credential of the provider. */
    public boolean foundACredentialMismatch() {
        return credentialMismatchFound;
    }

    /**
     * Stops the worker, ends the phases in work, and waits for the loops. A loop that ends because of the stop
     * writes back the record from before the stopped action, except when the result of the action is final. After
     * the timeout, the loops get an interrupt: an open of a live session in progress then closes its session.
     *
     * @return true when all loops ended in the timeout
     */
    public boolean shutDown(Duration timeout) {
        stopSignal.countDown();
        pollWait.stop();
        phaseStopper.refuseNewWork();
        stopPhasesInWork();
        executor.shutdown();
        try {
            return executor.awaitTermination(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        } finally {
            executor.shutdownNow();
            reportStopOnce();
        }
    }

    /** The main thread and the shutdown hook can both stop the worker. Only the first stop writes the event. */
    private void reportStopOnce() {
        if (stopReported.compareAndSet(false, true)) {
            events.writeWorkerStop(FactoryVersion.current(), configuration.repository().fullName());
        }
    }

    public synchronized Set<Integer> issuesInWork() {
        return Set.copyOf(issuesInWork);
    }

    /** The issues in work, the open live sessions, and the other cards with a slot share the parallel limit. */
    public synchronized int countUsedSlots() {
        return findIssuesWithASlot().size();
    }

    /** The loops and the open live sessions. A stop of the factory waits for them, but not for a card at rest. */
    public synchronized int countRunningWork() {
        return findRunningWork().size();
    }

    /** An issue can be in work and have an open live session at the same time. It counts one time only. */
    private Set<Integer> findRunningWork() {
        Set<Integer> runningWork = new HashSet<>(issuesInWork);
        runningWork.addAll(liveSessionSlots.findIssues());
        return runningWork;
    }

    private Set<Integer> findIssuesWithASlot() {
        Set<Integer> issuesWithASlot = findRunningWork();
        issuesWithASlot.addAll(cardSlots.findIssues());
        return issuesWithASlot;
    }

    /**
     * A card that takes a new slot adds a card to the board, so it counts all the other cards with a slot. Any other
     * card has its slot on the board already, so it counts only the other running work.
     */
    private Set<Integer> findOtherIssuesWithASlot(IssueRecord record) {
        Set<Integer> otherIssuesWithASlot = cardSlots.takesANewSlot(record) ? findIssuesWithASlot()
                : findRunningWork();
        otherIssuesWithASlot.remove(record.issueNumber());
        return otherIssuesWithASlot;
    }

    private void rememberLiveSession(IssueRecord record) {
        if (record.live().isPresent()) {
            liveSessionSlots.remember(record.issueNumber());
        }
    }

    /**
     * An issue that left the queue holds a slot, also after a restart. So an issue back from its live session goes
     * first, then the other started issues, and the queued issues last. The order by issue number stays in each group.
     */
    private static List<IssueRecord> orderForTheSlots(List<IssueRecord> startable, Set<Integer> earlierLiveIssues) {
        return startable.stream()
                .sorted(Comparator.comparing((IssueRecord record) -> !earlierLiveIssues.contains(record.issueNumber()))
                        .thenComparing(Worker::isQueued))
                .toList();
    }

    private static boolean isQueued(IssueRecord record) {
        return ColumnNames.QUEUED.equals(record.column());
    }

    private void ensureLabelsOnce() {
        if (labelsEnsured) {
            return;
        }
        gitHub.ensureLabel(FACTORY_LABEL, "1d76db", "The factory works on this issue.");
        gitHub.ensureLabel(APPROVE_LABEL, "0e8a16", "The owner approves the merge of the factory.");
        gitHub.ensureLabel(BLOCK_LABEL, "b60205", "The owner stops the factory on this issue.");
        gitHub.ensureLabel(EnterRequests.ENTER_LABEL, "fbca04", "The owner enters the session of the agent.");
        gitHub.ensureLabel(StartAgainRequests.START_AGAIN_LABEL, "5319e7",
                "The owner starts a blocked issue again and discards the earlier work.");
        gitHub.ensureLabel(HUMAN_LABEL, "d4c5f9", "A person works on this issue. The factory does not.");
        labelsEnsured = true;
    }

    /** An issue with the label human is for a person only, so the factory does not see it. */
    private List<Issue> readLabeledIssues() {
        return gitHub.openIssuesWithLabel(FACTORY_LABEL).stream()
                .filter(issue -> !isForAHuman(issue))
                .sorted(Comparator.comparingInt(Issue::number))
                .toList();
    }

    private static Set<Integer> numbersOf(List<Issue> issues) {
        return issues.stream().map(Issue::number).collect(Collectors.toSet());
    }

    /** Only the issues that the worker owns stay. The board then shows no card for the other issues. */
    private List<Issue> claimOwnedIssues(List<Issue> labeledIssues) {
        List<Issue> ownedIssues = new ArrayList<>();
        for (Issue issue : labeledIssues) {
            verifyEnoughRequestsRemain();
            checkOneIssue(issue, () -> claimIssue(issue)).ifPresent(ownedIssues::add);
        }
        return ownedIssues;
    }

    private Optional<Issue> claimIssue(Issue issue) {
        if (isNewIssueOfAStoppedFactory(issue)) {
            return Optional.empty();
        }
        Ownership ownership = issueClaim.claim(issue);
        if (ownership == Ownership.OWNED) {
            forgetEarlierLoss(issue.number());
        } else {
            cardSlots.forget(issue.number());
        }
        if (ownership == Ownership.OWNED_BY_ANOTHER) {
            leaveIssueOfAnotherOwner(issue);
        }
        return Optional.of(issue).filter(claimed -> ownership == Ownership.OWNED);
    }

    /** The issue can come back to the factory. A later loss then needs a new release. */
    private void forgetEarlierLoss(int issueNumber) {
        lostIssues.forgetLoss(issueNumber);
        releasedIssues.forgetRelease(issueNumber);
    }

    /**
     * A stopped factory leaves a new issue untouched, so that another factory can take it. It does not assign
     * itself to an issue without assignee, unless the factory works on it or has a record on it.
     */
    private boolean isNewIssueOfAStoppedFactory(Issue issue) {
        return factorySwitch.isStopRequested() && issue.assignees().isEmpty() && !isInWork(issue.number())
                && readRecord(issue.number()).isEmpty();
    }

    /**
     * A running action ends normally, and the loop then releases the issue. A block in progress goes first: a later
     * poll releases the issue. An issue that this run released costs no request.
     */
    private void leaveIssueOfAnotherOwner(Issue issue) {
        lostIssues.rememberLoss(issue.number());
        synchronized (this) {
            if (issuesInWork.contains(issue.number())) {
                issuesOfAnotherOwner.add(issue.number());
                return;
            }
            if (blockedIssues.contains(issue.number()) || releasedIssues.isReleased(issue.number())
                    || !releasesInProgress.add(issue.number())) {
                return;
            }
        }
        writeRelease(issue.number());
    }

    /**
     * An issue that the worker owned before this poll can hold a slot that the worker does not know. An issue in
     * the second check can get a new slot before its check fails, so each failure there counts.
     */
    private void rememberFailedCheck(Issue issue) {
        boolean wasOwnedBeforeThePoll = issue.assignees().stream()
                .anyMatch(assignee -> assignee.equalsIgnoreCase(workerLogin));
        if (wasOwnedBeforeThePoll || pollSlotChecks.areAllCardsChecked()) {
            pollSlotChecks.rememberFailedCheck();
        }
    }

    private Optional<IssueRecord> checkIssue(Issue issue) {
        return checkOneIssue(issue, () -> findStartableRecord(issue));
    }

    /** A failure keeps the issue out of work for this poll only. The next issue still gets its check. */
    private <T> Optional<T> checkOneIssue(Issue issue, Supplier<Optional<T>> check) {
        try {
            return check.get();
        } catch (GitHubRateLimitException exception) {
            throw exception;
        } catch (GitHubException exception) {
            rememberFailedCheck(issue);
            logGitHubFailure("The check of the issue #" + issue.number(), exception);
            return Optional.empty();
        } catch (RuntimeException exception) {
            rememberFailedCheck(issue);
            LOGGER.log(System.Logger.Level.ERROR, "The check of the issue #" + issue.number() + " failed.",
                    exception);
            return Optional.empty();
        }
    }

    /** The factory does not check who added a request label, so each request label counts. */
    private Optional<IssueRecord> findStartableRecord(Issue issue) {
        int issueNumber = issue.number();
        if (isBlockInProgress(issueNumber) || isReleaseInProgress(issueNumber)) {
            return Optional.empty();
        }
        showAcceptedRequests(issue);
        if (issue.labels().contains(BLOCK_LABEL)) {
            requestBlock(issueNumber);
            return Optional.empty();
        }
        if (isInWork(issueNumber)) {
            if (EnterRequests.isRequested(issue)) {
                stopTheActionForTheEnter(issueNumber);
            }
            return Optional.empty();
        }
        List<IssueComment> comments = gitHub.comments(issueNumber);
        if (StatusComment.hasBrokenRecord(comments, workerLogin)) {
            blockBrokenRecord(issue);
            return Optional.empty();
        }
        Optional<IssueRecord> ownRecord = StatusComment.findRecord(comments, workerLogin);
        Optional<IssueRecord> newerRecordOfAnotherOwner = earlierOwnerRecord.findNewerThanOwnRecord(issueNumber,
                comments);
        IssueRecord record;
        if (isTakeOverNeeded(ownRecord, newerRecordOfAnotherOwner)) {
            if (!pollSlotChecks.areAllCardsChecked()) {
                return deferToTheEndOfTheChecks(issue);
            }
            Optional<IssueRecord> takenOver = takeOver(issue, newerRecordOfAnotherOwner.or(() -> ownRecord));
            if (takenOver.isEmpty()) {
                return Optional.empty();
            }
            record = takenOver.get();
        } else if (ownRecord.isEmpty()) {
            return factorySwitch.isStopRequested() ? Optional.empty() : takeIntoQueue(issue);
        } else {
            record = ownRecord.get();
        }
        if (EnterRequests.isRequested(issue) && mayOpenANewSession(record) && !pollSlotChecks.areAllCardsChecked()) {
            return deferToTheEndOfTheChecks(issue);
        }
        IssueRecord afterStartAgain = StartAgainRequests.isRequested(issue)
                ? startAgainRequests.startAgain(issue, record) : record;
        boolean entersNow = EnterRequests.isRequested(issue)
                && (!mayOpenANewSession(afterStartAgain) || mayTakeANewSlot(issueNumber));
        boolean waitsForTheEnter = EnterRequests.isRequested(issue) && !entersNow;
        IssueRecord current = entersNow ? enterRequests.enter(afterStartAgain) : afterStartAgain;
        current = liveRecordCheck.forgetEndedSession(issue, current);
        rememberLiveSession(current);
        cardSlots.remember(current);
        board.recordIssue(issue, current);
        return Optional.of(current).filter(found -> !waitsForTheEnter && workflow.isStartable(issue, found));
    }

    /** The deferral comes before any change, so the second check of the issue does each change one time only. */
    private Optional<IssueRecord> deferToTheEndOfTheChecks(Issue issue) {
        pollSlotChecks.deferToTheEndOfTheChecks(issue);
        return Optional.empty();
    }

    /**
     * Tells if an enter can open a new session on a card without a slot, for example a blocked card. Such an enter
     * waits for a free slot: the label stays, the card does not start, and a later poll enters. A card without an
     * agent session opens no session.
     */
    private boolean mayOpenANewSession(IssueRecord record) {
        return record.agentSessionStarted() && cardSlots.takesANewSlot(record);
    }

    /** Fail closed: after a failed check, the worker does not know all the slots, so no card takes a new slot. */
    private boolean mayTakeANewSlot(int issueNumber) {
        return !pollSlotChecks.hasAFailedCheck() && hasAFreeSlotForANewCard(issueNumber);
    }

    private synchronized boolean hasAFreeSlotForANewCard(int issueNumber) {
        Set<Integer> otherIssuesWithASlot = findIssuesWithASlot();
        otherIssuesWithASlot.remove(issueNumber);
        return isBelowTheLimit(otherIssuesWithASlot);
    }

    private boolean isBelowTheLimit(Set<Integer> otherIssuesWithASlot) {
        return otherIssuesWithASlot.size() < configuration.limits().parallelIssues();
    }

    /**
     * A newer record of another owner makes the own record out of date. Without an own record, a stopped factory
     * leaves the issue untouched, as for an issue without any record.
     */
    private boolean isTakeOverNeeded(Optional<IssueRecord> ownRecord,
                                     Optional<IssueRecord> newerRecordOfAnotherOwner) {
        if (newerRecordOfAnotherOwner.isEmpty()) {
            return isReleased(ownRecord);
        }
        return ownRecord.isPresent() || !factorySwitch.isStopRequested();
    }

    /** The own record of a factory that stopped earlier is out of date, so the factory continues as a new owner. */
    private static boolean isReleased(Optional<IssueRecord> ownRecord) {
        return ownRecord.flatMap(IssueRecord::handOver).filter(handOver -> handOver == HandOver.RELEASED).isPresent();
    }

    /**
     * Fail closed: without a definition version that loads, the worker does not take over the issue. A takeover
     * that gives the card a slot, for example a wait for the approval, waits for a free slot. The next poll tries
     * again.
     */
    private Optional<IssueRecord> takeOver(Issue issue, Optional<IssueRecord> earlierRecord) {
        Optional<IssueRecord> prepared = createQueuedRecord(issue.number())
                .map(queued -> ownershipTakeover.prepare(issue, queued, earlierRecord));
        if (prepared.isEmpty()) {
            return Optional.empty();
        }
        if (cardSlots.holdsASlot(prepared.get()) && !mayTakeANewSlot(issue.number())) {
            showTheTakeOverThatWaits(issue, prepared.get());
            return Optional.empty();
        }
        return Optional.of(ownershipTakeover.publish(issue, prepared.get()));
    }

    /** The board shows the card with a note. GitHub gets no record of the worker before the takeover. */
    private void showTheTakeOverThatWaits(Issue issue, IssueRecord prepared) {
        board.recordIssue(issue, prepared.withNote(TAKE_OVER_WAITS_NOTE));
    }

    /** The card shows the waiting state of a request label also when the label comes from the GitHub app. */
    private void showAcceptedRequests(Issue issue) {
        boolean hasNewRequest = false;
        for (String label : List.of(APPROVE_LABEL, BLOCK_LABEL, EnterRequests.ENTER_LABEL,
                StartAgainRequests.START_AGAIN_LABEL)) {
            if (issue.labels().contains(label)) {
                hasNewRequest |= pageRequests.rememberAcceptedRequest(issue.number(), label);
            }
        }
        if (hasNewRequest) {
            board.recordRequestChange();
        }
    }

    /**
     * The live conversation resumes the session of the agent, so the agent ends first. Each poll with the label
     * stops again: an action that started just after the last stop still ends.
     */
    private void stopTheActionForTheEnter(int issueNumber) {
        rememberEnterRequest(issueNumber);
        phaseStopper.stopRunningWork(issueNumber);
    }

    /** A loop that ended already gets no request: the label stays, and the next poll enters at once. */
    private synchronized void rememberEnterRequest(int issueNumber) {
        if (issuesInWork.contains(issueNumber)) {
            enterRequestedIssues.add(issueNumber);
        }
    }

    /**
     * Fail closed: without a definition version that loads, the worker takes no new issue. The versions log the
     * reason once for each commit, so the intake only waits.
     */
    private Optional<IssueRecord> takeIntoQueue(Issue issue) {
        return createQueuedRecord(issue.number())
                .map(queued -> publishFirstRecord(issue, queued.withIssueTextHash(IssueText.computeHash(issue))));
    }

    /**
     * The new status comment becomes the newest record, so the broken record never counts again. Fail closed:
     * without a current version, the worker does nothing.
     */
    private void blockBrokenRecord(Issue issue) {
        createQueuedRecord(issue.number()).ifPresent(queued ->
                publishFirstRecord(issue, onAnyStateMoves.moveToBlockTarget(queued, BROKEN_RECORD_NOTE)));
    }

    /** A new record in the start state of the current version. Empty without a current version. */
    private Optional<IssueRecord> createQueuedRecord(int issueNumber) {
        Optional<DefinitionVersion> version = definitions.current();
        return version.map(current -> IssueRecord.queued(issueNumber, current.definition().workflow().start())
                .withDefinitionCommit(current.commit()));
    }

    /** There is no record before the first record, so the publisher always writes it. */
    private IssueRecord publishFirstRecord(Issue issue, IssueRecord record) {
        return statusPublisher.publish(issue, null, record);
    }

    private void startIssue(IssueRecord record) {
        int issueNumber = record.issueNumber();
        if (!reserveSlot(record)) {
            return;
        }
        IssueRecord start;
        try {
            start = restartPreparation.prepare(record);
        } catch (GitHubException exception) {
            logGitHubFailure("The restart of the issue #" + issueNumber, exception);
            releaseIssue(record, Optional.empty());
            return;
        }
        try {
            executor.submit(() -> runIssue(start));
        } catch (RejectedExecutionException exception) {
            LOGGER.log(System.Logger.Level.WARNING, "The executor refuses the issue #{0}.", issueNumber);
            releaseIssue(start, Optional.empty());
        }
    }

    private synchronized boolean reserveSlot(IssueRecord record) {
        int issueNumber = record.issueNumber();
        boolean hasFreeSlot = isBelowTheLimit(findOtherIssuesWithASlot(record));
        return hasFreeSlot && !isStopped() && !blockedIssues.contains(issueNumber)
                && !releasesInProgress.contains(issueNumber) && issuesInWork.add(issueNumber);
    }

    private synchronized boolean isInWork(int issueNumber) {
        return issuesInWork.contains(issueNumber);
    }

    private synchronized boolean isBlockInProgress(int issueNumber) {
        return blockedIssues.contains(issueNumber);
    }

    private synchronized boolean isReleaseInProgress(int issueNumber) {
        return releasesInProgress.contains(issueNumber);
    }

    /**
     * Keeps the result of an action only when no block and no stop came during the action, or when it is final. A
     * request of factory:enter stops the current action: an action that did not end loses its result and its
     * entry, and the loop enters on the record that it kept. A stop of the factory interrupts no action: the loop
     * ends after the action and the publish of its record.
     */
    private void runIssue(IssueRecord start) {
        int issueNumber = start.issueNumber();
        IssueRecord kept = start;
        Optional<IssueRecord> interruptedResult = Optional.empty();
        try {
            while (mayContinue(issueNumber) && !isEnterRequested(issueNumber) && !factorySwitch.isStopRequested()) {
                Issue issue = gitHub.issue(issueNumber);
                if (isForAHuman(issue) || !isOwnedBeforeTheAction(issue)) {
                    break;
                }
                IssueRecord next = workflow.advance(kept);
                if (!mayContinue(issueNumber) && !FinalActionResult.isFinal(kept, next)) {
                    interruptedResult = Optional.of(next);
                    break;
                }
                if (isStoppedForTheEnter(issueNumber, kept, next)) {
                    interruptedResult = Optional.of(next);
                    break;
                }
                if (next.equals(kept)) {
                    break;
                }
                kept = next;
                withdrawBlockOfAMergedIssue(issueNumber, kept);
                if (IssueWorkflow.isAtRest(kept)) {
                    break;
                }
            }
            if (mayContinue(issueNumber) && isEnterRequested(issueNumber)) {
                kept = enterRequests.enter(RecordFacts.keep(kept, interruptedResult));
                interruptedResult = Optional.empty();
            }
        } catch (CredentialMismatchException exception) {
            stopOnCredentialMismatch(issueNumber, exception);
        } catch (OwnershipLostException exception) {
            rememberIssueOfAnotherOwner(issueNumber);
        } catch (GitHubException exception) {
            logGitHubFailure("The action of the issue #" + issueNumber, exception);
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
        return !isStopped() && !blockedIssues.contains(issueNumber) && !issuesOfAnotherOwner.contains(issueNumber);
    }

    private static boolean isForAHuman(Issue issue) {
        return issue.labels().contains(HUMAN_LABEL);
    }

    /** The assignee can change between two polls, so each action checks the owner first. */
    private boolean isOwnedBeforeTheAction(Issue issue) {
        Ownership ownership = issueClaim.claim(issue);
        if (ownership == Ownership.OWNED) {
            forgetEarlierLoss(issue.number());
        } else if (ownership == Ownership.OWNED_BY_ANOTHER) {
            rememberIssueOfAnotherOwner(issue.number());
        }
        return ownership == Ownership.OWNED;
    }

    /** The guard refused a change on GitHub. This is not a failure, so the issue gets no block. */
    private synchronized void rememberIssueOfAnotherOwner(int issueNumber) {
        lostIssues.rememberLoss(issueNumber);
        issuesOfAnotherOwner.add(issueNumber);
    }

    private synchronized boolean isEnterRequested(int issueNumber) {
        return enterRequestedIssues.contains(issueNumber);
    }

    /** An action that ended moves the issue: only an action that did not end stays in its state. */
    private boolean isStoppedForTheEnter(int issueNumber, IssueRecord kept, IssueRecord next) {
        return isEnterRequested(issueNumber) && next.state().equals(kept.state())
                && !FinalActionResult.isFinal(kept, next);
    }

    /**
     * A record in the finish state is final: a merged change cannot be blocked, so the block request goes away and
     * the issue continues to the end of the finish action. The caller keeps the record before this call, so a
     * GitHub failure here cannot lose it.
     */
    private void withdrawBlockOfAMergedIssue(int issueNumber, IssueRecord kept) {
        if (!isInTheFinishState(kept) || !isBlockInProgress(issueNumber)) {
            return;
        }
        gitHub.removeLabel(issueNumber, BLOCK_LABEL);
        synchronized (this) {
            blockedIssues.remove(issueNumber);
        }
    }

    /** Exactly one of requestBlock and releaseIssue sees the other, so the block is written once. */
    private void requestBlock(int issueNumber) {
        boolean isRunning;
        synchronized (this) {
            if (!blockedIssues.add(issueNumber)) {
                return;
            }
            cardSlots.forget(issueNumber);
            isRunning = issuesInWork.contains(issueNumber);
        }
        phaseStopper.stopIssue(issueNumber);
        if (!isRunning) {
            finishBlock(issueNumber, Optional.empty(), Optional.empty());
        }
    }

    /** A stopping factory shows "Stopped" at once when its last issue ends. */
    private void releaseIssue(IssueRecord kept, Optional<IssueRecord> interruptedResult) {
        boolean isBlocked;
        boolean isOwnedByAnother;
        synchronized (this) {
            issuesInWork.remove(kept.issueNumber());
            enterRequestedIssues.remove(kept.issueNumber());
            isBlocked = blockedIssues.contains(kept.issueNumber());
            isOwnedByAnother = issuesOfAnotherOwner.remove(kept.issueNumber()) && !isBlocked;
            if (!isBlocked) {
                // A session that the loop opened holds the slot of the loop at once, before the next poll.
                rememberLiveSession(kept);
            }
            if (!isBlocked && !isOwnedByAnother) {
                // A card that waits for you holds the slot of the loop at once, before the next poll.
                cardSlots.remember(kept);
            }
            if (isOwnedByAnother) {
                // The poll sees no gap between the end of the work and the start of the release.
                releasesInProgress.add(kept.issueNumber());
            }
        }
        if (factorySwitch.isStopRequested()) {
            board.recordRequestChange();
        }
        if (isBlocked) {
            // A block during the enter of the loop came before the session was open.
            phaseStopper.closeLiveSession(kept.issueNumber());
            finishBlock(kept.issueNumber(), Optional.of(kept), interruptedResult);
        } else if (isOwnedByAnother) {
            writeRelease(kept.issueNumber());
        } else if (interruptedResult.isPresent()) {
            restoreKeptRecord(kept, interruptedResult.get());
        }
    }

    /**
     * Moves the record to the block target. The record of a loop is the last record that the loop kept; without a
     * loop, the record on GitHub is the last result. The block closed the live session, so the record drops it.
     * An older continue request goes away, so only a new request of the owner continues the issue.
     * Without a record and without a current version, the label stays for the next poll.
     */
    private void finishBlock(int issueNumber, Optional<IssueRecord> keptByLoop,
                             Optional<IssueRecord> interruptedResult) {
        try {
            Optional<IssueRecord> onGitHub = readRecord(issueNumber);
            if (onGitHub.filter(Worker::isInTheFinishState).isPresent()) {
                noteBlockWithoutEffect(onGitHub.get(), onGitHub);
                return;
            }
            Optional<IssueRecord> laterRecord = interruptedResult.or(() -> onGitHub);
            Optional<IssueRecord> base = keptByLoop.map(kept -> RecordFacts.keep(kept, laterRecord))
                    .or(() -> onGitHub).or(() -> createQueuedRecord(issueNumber));
            if (base.isEmpty()) {
                return;
            }
            if (isMergedOnGitHub(base.get())) {
                noteBlockWithoutEffect(moveToFinishState(base.get()), onGitHub);
                return;
            }
            IssueRecord blocked = onAnyStateMoves.moveToBlockTarget(base.get(), OWNER_BLOCK_NOTE).withoutLive();
            statusPublisher.publish(gitHub.issue(issueNumber), onGitHub.orElse(null), blocked);
            liveSessions.clearContinueRequest(issueNumber);
            gitHub.removeLabel(issueNumber, BLOCK_LABEL);
        } catch (GitHubException exception) {
            logGitHubFailure("The block of the issue #" + issueNumber, exception);
        } finally {
            synchronized (this) {
                blockedIssues.remove(issueNumber);
            }
        }
    }

    /**
     * A record in the finish state is final. The block only gets a note, and its label goes away. A record in the
     * finish state that still has the label factory runs its finish action at the next poll.
     */
    private void noteBlockWithoutEffect(IssueRecord done, Optional<IssueRecord> onGitHub) {
        int issueNumber = done.issueNumber();
        statusPublisher.publish(gitHub.issue(issueNumber), onGitHub.orElse(null), done.withNote(DONE_BLOCK_NOTE));
        gitHub.removeLabel(issueNumber, BLOCK_LABEL);
    }

    private IssueRecord moveToFinishState(IssueRecord record) {
        WorkflowDefinition workflow = definitions.definitionOf(record).workflow();
        return workerMove.moveTo(workflow, record, workflow.finishState());
    }

    private StateDefinition finishStateOf(IssueRecord record) {
        return definitions.definitionOf(record).workflow().finishState();
    }

    /** The record can miss the finish state when the publish after the merge failed. GitHub knows the truth. */
    private boolean isMergedOnGitHub(IssueRecord record) {
        return isInTheFinishState(record) || record.pullRequestNumber().map(gitHub::isMerged).orElse(false);
    }

    private static boolean isInTheFinishState(IssueRecord record) {
        return record.action().equals(Optional.of(ActionType.FINISH));
    }

    /**
     * The caller marks the release in progress first, so the loop and the poll write one release for one change
     * of owner. The release writes over the record on GitHub, so an interrupted result needs no restore.
     */
    private void writeRelease(int issueNumber) {
        try {
            ownershipRelease.release(issueNumber);
            releasedIssues.rememberRelease(issueNumber);
        } catch (GitHubException exception) {
            logGitHubFailure("The release of the issue #" + issueNumber, exception);
        } finally {
            synchronized (this) {
                releasesInProgress.remove(issueNumber);
            }
        }
    }

    /** The stop came during an action, so the record from before the action goes back to GitHub. */
    private void restoreKeptRecord(IssueRecord kept, IssueRecord interruptedResult) {
        int issueNumber = kept.issueNumber();
        try {
            IssueRecord restored = RecordFacts.keep(kept, Optional.of(interruptedResult));
            statusPublisher.publish(gitHub.issue(issueNumber), interruptedResult, restored);
        } catch (GitHubException exception) {
            logGitHubFailure("The restore of the issue #" + issueNumber, exception);
        }
    }

    /**
     * Fail closed: the issue moves to the block target with the cause, and the worker keeps running. An older
     * continue request goes away, so only a new request of the owner continues the issue. A merged issue goes to
     * the finish state with a note; the label factory stays on it, so the next poll runs the finish action again.
     */
    private void blockOnFailure(IssueRecord kept, RuntimeException exception) {
        int issueNumber = kept.issueNumber();
        LOGGER.log(System.Logger.Level.ERROR, "The issue #" + issueNumber + " failed.", exception);
        String note = "An unexpected failure stopped the issue: " + exception.getClass().getName()
                + ": " + exception.getMessage();
        try {
            Optional<IssueRecord> onGitHub = readRecord(issueNumber);
            IssueRecord base = onGitHub.orElse(kept);
            if (isMergedOnGitHub(base)) {
                IssueRecord noted = MergedIssue.noteFailureAfterTheMerge(base, finishStateOf(base), exception);
                statusPublisher.publish(gitHub.issue(issueNumber), onGitHub.orElse(null), noted);
                return;
            }
            IssueRecord blocked = onAnyStateMoves.moveToBlockTarget(closeTheLiveSession(base), note);
            statusPublisher.publish(gitHub.issue(issueNumber), onGitHub.orElse(null), blocked);
            liveSessions.clearContinueRequest(issueNumber);
        } catch (GitHubException publishException) {
            logGitHubFailure("The block of the issue #" + issueNumber, publishException);
        }
    }

    /** The block target has no conversation: the record must never know less than tmux. */
    private IssueRecord closeTheLiveSession(IssueRecord record) {
        if (record.live().isEmpty()) {
            return record;
        }
        phaseStopper.closeLiveSession(record.issueNumber());
        return record.withoutLive();
    }

    /** Fail closed: the worker stops and ends the phases that run. */
    private void stopOnCredentialMismatch(int issueNumber, CredentialMismatchException exception) {
        LOGGER.log(System.Logger.Level.ERROR, "The issue #{0} found a credential mismatch: {1}", issueNumber,
                exception.getMessage());
        credentialMismatchFound = true;
        stopSignal.countDown();
        pollWait.stop();
        phaseStopper.refuseNewWork();
        stopPhasesInWork();
    }

    /** A stop of the worker never closes a live session. */
    private void stopPhasesInWork() {
        issuesInWork().forEach(phaseStopper::stopRunningWork);
    }

    private Optional<IssueRecord> readRecord(int issueNumber) {
        return StatusComment.findRecord(gitHub.comments(issueNumber), workerLogin);
    }

    /**
     * The message of a GitHubException never holds the token. The rate limit is not a failure of the action:
     * the action runs again after the reset.
     */
    private void logGitHubFailure(String work, GitHubException exception) {
        if (exception instanceof GitHubRateLimitException rateLimit) {
            rateLimitWait.waitUntil(rateLimit.resetTime());
            return;
        }
        LOGGER.log(System.Logger.Level.WARNING, "{0} failed: {1}", work, exception.getMessage());
    }
}
