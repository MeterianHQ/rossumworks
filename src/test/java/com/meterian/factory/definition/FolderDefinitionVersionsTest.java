package com.meterian.factory.definition;

import static com.meterian.factory.testing.DefinitionCommits.builtInMainFile;
import static com.meterian.factory.testing.DefinitionFolders.writeDefinitionFolder;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.meterian.factory.state.IssueRecord;
import com.meterian.factory.testing.RecordStates;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.stream.Stream;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FolderDefinitionVersionsTest {

    private static final String BROKEN_MAIN_FILE = "version: 1\nworkflow: [\n";
    /** A static reference: the logging framework keeps only a weak reference to a logger. */
    private static final Logger VERSIONS_LOGGER = Logger.getLogger(FolderDefinitionVersions.class.getName());

    @TempDir
    Path temporaryDirectory;

    private final FactoryDefinition builtIn = BuiltInDefinition.load();
    private Path folder;
    private DefinitionCopies copies;

    /** The tests cause the warnings on purpose, so the log is only noise. */
    @BeforeAll
    static void silenceTheLog() {
        VERSIONS_LOGGER.setLevel(Level.OFF);
    }

    @BeforeEach
    void createTheFolders() {
        folder = temporaryDirectory.resolve("definition");
        copies = DefinitionCopies.inWorkFolder(temporaryDirectory.resolve("work"));
    }

    private FolderDefinitionVersions createVersions() {
        return new FolderDefinitionVersions(folder, copies, builtIn);
    }

    private static String mainFileWithImplementLimit(int entries) {
        return builtInMainFile().replace("limit: { entries: 3, then: blocked }",
                "limit: { entries: " + entries + ", then: blocked }");
    }

    private static int implementLimitOf(FactoryDefinition definition) {
        return definition.workflow().state("implement").limit().orElseThrow().entries();
    }

    private String currentVersion(FolderDefinitionVersions versions) {
        return versions.current().orElseThrow().identifier();
    }

    @Test
    void loadsTheFolderAndKeepsACopy() throws IOException {
        writeDefinitionFolder(folder, builtInMainFile());
        FolderDefinitionVersions versions = createVersions();

        versions.refresh();

        String hash = FolderHash.calculate(folder);
        assertThat(currentVersion(versions)).isEqualTo(LocalVersion.of(hash));
        assertThat(copies.exists(hash)).isTrue();
        assertThat(versions.warning()).isEmpty();
    }

    @Test
    void takesANewVersionAfterAnEdit() {
        writeDefinitionFolder(folder, mainFileWithImplementLimit(3));
        FolderDefinitionVersions versions = createVersions();
        versions.refresh();
        String first = currentVersion(versions);
        writeDefinitionFolder(folder, mainFileWithImplementLimit(5));

        versions.refresh();

        assertThat(currentVersion(versions)).isNotEqualTo(first);
        assertThat(implementLimitOf(versions.current().orElseThrow().definition())).isEqualTo(5);
    }

    @Test
    void keepsTheCurrentVersionWhenTheEditDoesNotLoad() throws IOException {
        writeDefinitionFolder(folder, builtInMainFile());
        FolderDefinitionVersions versions = createVersions();
        versions.refresh();
        String good = currentVersion(versions);
        writeDefinitionFolder(folder, BROKEN_MAIN_FILE);

        versions.refresh();

        assertThat(currentVersion(versions)).isEqualTo(good);
        assertThat(versions.warning()).hasValueSatisfying(warning -> assertThat(warning)
                .startsWith("The definition folder " + folder + " does not load: ")
                .endsWith("The factory keeps the version " + DefinitionVersionText.shorten(good) + "."));
        assertThat(copies.listNewestFirst()).hasSize(1);
    }

    @Test
    void removesTheWarningAfterACorrection() {
        writeDefinitionFolder(folder, builtInMainFile());
        FolderDefinitionVersions versions = createVersions();
        versions.refresh();
        writeDefinitionFolder(folder, BROKEN_MAIN_FILE);
        versions.refresh();
        writeDefinitionFolder(folder, mainFileWithImplementLimit(5));

        versions.refresh();

        assertThat(versions.warning()).isEmpty();
        assertThat(implementLimitOf(versions.current().orElseThrow().definition())).isEqualTo(5);
    }

    @Test
    void hasNoVersionWhenNothingLoads() {
        writeDefinitionFolder(folder, BROKEN_MAIN_FILE);
        FolderDefinitionVersions versions = createVersions();

        versions.refresh();

        assertThat(versions.current()).isEmpty();
        assertThat(versions.warning()).hasValueSatisfying(warning -> assertThat(warning)
                .endsWith("The factory takes no new issue."));
    }

    @Test
    void keepsTheCurrentVersionWhenTheFolderCannotBeRead() throws IOException {
        writeDefinitionFolder(folder, builtInMainFile());
        FolderDefinitionVersions versions = createVersions();
        versions.refresh();
        String good = currentVersion(versions);
        Files.createSymbolicLink(folder.resolve("link.md"), folder.resolve("factory.yaml"));

        versions.refresh();

        assertThat(currentVersion(versions)).isEqualTo(good);
        assertThat(versions.warning()).hasValueSatisfying(warning -> assertThat(warning)
                .contains("link.md, which is not a file or a folder."));
    }

    @Test
    void takesTheNewestCopyThatLoadsAtTheStartWhenTheFolderDoesNotLoad() {
        writeDefinitionFolder(folder, mainFileWithImplementLimit(5));
        FolderDefinitionVersions earlierRun = createVersions();
        earlierRun.refresh();
        String good = currentVersion(earlierRun);
        writeDefinitionFolder(folder, BROKEN_MAIN_FILE);
        FolderDefinitionVersions versions = createVersions();

        versions.refresh();

        assertThat(currentVersion(versions)).isEqualTo(good);
        assertThat(versions.warning()).isPresent();
    }

    @Test
    void givesTheRecordTheDefinitionOfItsCopy() {
        writeDefinitionFolder(folder, mainFileWithImplementLimit(5));
        FolderDefinitionVersions versions = createVersions();
        versions.refresh();
        IssueRecord record = RecordStates.queued(7).withDefinitionVersion(currentVersion(versions));
        writeDefinitionFolder(folder, mainFileWithImplementLimit(2));
        versions.refresh();

        assertThat(implementLimitOf(versions.definitionOf(record))).isEqualTo(5);
        assertThat(implementLimitOf(createVersions().definitionOf(record))).isEqualTo(5);
    }

    @Test
    void givesARecordWithoutAVersionTheBuiltInDefinition() {
        assertThat(createVersions().definitionOf(RecordStates.queued(7))).isSameAs(builtIn);
    }

    @Test
    void refusesALocalVersionWithoutACopy() {
        IssueRecord record = RecordStates.queued(7).withDefinitionVersion(LocalVersion.of("b".repeat(64)));

        assertThatThrownBy(() -> createVersions().definitionOf(record))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("The version local:bbbbbbb of the record has no definition copy in "
                        + copies.copiesFolder());
    }

    @Test
    void refusesALocalVersionWithAHashThatIsNotValid() {
        IssueRecord record = RecordStates.queued(7).withDefinitionVersion("local:../../etc");

        assertThatThrownBy(() -> createVersions().definitionOf(record))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("The version local:../../e of the record is not a valid local version");
    }

    @Test
    void refusesACommitInTheRecord() {
        IssueRecord record = RecordStates.queued(7).withDefinitionVersion("3f2a9c1d8e");

        assertThatThrownBy(() -> createVersions().definitionOf(record))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("The version 3f2a9c1 of the record is a commit of .factory, but this worker reads "
                        + "the definition folder " + folder);
    }

    @Test
    void showsTheColumnsOfTheBuiltInDefinitionWithoutAVersion() {
        assertThat(createVersions().findCurrentColumns()).isEqualTo(builtIn.workflow().columns());
    }

    @Test
    void leavesNoTemporaryCopyWhenTheFolderDoesNotLoad() throws IOException {
        writeDefinitionFolder(folder, BROKEN_MAIN_FILE);

        createVersions().refresh();

        assertThat(listCopiesFolder()).isEmpty();
    }

    @Test
    void warnsAndLeavesNoTemporaryCopyWhenTheCopiesCannotBeListed() throws IOException {
        writeDefinitionFolder(folder, BROKEN_MAIN_FILE);
        Files.createDirectories(copies.copiesFolder());
        Files.setPosixFilePermissions(copies.copiesFolder(), PosixFilePermissions.fromString("-wx------"));
        FolderDefinitionVersions versions = createVersions();

        try {
            versions.refresh();
        } finally {
            Files.setPosixFilePermissions(copies.copiesFolder(), PosixFilePermissions.fromString("rwx------"));
        }

        assertThat(versions.current()).isEmpty();
        assertThat(versions.warning()).hasValueSatisfying(warning -> assertThat(warning)
                .contains("cannot be listed")
                .endsWith("The factory takes no new issue."));
        assertThat(listCopiesFolder()).isEmpty();
    }

    private List<Path> listCopiesFolder() throws IOException {
        try (Stream<Path> entries = Files.list(copies.copiesFolder())) {
            return entries.toList();
        }
    }
}
