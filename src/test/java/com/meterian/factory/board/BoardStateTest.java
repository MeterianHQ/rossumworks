package com.meterian.factory.board;

import static org.assertj.core.api.Assertions.assertThat;

import com.meterian.factory.github.Issue;
import com.meterian.factory.state.IssueRecord;
import com.meterian.factory.testing.RecordStates;
import com.meterian.factory.testing.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class BoardStateTest {

    private static final Instant START = Instant.parse("2026-11-01T10:00:00Z");

    private final MutableClock clock = new MutableClock(START);
    private final BoardState board = new BoardState(clock);

    private static Issue issue(int number, String title) {
        return new Issue(number, title, "body", Set.of("factory"));
    }

    /** A record in the state of the built-in workflow; the start state keeps the column queued. */
    private static IssueRecord record(int number, String stateName) {
        IssueRecord queued = RecordStates.queued(number);
        return stateName.equals(queued.state()) ? queued : RecordStates.move(queued, stateName);
    }

    @Test
    void aRequestChangeWakesTheWaitingPage() throws InterruptedException {
        long knownVersion = board.snapshot().version();

        board.recordRequestChange();

        assertThat(board.awaitChange(knownVersion, Duration.ZERO)).isEqualTo(knownVersion + 1);
    }

    @Test
    void aPollSetsTheCheckTimeAndTheTitles() {
        board.recordIssue(issue(7, "Old title"), record(7, "prepare"));
        clock.advance(Duration.ofMinutes(1));

        board.recordPoll(List.of(new Issue(7, "New title", "body", Set.of("factory", "bug"))));

        BoardSnapshot snapshot = board.snapshot();
        assertThat(snapshot.workerStartedAt()).isEqualTo(START);
        assertThat(snapshot.lastCheckAt()).contains(START.plus(Duration.ofMinutes(1)));
        assertThat(snapshot.cards()).hasSize(1);
        assertThat(snapshot.cards().get(0).title()).isEqualTo("New title");
        assertThat(snapshot.cards().get(0).labels()).containsExactlyInAnyOrder("factory", "bug");
    }

    @Test
    void aRecordMakesACard() {
        IssueRecord record = record(7, "implement");

        board.recordIssue(issue(7, "Title"), record);

        IssueCard card = board.snapshot().cards().get(0);
        assertThat(card.issueNumber()).isEqualTo(7);
        assertThat(card.title()).isEqualTo("Title");
        assertThat(card.record()).isEqualTo(record);
        assertThat(card.doneAt()).isEmpty();
    }

    @Test
    void anIssueThatLeavesTheListGoesAwayUnlessItIsDone() {
        board.recordIssue(issue(1, "Open"), record(1, "implement"));
        board.recordIssue(issue(2, "Finished"), record(2, "done"));

        board.recordPoll(List.of());

        assertThat(board.snapshot().cards()).extracting(IssueCard::issueNumber).containsExactly(2);
    }

    @Test
    void theDoneTimeIsTheFirstTimeTheWorkerSawDone() {
        Instant firstTime = clock.instant();
        board.recordIssue(issue(5, "Title"), record(5, "done"));
        clock.advance(Duration.ofHours(1));
        board.recordIssue(issue(5, "Title"), record(5, "done"));

        assertThat(board.snapshot().cards().get(0).doneAt()).contains(firstTime);
    }

    @Test
    void onlyTheNewest20DoneCardsStay() {
        for (int number = 1; number <= 25; number++) {
            board.recordIssue(issue(number, "Issue " + number), record(number, "done"));
            clock.advance(Duration.ofMinutes(1));
        }

        List<IssueCard> cards = board.snapshot().cards();

        assertThat(cards).hasSize(20);
        assertThat(cards).extracting(IssueCard::issueNumber).doesNotContain(1, 2, 3, 4, 5).contains(25);
        assertThat(cards.get(0).issueNumber()).isEqualTo(25);
    }

    @Test
    void aLabelFromThePageShowsAtOnce() {
        board.recordIssue(issue(7, "Title"), record(7, "waits-for-approval"));
        long before = board.snapshot().version();

        board.recordLabelAdded(7, "factory:approve");

        BoardSnapshot snapshot = board.snapshot();
        assertThat(snapshot.cards().get(0).labels()).contains("factory:approve");
        assertThat(snapshot.version()).isGreaterThan(before);
    }

    @Test
    void aLabelFromThePageStaysWhenThePollDoesNotShowItYet() {
        board.recordIssue(issue(7, "Title"), record(7, "waits-for-approval"));
        board.recordLabelAdded(7, "factory:approve");

        board.recordPoll(List.of(issue(7, "Title")));

        assertThat(board.snapshot().cards().get(0).labels()).contains("factory:approve");
    }

    @Test
    void aLabelFromThePageFollowsThePollsAfterAPollShowsIt() {
        board.recordIssue(issue(7, "Title"), record(7, "waits-for-approval"));
        board.recordLabelAdded(7, "factory:approve");
        board.recordPoll(List.of(new Issue(7, "Title", "body", Set.of("factory", "factory:approve"))));

        board.recordPoll(List.of(issue(7, "Title")));

        assertThat(board.snapshot().cards().get(0).labels()).doesNotContain("factory:approve");
    }

    @Test
    void aLabelFromThePageThatNoPollShowsGoesAwayAfterTwoPolls() {
        board.recordIssue(issue(7, "Title"), record(7, "waits-for-approval"));
        board.recordLabelAdded(7, "factory:approve");
        board.recordPoll(List.of(issue(7, "Title")));

        board.recordPoll(List.of(issue(7, "Title")));

        assertThat(board.snapshot().cards().get(0).labels()).doesNotContain("factory:approve");
    }

    @Test
    void aRecordKeepsTheLabelsAndTheTitleOfAnExistingCard() {
        board.recordIssue(issue(7, "Title"), record(7, "waits-for-approval"));
        board.recordLabelAdded(7, "factory:approve");

        board.recordIssue(issue(7, "Old title"), record(7, "review"));

        IssueCard card = board.snapshot().cards().get(0);
        assertThat(card.labels()).contains("factory:approve");
        assertThat(card.title()).isEqualTo("Title");
        assertThat(card.record().column()).isEqualTo("in-review");
    }

    @Test
    void awaitChangeReturnsAtOnceForAnOldVersion()throws InterruptedException {
        board.recordPoll(List.of());
        long current = board.snapshot().version();

        long result = board.awaitChange(current - 1, Duration.ofSeconds(30));

        assertThat(result).isEqualTo(current);
    }

    @Test
    void awaitChangeWakesUpOnAChange() throws InterruptedException {
        long known = board.snapshot().version();
        Thread writer = new Thread(() -> {
            try {
                Thread.sleep(100);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
            board.recordPoll(List.of());
        });
        long startNanos = System.nanoTime();
        writer.start();

        long result = board.awaitChange(known, Duration.ofSeconds(30));

        assertThat(result).isGreaterThan(known);
        assertThat(Duration.ofNanos(System.nanoTime() - startNanos)).isLessThan(Duration.ofSeconds(2));
    }

    @Test
    void awaitChangeEndsAfterTheTimeout() throws InterruptedException {
        long known = board.snapshot().version();

        long result = board.awaitChange(known, Duration.ofMillis(200));

        assertThat(result).isEqualTo(known);
    }
}
