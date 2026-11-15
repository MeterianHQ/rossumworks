package com.meterian.factory.merge;

import java.util.List;
import static com.meterian.factory.testing.TemporaryRepository.commitFile;
import static com.meterian.factory.testing.TemporaryRepository.createBare;
import static com.meterian.factory.testing.TemporaryRepository.createClone;
import static com.meterian.factory.testing.TemporaryRepository.run;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.meterian.factory.build.BuildRunner;
import com.meterian.factory.build.BuildSettings;
import com.meterian.factory.git.Gatekeeper;
import com.meterian.factory.git.Git;
import com.meterian.factory.git.ProcessGit;
import com.meterian.factory.github.GitHubException;
import com.meterian.factory.github.MergeOutcome;
import com.meterian.factory.github.PullRequest;
import com.meterian.factory.ownership.OwnershipGuard;
import com.meterian.factory.ownership.OwnershipLostException;
import com.meterian.factory.state.IssueRecord;
import com.meterian.factory.testing.RecordStates;
import com.meterian.factory.state.RiskLevel;
import com.meterian.factory.state.Verdict;
import com.meterian.factory.testing.FakeGitHubClient;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MergerTest {

    private static final String BRANCH = "factory/issue-7";

    @TempDir
    Path temporaryDirectory;

    private final Git git = new ProcessGit();
    private final FakeGitHubClient gitHub = new FakeGitHubClient();
    private Path origin;
    private Path clone;
    private Path worktree;
    private Path gatekeeper;
    private Path github;
    private Path buildLog;
    private PullRequest pullRequest;
    private Merger merger;

    @BeforeEach
    void createRepositories() {
        origin = createBare(temporaryDirectory.resolve("origin.git"));
        clone = createClone(origin, temporaryDirectory.resolve("clone"));
        commitFile(clone, "m.txt", "1\n2\n3\n4\n5\n6\n7\n8\n", "Add m");
        run(clone, "push", "-q", "origin", "main");
        github = createBare(temporaryDirectory.resolve("github.git"));
        gatekeeper = temporaryDirectory.resolve("gatekeeper.git");
        Gatekeeper.ensure(gatekeeper, github.toString());
        git.setRemote(clone, "gatekeeper", gatekeeper.toString());
        worktree = temporaryDirectory.resolve("worktree");
        git.addWorktree(clone, worktree, BRANCH, "origin/main");
        buildLog = temporaryDirectory.resolve("builds.txt");
        merger = createMerger("echo ran >> " + buildLog, Map.of());
        pullRequest = gitHub.createPullRequest(BRANCH, "main", "Title", "Body");
        gitHub.squashMergeInto(clone);
        gitHub.readPullRequestHeadsFrom(github);
    }

    private IssueRecord reviewedRecord() {
        return reviewedRecord(7, worktree, pullRequest);
    }

    private IssueRecord reviewedRecord(int issueNumber, Path issueWorktree, PullRequest issuePullRequest) {
        IssueRecord record = RecordStates.queued(issueNumber).withPullRequest(issuePullRequest.number())
                .withRisk(RiskLevel.LOW, true, List.of())
                .withReviewedTree(git.treeHash(issueWorktree), git.patchId(issueWorktree, "origin/main"))
                .withReviewedHeadCommit(git.headCommit(issueWorktree))
                .withBuiltTree(git.treeHash(issueWorktree));
        for (String reviewName : RecordStates.REVIEW_NAMES) {
            record = record.withReview(reviewName, Verdict.APPROVE);
        }
        return record;
    }

    /** A clone of a person who pushes to the merge target while the factory merges. */
    private Path createPersonClone() {
        Path person = temporaryDirectory.resolve("person");
        run(temporaryDirectory, "clone", "-q", origin.toString(), person.toString());
        run(person, "config", "user.name", "A Person");
        run(person, "config", "user.email", "person@example.com");
        return person;
    }

    /** The build pushes a new commit of a person to main. With a marker file, only the first build does it. */
    private Merger createMergerWithABuildThatMovesTheBase(Path person, boolean onlyOnce) {
        Path marker = temporaryDirectory.resolve("moved");
        String movesTheBase = "cd " + person + " && git pull -q --ff-only origin main"
                + " && git commit -q --allow-empty -m Person && git push -q origin main";
        String command = "echo ran >> " + buildLog + "; "
                + (onlyOnce ? "if [ ! -e " + marker + " ]; then touch " + marker + "; " + movesTheBase + "; fi"
                        : movesTheBase);
        return createMerger(command, Map.of("GIT_CONFIG_GLOBAL", "/dev/null", "GIT_CONFIG_NOSYSTEM", "1"));
    }

    private static void waitUntilBlockedOrEnded(Thread thread) {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (thread.getState() != Thread.State.BLOCKED && thread.getState() != Thread.State.TERMINATED) {
            assertThat(System.nanoTime()).isLessThan(deadline);
            try {
                Thread.sleep(10);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void moveTheBase(String fileName, String content) {
        commitFile(clone, fileName, content, "Move the base");
        run(clone, "push", "-q", "origin", "main");
    }

    private long buildCount() throws IOException {
        return Files.exists(buildLog) ? Files.readAllLines(buildLog).size() : 0;
    }

    @Test
    void mergesWhenTheBaseDidNotMove() throws IOException {
        commitFile(worktree, "b.txt", "feature\n", "Feature");
        IssueRecord record = reviewedRecord();

        MergeResult result = merger.merge(worktree, record);

        assertThat(result).isEqualTo(new MergeResult.Merged(record.builtTreeHash().orElseThrow()));
        assertThat(gitHub.squashMergeCalls()).containsExactly(pullRequest.number());
        assertThat(gitHub.squashMergeShas()).containsExactly(run(worktree, "rev-parse", "HEAD"));
        assertThat(buildCount()).isZero();
        assertThat(run(gatekeeper, "rev-parse", "refs/heads/" + BRANCH)).isEqualTo(run(worktree, "rev-parse", "HEAD"));
    }

    @Test
    void mergerDoesNotMergeWhenAnotherAccountOwnsTheIssue() {
        commitFile(worktree, "b.txt", "feature\n", "Feature");
        IssueRecord record = reviewedRecord();
        OwnershipGuard anotherAccountOwnsTheIssue = issueNumber -> {
            throw new OwnershipLostException(issueNumber);
        };
        Merger refusedMerger = createMerger("true", Map.of(), anotherAccountOwnsTheIssue);

        assertThatThrownBy(() -> refusedMerger.merge(worktree, record)).isInstanceOf(OwnershipLostException.class);

        assertThat(gitHub.squashMergeCalls()).isEmpty();
    }

    @Test
    void mergerDoesNotSquashMergeWhenTheOwnerChangesAfterThePush() {
        commitFile(worktree, "b.txt", "feature\n", "Feature");
        IssueRecord record = reviewedRecord();
        AtomicInteger ownershipChecks = new AtomicInteger();
        OwnershipGuard ownerChangesAfterThePush = issueNumber -> {
            if (ownershipChecks.incrementAndGet() > 1) {
                throw new OwnershipLostException(issueNumber);
            }
        };
        Merger refusedMerger = createMerger("true", Map.of(), ownerChangesAfterThePush);

        assertThatThrownBy(() -> refusedMerger.merge(worktree, record)).isInstanceOf(OwnershipLostException.class);

        assertThat(ownershipChecks).hasValue(2);
        assertThat(gitHub.squashMergeCalls()).isEmpty();
    }

    @Test
    void keepsTheReviewsAfterACleanRebase() throws IOException {
        commitFile(worktree, "b.txt", "feature\n", "Feature");
        IssueRecord record = reviewedRecord();
        moveTheBase("c.txt", "base\n");

        MergeResult result = merger.merge(worktree, record);

        assertThat(result).isEqualTo(new MergeResult.Merged(git.treeHash(worktree)));
        assertThat(((MergeResult.Merged) result).builtTreeHash()).isNotEqualTo(record.builtTreeHash().orElseThrow());
        assertThat(buildCount()).isEqualTo(1);
        assertThat(gitHub.squashMergeCalls()).hasSize(1);
    }

    @Test
    void asksForNewReviewsWhenTheRebaseChangesTheDiff() {
        commitFile(worktree, "m.txt", "1\n2\n3\n4\n5\nsix\n7\n8\n", "Feature");
        IssueRecord record = reviewedRecord();
        moveTheBase("m.txt", "1\n2\n3\nfour\n5\n6\n7\n8\n");

        MergeResult result = merger.merge(worktree, record);

        assertThat(result).isInstanceOf(MergeResult.NeedsNewReviews.class);
        assertThat(gitHub.squashMergeCalls()).isEmpty();
    }

    @Test
    void sendsAConflictBackToTheAgent() {
        commitFile(worktree, "m.txt", "1\n2\n3\n4\n5\nsix\n7\n8\n", "Feature");
        IssueRecord record = reviewedRecord();
        moveTheBase("m.txt", "1\n2\n3\n4\n5\nbase\n7\n8\n");

        MergeResult result = merger.merge(worktree, record);

        assertThat(result).isInstanceOf(MergeResult.NeedsFix.class);
        assertThat(((MergeResult.NeedsFix) result).findings()).contains("conflict");
        assertThat(gitHub.squashMergeCalls()).isEmpty();
    }

    @Test
    void sendsAFailedBuildBackToTheAgent() {
        commitFile(worktree, "b.txt", "feature\n", "Feature");
        IssueRecord record = reviewedRecord();
        moveTheBase("c.txt", "base\n");
        Merger failingMerger = createMerger("echo broken; exit 1", Map.of());

        MergeResult result = failingMerger.merge(worktree, record);

        assertThat(result).isInstanceOf(MergeResult.NeedsFix.class);
        assertThat(((MergeResult.NeedsFix) result).findings()).contains("broken");
        assertThat(gitHub.squashMergeCalls()).isEmpty();
    }

    @Test
    void retriesANotMergeableAnswer() {
        commitFile(worktree, "b.txt", "feature\n", "Feature");
        gitHub.queueMergeOutcomes(MergeOutcome.NOT_MERGEABLE, MergeOutcome.MERGED);

        MergeResult result = merger.merge(worktree, reviewedRecord());

        assertThat(result).isInstanceOf(MergeResult.Merged.class);
        assertThat(gitHub.squashMergeCalls()).hasSize(2);
        assertThat(gitHub.squashMergeShas().get(0)).isEqualTo(gitHub.squashMergeShas().get(1));
    }

    @Test
    void threeNotMergeableAnswersAreATransientFailure() {
        commitFile(worktree, "b.txt", "feature\n", "Feature");
        gitHub.queueMergeOutcomes(MergeOutcome.NOT_MERGEABLE, MergeOutcome.NOT_MERGEABLE,
                MergeOutcome.NOT_MERGEABLE);

        assertThatThrownBy(() -> merger.merge(worktree, reviewedRecord()))
                .isInstanceOf(GitHubException.class)
                .hasMessage("GitHub refuses the merge of the pull request #" + pullRequest.number() + ".");
        assertThat(gitHub.squashMergeCalls()).hasSize(3);
    }

    @Test
    void failsWhenTheIssueHasNoPullRequest() {
        IssueRecord record = RecordStates.queued(7);

        MergeResult result = merger.merge(worktree, record);

        assertThat(result).isEqualTo(new MergeResult.Failed("The issue has no pull request."));
    }

    @Test
    void failsWhenAGitStepFails() {
        commitFile(worktree, "b.txt", "feature\n", "Feature");
        git.setRemote(clone, "gatekeeper", temporaryDirectory.resolve("missing.git").toString());

        MergeResult result = merger.merge(worktree, reviewedRecord());

        assertThat(result).isInstanceOf(MergeResult.Failed.class);
        assertThat(((MergeResult.Failed) result).reason()).startsWith("The push to the gatekeeper failed");
        assertThat(gitHub.squashMergeCalls()).isEmpty();
    }

    @Test
    void failsWhenTheRecordedBuildIsNotTheTreeAfterARebaseByHand() {
        commitFile(worktree, "b.txt", "feature\n", "Feature");
        IssueRecord record = reviewedRecord();
        moveTheBase("c.txt", "base\n");
        git.fetch(worktree, "origin", "main");
        git.rebase(worktree, "origin/main");

        MergeResult result = merger.merge(worktree, record);

        assertThat(result).isEqualTo(new MergeResult.Failed("The files differ from the built and reviewed tree."));
        assertThat(gitHub.squashMergeCalls()).isEmpty();
    }

    @Test
    void failsWhenAnotherCommitFollowsTheReviews() {
        commitFile(worktree, "b.txt", "feature\n", "Feature");
        IssueRecord record = reviewedRecord();
        commitFile(worktree, "d.txt", "late\n", "Late change");

        MergeResult result = merger.merge(worktree, record);

        assertThat(result).isEqualTo(new MergeResult.Failed("The files differ from the built and reviewed tree."));
        assertThat(gitHub.squashMergeCalls()).isEmpty();
    }

    @Test
    void failsWhenTheRecordedReviewedTreeDiffers() {
        commitFile(worktree, "b.txt", "feature\n", "Feature");
        IssueRecord record = reviewedRecord().withReviewedTree("other", "p");

        MergeResult result = merger.merge(worktree, record);

        assertThat(result).isEqualTo(new MergeResult.Failed("The files differ from the built and reviewed tree."));
    }

    @Test
    void failsWithoutABuiltTreeWhenTheBaseDidNotMove() {
        commitFile(worktree, "b.txt", "feature\n", "Feature");
        IssueRecord record = RecordStates.queued(7).withPullRequest(pullRequest.number());

        MergeResult result = merger.merge(worktree, record);

        assertThat(result).isEqualTo(new MergeResult.Failed("The record has no built tree hash."));
        assertThat(gitHub.squashMergeCalls()).isEmpty();
    }

    @Test
    void asksForNewReviewsWhenTheReviewedPatchIdIsEmptyAfterARebase() {
        commitFile(worktree, "b.txt", "feature\n", "Feature");
        IssueRecord record = reviewedRecord().withReviewedTree(git.treeHash(worktree), "");
        moveTheBase("c.txt", "base\n");

        MergeResult result = merger.merge(worktree, record);

        assertThat(result).isInstanceOf(MergeResult.NeedsNewReviews.class);
    }

    @Test
    void pushesTheRebasedHeadToTheGatekeeperAndForwardsIt() {
        commitFile(worktree, "b.txt", "feature\n", "Feature");
        IssueRecord record = reviewedRecord();
        moveTheBase("c.txt", "base\n");

        MergeResult result = merger.merge(worktree, record);

        String head = run(worktree, "rev-parse", "HEAD");
        assertThat(result).isEqualTo(new MergeResult.Merged(git.treeHash(worktree)));
        assertThat(run(gatekeeper, "rev-parse", "refs/heads/" + BRANCH)).isEqualTo(head);
        assertThat(run(github, "rev-parse", "refs/heads/" + BRANCH)).isEqualTo(head);
        assertThat(gitHub.squashMergeShas()).containsExactly(head);
    }

    @Test
    void forcePushesOverAnOlderVersionOfTheBranch() {
        commitFile(worktree, "b.txt", "feature\n", "Feature");
        run(worktree, "push", "-q", "gatekeeper", "HEAD:refs/heads/" + BRANCH);
        run(worktree, "commit", "-q", "--amend", "-m", "Feature amended");
        IssueRecord record = reviewedRecord();

        MergeResult result = merger.merge(worktree, record);

        assertThat(result).isInstanceOf(MergeResult.Merged.class);
        assertThat(run(gatekeeper, "rev-parse", "refs/heads/" + BRANCH)).isEqualTo(run(worktree, "rev-parse", "HEAD"));
    }

    @Test
    void anotherHeadOnGitHubIsATransientFailureWithoutARetry() {
        commitFile(worktree, "b.txt", "feature\n", "Feature");
        gitHub.queueMergeOutcomes(MergeOutcome.HEAD_CHANGED);

        assertThatThrownBy(() -> merger.merge(worktree, reviewedRecord()))
                .isInstanceOf(GitHubException.class)
                .hasMessage("GitHub has another head for the pull request #" + pullRequest.number() + ".");
        assertThat(gitHub.squashMergeCalls()).hasSize(1);
    }

    @Test
    void aFailedSquashMergeRequestLeavesAsAGitHubException() {
        commitFile(worktree, "b.txt", "feature\n", "Feature");
        gitHub.failTheNextSquashMergeAfterTheMerge();

        assertThatThrownBy(() -> merger.merge(worktree, reviewedRecord())).isInstanceOf(GitHubException.class);
        assertThat(gitHub.isMerged(pullRequest.number())).isTrue();
    }

    @Test
    void reportsACommitThatTheForwardDidNotBringToGitHub() {
        commitFile(worktree, "b.txt", "feature\n", "Feature");
        IssueRecord record = reviewedRecord();
        git.setRemote(gatekeeper, "github", temporaryDirectory.resolve("missing.git").toString());

        MergeResult result = merger.merge(worktree, record);

        assertThat(result).isEqualTo(new MergeResult.NotForwarded(git.headCommit(worktree), git.treeHash(worktree)));
        assertThat(gitHub.squashMergeCalls()).isEmpty();
    }

    @Test
    void forwardsTheCommitAgainFromTheGatekeeperWhenGitHubLacksIt() {
        commitFile(worktree, "b.txt", "feature\n", "Feature");
        IssueRecord record = reviewedRecord();
        git.setRemote(gatekeeper, "github", temporaryDirectory.resolve("missing.git").toString());
        assertThat(merger.merge(worktree, record)).isInstanceOf(MergeResult.NotForwarded.class);
        git.setRemote(gatekeeper, "github", github.toString());

        MergeResult result = merger.merge(worktree, record);

        assertThat(result).isInstanceOf(MergeResult.Merged.class);
        assertThat(run(github, "rev-parse", "refs/heads/" + BRANCH)).isEqualTo(git.headCommit(worktree));
    }

    @Test
    void mergesWhenGitHubShowsTheOldHeadForAWhile() {
        commitFile(worktree, "b.txt", "feature\n", "Feature");
        gitHub.answerAnOldPullRequestHeadOnce();

        assertThat(merger.merge(worktree, reviewedRecord())).isInstanceOf(MergeResult.Merged.class);
    }

    @Test
    void returnsToTheReviewedCommitWhenAnotherHeadFollowsARebase() {
        commitFile(worktree, "b.txt", "feature\n", "Feature");
        IssueRecord record = reviewedRecord();
        moveTheBase("c.txt", "base\n");
        gitHub.queueMergeOutcomes(MergeOutcome.HEAD_CHANGED);

        assertThatThrownBy(() -> merger.merge(worktree, record)).isInstanceOf(GitHubException.class);

        assertThat(git.headCommit(worktree)).isEqualTo(record.reviewedHeadCommit().orElseThrow());
    }

    @Test
    void asksForNewReviewsWhenOnlyABinaryFileChangesAfterTheReviews() throws IOException {
        Files.write(worktree.resolve("image.bin"), new byte[] {0, 1, 2, 3});
        run(worktree, "add", "image.bin");
        run(worktree, "commit", "-q", "-m", "Binary");
        IssueRecord record = reviewedRecord();
        Files.write(worktree.resolve("image.bin"), new byte[] {0, 1, 2, 4});
        run(worktree, "commit", "-q", "-a", "-m", "Binary changed");
        moveTheBase("c.txt", "base\n");

        MergeResult result = merger.merge(worktree, record);

        assertThat(result).isInstanceOf(MergeResult.NeedsNewReviews.class);
        assertThat(gitHub.squashMergeCalls()).isEmpty();
    }

    @Test
    void failsWhenTheWorktreeHasAnUntrackedFile() throws IOException {
        commitFile(worktree, "b.txt", "feature\n", "Feature");
        IssueRecord record = reviewedRecord();
        Files.writeString(worktree.resolve("stray.txt"), "stray\n");

        MergeResult result = merger.merge(worktree, record);

        assertThat(result).isEqualTo(new MergeResult.Failed("The worktree has changes that are not committed."));
        assertThat(gitHub.squashMergeCalls()).isEmpty();
    }

    @Test
    void failsWhenTheBuildLeavesAFileInTheWorktree() {
        commitFile(worktree, "b.txt", "feature\n", "Feature");
        IssueRecord record = reviewedRecord();
        moveTheBase("c.txt", "base\n");
        Merger dirtyingMerger = createMerger("echo x > junk.txt", Map.of());

        MergeResult result = dirtyingMerger.merge(worktree, record);

        assertThat(result).isEqualTo(new MergeResult.Failed("The worktree has changes that are not committed."));
        assertThat(gitHub.squashMergeCalls()).isEmpty();
    }

    @Test
    void theSecondOfTwoMergesAtTheSameTimeRebasesOnTheFirstAndBuildsAgain() throws Exception {
        commitFile(worktree, "b.txt", "feature\n", "Feature");
        IssueRecord firstRecord = reviewedRecord();
        Path secondWorktree = temporaryDirectory.resolve("worktree-8");
        git.addWorktree(clone, secondWorktree, "factory/issue-8", "origin/main");
        commitFile(secondWorktree, "d.txt", "other feature\n", "Other feature");
        run(secondWorktree, "push", "-q", gatekeeper.toString(), "HEAD:refs/heads/factory/issue-8");
        PullRequest secondPullRequest = gitHub.createPullRequest("factory/issue-8", "main", "Title", "Body");
        IssueRecord secondRecord = reviewedRecord(8, secondWorktree, secondPullRequest);
        AtomicReference<MergeResult> secondResult = new AtomicReference<>();
        Thread secondMerge = new Thread(() -> secondResult.set(merger.merge(secondWorktree, secondRecord)));
        gitHub.whenSquashMerging(() -> {
            secondMerge.start();
            waitUntilBlockedOrEnded(secondMerge);
        });

        MergeResult firstResult = merger.merge(worktree, firstRecord);
        String firstSquashCommit = run(origin, "rev-parse", "main");
        secondMerge.join(Duration.ofSeconds(30));

        assertThat(firstResult).isInstanceOf(MergeResult.Merged.class);
        assertThat(secondResult.get()).isEqualTo(new MergeResult.Merged(git.treeHash(secondWorktree)));
        assertThat(run(secondWorktree, "rev-parse", "HEAD^")).isEqualTo(firstSquashCommit);
        assertThat(buildCount()).isEqualTo(1);
        assertThat(gitHub.squashMergeShas()).containsExactly(run(worktree, "rev-parse", "HEAD"),
                run(secondWorktree, "rev-parse", "HEAD"));
    }

    @Test
    void runsTheMergeAgainFromTheRebaseWhenTheBaseMovesBeforeTheSquash() throws IOException {
        commitFile(worktree, "b.txt", "feature\n", "Feature");
        IssueRecord record = reviewedRecord();
        moveTheBase("c.txt", "base\n");
        Merger movingMerger = createMergerWithABuildThatMovesTheBase(createPersonClone(), true);

        MergeResult result = movingMerger.merge(worktree, record);

        assertThat(result).isEqualTo(new MergeResult.Merged(git.treeHash(worktree)));
        assertThat(buildCount()).isEqualTo(2);
        assertThat(run(worktree, "rev-parse", "HEAD^")).isEqualTo(run(origin, "rev-parse", "main^"));
        assertThat(run(worktree, "log", "-1", "--format=%s", "HEAD^")).isEqualTo("Person");
        assertThat(gitHub.squashMergeShas()).containsExactly(run(worktree, "rev-parse", "HEAD"));
    }

    @Test
    void postponesTheMergeWhenTheBaseMovesThreeTimes() throws IOException {
        commitFile(worktree, "b.txt", "feature\n", "Feature");
        IssueRecord record = reviewedRecord();
        moveTheBase("c.txt", "base\n");
        Merger movingMerger = createMergerWithABuildThatMovesTheBase(createPersonClone(), false);

        MergeResult result = movingMerger.merge(worktree, record);

        assertThat(result).isEqualTo(new MergeResult.Postponed(
                "The branch main moved 3 times before the merge. The next poll tries again."));
        assertThat(buildCount()).isEqualTo(3);
        assertThat(gitHub.squashMergeCalls()).isEmpty();
        assertThat(git.headCommit(worktree)).isEqualTo(record.reviewedHeadCommit().orElseThrow());
    }

    @Test
    void notesAMergedTreeThatDiffersFromTheBuiltTree() {
        commitFile(worktree, "b.txt", "feature\n", "Feature");
        IssueRecord record = reviewedRecord();
        gitHub.whenSquashMerging(() -> moveTheBase("p.txt", "a push of a person\n"));

        MergeResult result = merger.merge(worktree, record);

        assertThat(result).isEqualTo(new MergeResult.Merged(record.builtTreeHash().orElseThrow(),
                Optional.of("The tree on main after the merge differs from the built tree.")));
    }

    @Test
    void asksForNewReviewsWhenNoChangeIsLeftAfterTheRebase() {
        commitFile(worktree, "b.txt", "same\n", "Feature");
        IssueRecord record = reviewedRecord().withReviewedTree(git.treeHash(worktree), "");
        moveTheBase("b.txt", "same\n");

        MergeResult result = merger.merge(worktree, record);

        assertThat(result).isInstanceOf(MergeResult.NeedsNewReviews.class);
        assertThat(gitHub.squashMergeCalls()).isEmpty();
    }

    private Merger createMerger(String buildCommand, Map<String, String> buildEnvironment) {
        return createMerger(buildCommand, buildEnvironment, OwnershipGuard.none());
    }

    private Merger createMerger(String buildCommand, Map<String, String> buildEnvironment,
                                OwnershipGuard ownershipGuard) {
        return new Merger(git, gitHub, new BuildRunner(), clone, "main",
                new BuildSettings(buildCommand, buildEnvironment), gatekeeper, Duration.ZERO, ownershipGuard);
    }
}
