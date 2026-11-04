package com.meterian.factory.board;

import com.meterian.factory.github.Issue;
import com.meterian.factory.state.IssueRecord;
import com.meterian.factory.state.IssueStatus;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * The board in memory. Each change adds 1 to the version and wakes the threads that wait.
 * The DONE cards are lost at a restart, because a list of closed issues costs requests.
 * The merge date is the time when the worker saw DONE.
 */
public final class BoardState implements BoardUpdates {

    static final int MAXIMUM_DONE_CARDS = 20;
    /** A label from the page stays on the card for this many polls when no poll shows it. */
    private static final int POLLS_FOR_A_PAGE_LABEL = 2;

    private final Clock clock;
    private final Instant workerStartedAt;
    private final Map<Integer, IssueCard> cardsByNumber = new LinkedHashMap<>();
    /** The labels that the page added and that no poll showed yet, with the polls that remain for each. */
    private final Map<Integer, Map<String, Integer>> pageLabelsByNumber = new HashMap<>();
    private Optional<Instant> lastCheckAt = Optional.empty();
    private long version;

    public BoardState(Clock clock) {
        this.clock = clock;
        this.workerStartedAt = clock.instant();
    }

    @Override
    public synchronized void recordPoll(List<Issue> labeledIssues) {
        lastCheckAt = Optional.of(clock.instant());
        Map<Integer, Issue> issuesByNumber = new LinkedHashMap<>();
        labeledIssues.forEach(issue -> issuesByNumber.put(issue.number(), issue));
        cardsByNumber.values().removeIf(card -> isGone(card, issuesByNumber));
        pageLabelsByNumber.keySet().retainAll(issuesByNumber.keySet());
        issuesByNumber.values().forEach(this::refreshCard);
        markChanged();
    }

    /** The labels and the title come from the poll. The issue gives them only to a new card. */
    @Override
    public synchronized void recordIssue(Issue issue, IssueRecord record) {
        Optional<Instant> doneAt = findDoneTime(issue.number(), record);
        IssueCard existing = cardsByNumber.get(issue.number());
        String title = existing == null ? issue.title() : existing.title();
        Set<String> labels = existing == null ? issue.labels() : existing.labels();
        cardsByNumber.put(issue.number(), new IssueCard(issue.number(), title, labels, record, doneAt));
        removeOldDoneCards();
        markChanged();
    }

    @Override
    public synchronized void recordRequestChange() {
        markChanged();
    }

    /** Shows the label at once, so the button of the action goes away before the next poll. */
    public synchronized void recordLabelAdded(int issueNumber, String label) {
        IssueCard card = cardsByNumber.get(issueNumber);
        if (card == null) {
            return;
        }
        pageLabelsByNumber.computeIfAbsent(issueNumber, number -> new HashMap<>()).put(label, POLLS_FOR_A_PAGE_LABEL);
        Set<String> labels = new HashSet<>(card.labels());
        labels.add(label);
        cardsByNumber.put(issueNumber,
                new IssueCard(issueNumber, card.title(), labels, card.record(), card.doneAt()));
        markChanged();
    }

    public synchronized BoardSnapshot snapshot() {
        List<IssueCard> openCards = cardsByNumber.values().stream().filter(card -> card.doneAt().isEmpty()).toList();
        Stream<IssueCard> doneCards = cardsByNumber.values().stream()
                .filter(card -> card.doneAt().isPresent())
                .sorted(Comparator.comparing((IssueCard card) -> card.doneAt().get()).reversed());
        return new BoardSnapshot(workerStartedAt, lastCheckAt,
                Stream.concat(openCards.stream(), doneCards).toList(), version);
    }

    /** Returns at once when the version is new. Otherwise waits for a change or for the timeout. */
    public synchronized long awaitChange(long knownVersion, Duration timeout) throws InterruptedException {
        long deadlineNanos = System.nanoTime() + timeout.toNanos();
        long remainingNanos = timeout.toNanos();
        while (version == knownVersion && remainingNanos > 0) {
            TimeUnit.NANOSECONDS.timedWait(this, remainingNanos);
            remainingNanos = deadlineNanos - System.nanoTime();
        }
        return version;
    }

    private boolean isGone(IssueCard card, Map<Integer, Issue> issuesByNumber) {
        return !issuesByNumber.containsKey(card.issueNumber()) && card.doneAt().isEmpty();
    }

    private void refreshCard(Issue issue) {
        IssueCard card = cardsByNumber.get(issue.number());
        if (card != null) {
            Set<String> labels = new HashSet<>(issue.labels());
            labels.addAll(keepPageLabelsNotShownYet(issue));
            cardsByNumber.put(issue.number(),
                    new IssueCard(issue.number(), issue.title(), labels, card.record(), card.doneAt()));
        }
    }

    /**
     * GitHub can show a new label in the list of issues a few seconds late. So a label from the page stays on the
     * card until a poll shows it, or for {@link #POLLS_FOR_A_PAGE_LABEL} polls.
     */
    private Set<String> keepPageLabelsNotShownYet(Issue issue) {
        Map<String, Integer> pageLabels = pageLabelsByNumber.getOrDefault(issue.number(), new HashMap<>());
        pageLabels.keySet().removeAll(issue.labels());
        pageLabels.replaceAll((label, remainingPolls) -> remainingPolls - 1);
        pageLabels.values().removeIf(remainingPolls -> remainingPolls <= 0);
        if (pageLabels.isEmpty()) {
            pageLabelsByNumber.remove(issue.number());
        }
        return Set.copyOf(pageLabels.keySet());
    }

    private Optional<Instant> findDoneTime(int issueNumber, IssueRecord record) {
        if (record.status() != IssueStatus.DONE) {
            return Optional.empty();
        }
        IssueCard existing = cardsByNumber.get(issueNumber);
        if (existing != null && existing.doneAt().isPresent()) {
            return existing.doneAt();
        }
        return Optional.of(clock.instant());
    }

    private void removeOldDoneCards() {
        cardsByNumber.values().stream()
                .filter(card -> card.doneAt().isPresent())
                .sorted(Comparator.comparing((IssueCard card) -> card.doneAt().get()).reversed())
                .skip(MAXIMUM_DONE_CARDS)
                .map(IssueCard::issueNumber)
                .toList()
                .forEach(cardsByNumber::remove);
    }

    private void markChanged() {
        version++;
        notifyAll();
    }
}
