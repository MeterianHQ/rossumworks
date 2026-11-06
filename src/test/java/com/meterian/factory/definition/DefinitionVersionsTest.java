package com.meterian.factory.definition;

import static com.meterian.factory.testing.DefinitionCommits.builtInMainFile;
import static com.meterian.factory.testing.DefinitionCommits.commitDefinition;
import static com.meterian.factory.testing.DefinitionCommits.commitWithoutDefinition;
import static com.meterian.factory.testing.TemporaryRepository.createBare;
import static com.meterian.factory.testing.TemporaryRepository.createClone;
import static com.meterian.factory.testing.TemporaryRepository.run;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.meterian.factory.git.Git;
import com.meterian.factory.git.GitException;
import com.meterian.factory.git.ProcessGit;
import com.meterian.factory.state.IssueRecord;
import com.meterian.factory.testing.RecordStates;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DefinitionVersionsTest {

    private static final String BROKEN_MAIN_FILE = "version: 1\nworkflow: [\n";
    /** A static reference: the logging framework keeps only a weak reference to a logger. */
    private static final Logger VERSIONS_LOGGER = Logger.getLogger(DefinitionVersions.class.getName());

    @TempDir
    Path temporaryDirectory;

    private final Git git = new ProcessGit();
    private final FactoryDefinition builtIn = BuiltInDefinition.load();
    private Path author;
    private Path clone;

    /** The tests cause the warnings on purpose, so the log is only noise. */
    @BeforeAll
    static void silenceTheLog() {
        VERSIONS_LOGGER.setLevel(Level.OFF);
    }

    @BeforeEach
    void createRepositories() {
        Path origin = createBare(temporaryDirectory.resolve("origin.git"));
        author = createClone(origin, temporaryDirectory.resolve("author"));
        clone = temporaryDirectory.resolve("clone");
        run(temporaryDirectory, "clone", "-q", origin.toString(), clone.toString());
    }

    private DefinitionVersions createVersions() {
        return new DefinitionVersions(git, clone, "main", builtIn);
    }

    private static String mainFileWithImplementLimit(int entries) {
        return builtInMainFile().replace("limit: { entries: 3, then: blocked }",
                "limit: { entries: " + entries + ", then: blocked }");
    }

    private static int implementLimitOf(FactoryDefinition definition) {
        return definition.workflow().state("implement").limit().orElseThrow().entries();
    }

    private static String shortCommit(String commit) {
        return commit.substring(0, 7);
    }

    @Test
    void loadsTheDefinitionOfTheDefaultBranch() {
        String commit = commitDefinition(author, builtInMainFile());
        DefinitionVersions versions = createVersions();

        versions.refresh();

        assertThat(versions.current()).hasValueSatisfying(version -> {
            assertThat(version.commit()).isEqualTo(commit);
            assertThat(version.definition().workflow().start()).isEqualTo("prepare");
        });
        assertThat(versions.warning()).isEmpty();
    }

    @Test
    void keepsTheLastGoodVersionWhenANewCommitDoesNotLoad() {
        String good = commitDefinition(author, builtInMainFile());
        DefinitionVersions versions = createVersions();
        versions.refresh();

        String broken = commitDefinition(author, BROKEN_MAIN_FILE);
        versions.refresh();

        assertThat(versions.current()).hasValueSatisfying(version -> assertThat(version.commit()).isEqualTo(good));
        assertThat(versions.warning()).hasValueSatisfying(warning -> assertThat(warning)
                .startsWith("The .factory of " + shortCommit(broken) + " does not load: ")
                .endsWith(". The factory keeps the version " + shortCommit(good) + "."));

        String withoutFolder = commitWithoutDefinition(author);
        versions.refresh();

        assertThat(versions.current()).hasValueSatisfying(version -> assertThat(version.commit()).isEqualTo(good));
        assertThat(versions.warning()).hasValueSatisfying(warning -> assertThat(warning)
                .startsWith("The .factory of " + shortCommit(withoutFolder) + " does not load: ")
                .contains("does not exist"));
    }

    @Test
    void clearsTheWarningWithAGoodVersion() {
        commitDefinition(author, builtInMainFile());
        DefinitionVersions versions = createVersions();
        versions.refresh();
        commitDefinition(author, BROKEN_MAIN_FILE);
        versions.refresh();

        String repaired = commitDefinition(author, mainFileWithImplementLimit(3));
        versions.refresh();

        assertThat(versions.current()).hasValueSatisfying(
                version -> assertThat(version.commit()).isEqualTo(repaired));
        assertThat(versions.warning()).isEmpty();
    }

    @Test
    void takesNoVersionWithoutAGoodDefinition() {
        commitDefinition(author, BROKEN_MAIN_FILE);
        DefinitionVersions versions = createVersions();

        versions.refresh();

        assertThat(versions.current()).isEmpty();
        assertThat(versions.warning()).hasValueSatisfying(warning -> assertThat(warning)
                .startsWith("The default branch has no .factory that loads: ")
                .endsWith(". The factory takes no new issue."));
    }

    @Test
    void aDefaultBranchWithoutFactoryAsksForTheSetup() {
        DefinitionVersions versions = createVersions();

        versions.refresh();

        assertThat(versions.current()).isEmpty();
        assertThat(versions.warning()).contains("The default branch has no .factory. "
                + "Merge the PR factory/setup, or run claude-ai-factory create. The factory takes no new issue.");
    }

    @Test
    void takesTheNewestGoodVersionAfterARestartWithABrokenHead() {
        String older = commitDefinition(author, mainFileWithImplementLimit(3));
        String good = commitDefinition(author, mainFileWithImplementLimit(4));
        String broken = commitDefinition(author, BROKEN_MAIN_FILE);
        assertThat(older).isNotEqualTo(good);
        DefinitionVersions afterRestart = createVersions();

        afterRestart.refresh();

        assertThat(afterRestart.current()).hasValueSatisfying(
                version -> assertThat(version.commit()).isEqualTo(good));
        assertThat(implementLimitOf(afterRestart.current().orElseThrow().definition())).isEqualTo(4);
        assertThat(afterRestart.warning()).hasValueSatisfying(warning -> assertThat(warning)
                .startsWith("The .factory of " + shortCommit(broken) + " does not load: ")
                .endsWith(". The factory keeps the version " + shortCommit(good) + "."));
    }

    @Test
    void stopsTheWalkBackAfterTheLimitOfCommits() {
        commitDefinition(author, builtInMainFile());
        for (int count = 0; count < 20; count++) {
            commitDefinition(author, BROKEN_MAIN_FILE + "# " + count + "\n");
        }
        DefinitionVersions afterRestart = createVersions();

        afterRestart.refresh();

        assertThat(afterRestart.current()).isEmpty();
        assertThat(afterRestart.warning()).hasValueSatisfying(warning -> assertThat(warning)
                .endsWith(". The factory takes no new issue."));
    }

    @Test
    void keepsTheVersionOfAnIssueAfterANewCommit() {
        String first = commitDefinition(author, builtInMainFile());
        DefinitionVersions versions = createVersions();
        versions.refresh();
        IssueRecord record = RecordStates.queued(7).withDefinitionCommit(first);

        commitDefinition(author, mainFileWithImplementLimit(5));
        versions.refresh();

        assertThat(implementLimitOf(versions.definitionOf(record))).isEqualTo(3);
        assertThat(implementLimitOf(versions.current().orElseThrow().definition())).isEqualTo(5);
    }

    @Test
    void loadsTheVersionOfARecordAfterARestart() {
        String first = commitDefinition(author, builtInMainFile());
        createVersions().refresh();
        commitDefinition(author, mainFileWithImplementLimit(5));
        IssueRecord record = RecordStates.queued(7).withDefinitionCommit(first);

        DefinitionVersions afterRestart = createVersions();
        afterRestart.refresh();

        assertThat(implementLimitOf(afterRestart.definitionOf(record))).isEqualTo(3);
    }

    @Test
    void refusesARecordWhoseVersionDoesNotLoad() {
        String broken = commitDefinition(author, BROKEN_MAIN_FILE);
        DefinitionVersions versions = createVersions();
        versions.refresh();
        IssueRecord record = RecordStates.queued(7).withDefinitionCommit(broken);

        assertThatThrownBy(() -> versions.definitionOf(record)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void usesTheBuiltInVersionForARecordWithoutACommit() {
        DefinitionVersions versions = createVersions();

        assertThat(versions.definitionOf(RecordStates.queued(7))).isSameAs(builtIn);
    }

    @Test
    void readsTheCommitAgainAfterAFailureOfGit() {
        String commit = commitDefinition(author, builtInMainFile());
        DefinitionVersions versions = new DefinitionVersions(failTheFirstShowFile(git), clone, "main", builtIn);

        versions.refresh();
        assertThat(versions.current()).isEmpty();
        assertThat(versions.warning()).isEmpty();

        versions.refresh();

        assertThat(versions.current()).hasValueSatisfying(version -> assertThat(version.commit()).isEqualTo(commit));
        assertThat(versions.warning()).isEmpty();
    }

    /** A git that fails the first read of a file, as a lock file or a full disk can do. */
    private static Git failTheFirstShowFile(Git delegate) {
        AtomicBoolean failed = new AtomicBoolean();
        return (Git) Proxy.newProxyInstance(Git.class.getClassLoader(), new Class<?>[] {Git.class},
                (proxy, method, arguments) -> {
                    if (method.getName().equals("showFile") && failed.compareAndSet(false, true)) {
                        throw new GitException("git cat-file failed: the index is locked");
                    }
                    try {
                        return method.invoke(delegate, arguments);
                    } catch (InvocationTargetException exception) {
                        throw exception.getCause();
                    }
                });
    }

    @Test
    void refusesAPromptPathThatLeavesTheFolder() {
        String commit = commitDefinition(author, builtInMainFile());
        GitDefinitionFiles files = new GitDefinitionFiles(git, clone, commit);

        assertThatThrownBy(() -> files.read("../a.txt")).isInstanceOf(DefinitionException.class);
        assertThatThrownBy(() -> files.read("/etc/passwd")).isInstanceOf(DefinitionException.class);
    }
}
