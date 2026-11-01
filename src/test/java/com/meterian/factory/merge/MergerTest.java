package com.meterian.factory.merge;

import static com.meterian.factory.testing.TemporaryRepository.commitFile;
import static com.meterian.factory.testing.TemporaryRepository.createBare;
import static com.meterian.factory.testing.TemporaryRepository.createClone;
import static com.meterian.factory.testing.TemporaryRepository.run;
import static org.assertj.core.api.Assertions.assertThat;

import com.meterian.factory.build.BuildRunner;
import com.meterian.factory.configuration.TargetRepository;
import com.meterian.factory.git.Git;
import com.meterian.factory.git.ProcessGit;
import com.meterian.factory.github.MergeOutcome;
import com.meterian.factory.github.PullRequest;
import com.meterian.factory.state.IssueRecord;
import com.meterian.factory.state.ReviewKind;
import com.meterian.factory.state.RiskLevel;
import com.meterian.factory.state.Verdict;
import com.meterian.factory.testing.FakeGitHubClient;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MergerTest {

    private static final String BRANCH = "factory/issue-7";

    @TempDir
    Path temporaryDirectory;

    private final Git git = new ProcessGit();
    private final FakeGitHubClient gitHub = new FakeGitHubClient();
    private Path clone;
    private Path worktree;
    private Path gatekeeper;
    private Path buildLog;
    private PullRequest pullRequest;
    private Merger merger;

    @BeforeEach
    void createRepositories() {
        Path origin = createBare(temporaryDirectory.resolve("origin.git"));
        clone = createClone(origin, temporaryDirectory.resolve("clone"));
        commitFile(clone, "m.txt", "1\n2\n3\n4\n5\n6\n7\n8\n", "Add m");
        run(clone, "push", "-q", "origin", "main");
        gatekeeper = createBare(temporaryDirectory.resolve("gatekeeper.git"));
        git.setRemote(clone, "gatekeeper", gatekeeper.toString());
        worktree = temporaryDirectory.resolve("worktree");
        git.addWorktree(clone, worktree, BRANCH, "origin/main");
        buildLog = temporaryDirectory.resolve("builds.txt");
        TargetRepository target = new TargetRepository("owner", "name", clone, "echo ran >> " + buildLog,
                Map.of());
        merger = new Merger(git, gitHub, new BuildRunner(), target, "main", Duration.ZERO);
        pullRequest = gitHub.createPullRequest(BRANCH, "main", "Title", "Body");
    }

    private IssueRecord reviewedRecord() {
        IssueRecord record = IssueRecord.queued(7).withPullRequest(pullRequest.number())
                .withRisk(RiskLevel.LOW, true)
                .withReviewedTree(git.treeHash(worktree), git.patchId(worktree, "origin/main"))
                .withBuiltTree(git.treeHash(worktree));
        for (ReviewKind kind : ReviewKind.values()) {
            record = record.withReview(kind, Verdict.APPROVE);
        }
        return record;
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
        assertThat(buildCount()).isZero();
        assertThat(run(gatekeeper, "rev-parse", "refs/heads/" + BRANCH)).isEqualTo(run(worktree, "rev-parse", "HEAD"));
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
        TargetRepository failing = new TargetRepository("owner", "name", clone, "echo broken; exit 1", Map.of());
        Merger failingMerger = new Merger(git, gitHub, new BuildRunner(), failing, "main", Duration.ZERO);

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
    }

    @Test
    void failsAfterThreeNotMergeableAnswers() {
        commitFile(worktree, "b.txt", "feature\n", "Feature");
        gitHub.queueMergeOutcomes(MergeOutcome.NOT_MERGEABLE, MergeOutcome.NOT_MERGEABLE,
                MergeOutcome.NOT_MERGEABLE);

        MergeResult result = merger.merge(worktree, reviewedRecord());

        assertThat(result).isEqualTo(
                new MergeResult.Failed("GitHub refuses the merge of the pull request #" + pullRequest.number()));
        assertThat(gitHub.squashMergeCalls()).hasSize(3);
    }

    @Test
    void failsWhenTheIssueHasNoPullRequest() {
        IssueRecord record = IssueRecord.queued(7);

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
}
