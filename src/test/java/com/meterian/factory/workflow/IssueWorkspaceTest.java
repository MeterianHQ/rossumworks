package com.meterian.factory.workflow;

import static com.meterian.factory.testing.TemporaryRepository.commitFile;
import static com.meterian.factory.testing.TemporaryRepository.createBare;
import static com.meterian.factory.testing.TemporaryRepository.createClone;
import static com.meterian.factory.testing.TemporaryRepository.run;
import static org.assertj.core.api.Assertions.assertThat;

import com.meterian.factory.build.BuildRunner;
import com.meterian.factory.build.BuildSettings;
import com.meterian.factory.configuration.ClaudeSettings;
import com.meterian.factory.configuration.FactoryConfiguration;
import com.meterian.factory.configuration.IsolationMode;
import com.meterian.factory.configuration.Limits;
import com.meterian.factory.configuration.TargetRepository;
import com.meterian.factory.git.Git;
import com.meterian.factory.git.ProcessGit;
import com.meterian.factory.merge.MergeResult;
import com.meterian.factory.merge.Merger;
import com.meterian.factory.state.IssueRecord;
import com.meterian.factory.testing.FakeGitHubClient;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class IssueWorkspaceTest {

    private static final int ROUNDS = 20;

    @TempDir
    Path temporaryDirectory;

    private final Git git = new ProcessGit();

    /** Each round moves origin, so each fetch writes refs/remotes/origin/main of the shared clone. */
    @Test
    void theCreateAndTheFetchOfTheMergeShareOneLock() throws Exception {
        Path origin = createBare(temporaryDirectory.resolve("origin.git"));
        Path clone = createClone(origin, temporaryDirectory.resolve("clone"));
        Path pusher = temporaryDirectory.resolve("pusher");
        run(temporaryDirectory, "clone", "-q", origin.toString(), pusher.toString());
        run(pusher, "config", "user.name", "Factory Test");
        run(pusher, "config", "user.email", "factory-test@example.com");
        TargetRepository target = new TargetRepository("MeterianHQ", "sample", clone, "true", Map.of());
        FactoryConfiguration configuration = new FactoryConfiguration(target, "main", Limits.createDefaults(),
                List.of("bbossola"), IsolationMode.SAME_USER, ClaudeSettings.createDefaults(),
                temporaryDirectory.resolve("work"), temporaryDirectory.resolve("gatekeeper.git"), Optional.empty(),
                temporaryDirectory.resolve("live"), Optional.empty(),
                FactoryConfiguration.DEFAULT_TERMINAL_COMMAND);
        IssueWorkspace workspace = new IssueWorkspace(configuration, git, "main");
        Path mergeWorktree = temporaryDirectory.resolve("work").resolve("issue-2");
        workspace.create(2, mergeWorktree);
        Merger merger = new Merger(git, new FakeGitHubClient(), new BuildRunner(), clone, "main",
                new BuildSettings(target.buildCommand(), target.buildEnvironment()),
                temporaryDirectory.resolve("gatekeeper.git"), Duration.ZERO);
        IssueRecord record = IssueRecord.queued(2).withPullRequest(500);
        List<MergeResult> mergeResults = new CopyOnWriteArrayList<>();
        ExecutorService executor = Executors.newFixedThreadPool(2);

        Future<?> creates = executor.submit(() -> {
            for (int round = 0; round < ROUNDS; round++) {
                workspace.create(1, temporaryDirectory.resolve("work").resolve("issue-1"));
            }
        });
        Future<?> merges = executor.submit(() -> {
            for (int round = 0; round < ROUNDS; round++) {
                commitFile(pusher, "base.txt", "round " + round + "\n", "Move the base " + round);
                run(pusher, "push", "-q", "origin", "HEAD:main");
                mergeResults.add(merger.merge(mergeWorktree, record));
            }
        });
        creates.get(2, TimeUnit.MINUTES);
        merges.get(2, TimeUnit.MINUTES);
        executor.shutdown();

        assertThat(mergeResults).hasSize(ROUNDS)
                .noneMatch(result -> result instanceof MergeResult.Failed failed
                        && failed.reason().startsWith("The fetch"));
    }
}
