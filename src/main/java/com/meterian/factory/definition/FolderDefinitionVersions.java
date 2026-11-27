package com.meterian.factory.definition;

import com.meterian.factory.state.IssueRecord;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The versions of a definition folder. Each version that loads gets a definition copy, and an issue reads its
 * definition only from its copy. So an edit of the folder reaches only the new issues.
 */
public final class FolderDefinitionVersions implements DefinitionSource {

    private static final System.Logger LOGGER = System.getLogger(FolderDefinitionVersions.class.getName());

    private final Path folder;
    private final DefinitionCopies copies;
    private final FactoryDefinition builtIn;
    private final Map<String, FactoryDefinition> definitionsByVersion = new ConcurrentHashMap<>();
    /** A definition copy does not change, so a copy that does not load gives the same failure at each read. */
    private final Map<String, IllegalStateException> failuresByVersion = new ConcurrentHashMap<>();
    private volatile Optional<DefinitionVersion> current = Optional.empty();
    private volatile Optional<String> warning = Optional.empty();
    /** The hash of the content that the last refresh loaded. The same hash needs no new read. */
    private String lastFolderHash;

    public FolderDefinitionVersions(Path folder, DefinitionCopies copies, FactoryDefinition builtIn) {
        this.folder = folder;
        this.copies = copies;
        this.builtIn = builtIn;
    }

    /** A failure of the read keeps the current version, and the next refresh reads the folder again. */
    @Override
    public synchronized void refresh() {
        try {
            String folderHash = FolderHash.calculate(folder);
            if (folderHash.equals(lastFolderHash)) {
                return;
            }
            lastFolderHash = loadNewContent();
        } catch (UnreadableCopiesException exception) {
            lastFolderHash = null;
            warnAbout("The definition folder " + folder + " does not load, and the definition copies in "
                    + copies.copiesFolder() + " cannot be listed: " + describeReason(exception) + ". ");
        } catch (IOException exception) {
            lastFolderHash = null;
            takeNewestCopyAfterReadFailure();
            warnAbout("The definition folder " + folder + " cannot be read or copied: " + describeReason(exception)
                    + ". ");
        }
    }

    /** The worker deletes the temporary copy in all cases. After a keep, the copy is already a definition copy. */
    private String loadNewContent() throws IOException {
        Path temporaryCopy = copies.copyToTemporaryFolder(folder);
        try {
            return loadTemporaryCopy(temporaryCopy);
        } finally {
            copies.discard(temporaryCopy);
        }
    }

    /**
     * The hash and the load use the same temporary copy, so the version always matches its content.
     *
     * @return the hash of the temporary copy
     * @throws IOException when a file of the copy cannot be read, so the next refresh reads the folder again
     */
    private String loadTemporaryCopy(Path temporaryCopy) throws IOException {
        String hash = FolderHash.calculate(temporaryCopy);
        FactoryDefinition definition;
        try {
            definition = DefinitionLoader.load(new FolderDefinitionFiles(temporaryCopy, folder));
        } catch (DefinitionException exception) {
            if (isReadFailure(exception)) {
                throw new IOException(exception.getMessage(), exception);
            }
            takeNewestCopyWhenNoVersionIsCurrent();
            warnAbout("The definition folder " + folder + " does not load: "
                    + DefinitionVersionText.describeReason(exception.getMessage()) + ". ");
            return hash;
        }
        copies.keep(temporaryCopy, hash);
        String version = LocalVersion.of(hash);
        definitionsByVersion.put(version, definition);
        current = Optional.of(new DefinitionVersion(version, definition));
        warning = Optional.empty();
        return hash;
    }

    /** A failure of the read can go away, so the factory reads the files again later. */
    private static boolean isReadFailure(DefinitionException exception) {
        return exception instanceof UnreadableDefinitionFileException;
    }

    /** An error in the definition folder does not stop the worker: at the start, it takes the newest copy. */
    private void takeNewestCopyAfterReadFailure() {
        try {
            takeNewestCopyWhenNoVersionIsCurrent();
        } catch (UnreadableCopiesException exception) {
            LOGGER.log(System.Logger.Level.WARNING, "The definition copies in {0} cannot be listed: {1}",
                    copies.copiesFolder(), exception.getMessage());
        }
    }

    /** After a start, the folder can fail while an older definition copy loads. */
    private void takeNewestCopyWhenNoVersionIsCurrent() throws UnreadableCopiesException {
        if (current.isPresent()) {
            return;
        }
        try {
            current = findNewestCopyThatLoads();
        } catch (IOException exception) {
            throw new UnreadableCopiesException(exception);
        }
    }

    /** Fail closed: no copy that loads gives no version. */
    private Optional<DefinitionVersion> findNewestCopyThatLoads() throws IOException {
        for (String hash : copies.listNewestFirst()) {
            String version = LocalVersion.of(hash);
            try {
                return Optional.of(new DefinitionVersion(version, definitionOfVersion(version)));
            } catch (IllegalStateException exception) {
                LOGGER.log(System.Logger.Level.INFO, "The definition copy {0} does not load: {1}",
                        DefinitionVersionText.shorten(version), exception.getMessage());
            }
        }
        return Optional.empty();
    }

    @Override
    public Optional<DefinitionVersion> current() {
        return current;
    }

    /** The reason why the definition folder does not load, while an older good version stays current. */
    @Override
    public Optional<String> warning() {
        return warning;
    }

    /** Without a current version, the page shows the columns of the built-in definition. */
    @Override
    public List<String> findCurrentColumns() {
        return current.map(DefinitionVersion::definition).orElse(builtIn).workflow().columns();
    }

    /**
     * Anyone who can edit the status comment can change the version of the record. So the version must have a
     * definition copy: the factory never runs a definition that did not come from the definition folder.
     *
     * @throws IllegalStateException when the version of the record does not load. Its message is one line.
     */
    @Override
    public FactoryDefinition definitionOf(IssueRecord record) {
        return record.definitionVersion().map(this::definitionOfVersion).orElse(builtIn);
    }

    private FactoryDefinition definitionOfVersion(String version) {
        FactoryDefinition known = definitionsByVersion.get(version);
        if (known != null) {
            return known;
        }
        IllegalStateException knownFailure = failuresByVersion.get(version);
        if (knownFailure != null) {
            throw knownFailure;
        }
        String hash = findValidHash(version);
        if (!copies.exists(hash)) {
            throw new IllegalStateException("The version " + DefinitionVersionText.shorten(version)
                    + " of the record has no definition copy in " + copies.copiesFolder());
        }
        return loadCopy(version, hash);
    }

    private String findValidHash(String version) {
        if (!LocalVersion.isLocal(version)) {
            throw new IllegalStateException("The version " + DefinitionVersionText.shorten(version) + " of the "
                    + "record is a commit of .factory, but this worker reads the definition folder " + folder);
        }
        return LocalVersion.findHash(version).orElseThrow(() -> new IllegalStateException("The version "
                + DefinitionVersionText.shorten(version) + " of the record is not a valid local version"));
    }

    private FactoryDefinition loadCopy(String version, String hash) {
        try {
            FactoryDefinition definition = DefinitionLoader.load(new FolderDefinitionFiles(copies.folderOf(hash)));
            definitionsByVersion.put(version, definition);
            return definition;
        } catch (DefinitionException exception) {
            IllegalStateException failure = new IllegalStateException("The definition copy "
                    + DefinitionVersionText.shorten(version) + " does not load: "
                    + DefinitionVersionText.describeReason(exception.getMessage()), exception);
            if (!isReadFailure(exception)) {
                failuresByVersion.put(version, failure);
            }
            throw failure;
        }
    }

    /** The warning tells what the factory does now: it keeps the current version, or it takes no new issue. */
    private void warnAbout(String problem) {
        String consequence = current
                .map(version -> "The factory keeps the version "
                        + DefinitionVersionText.shorten(version.identifier()) + ".")
                .orElse("The factory takes no new issue.");
        warning = Optional.of(problem + consequence);
        LOGGER.log(System.Logger.Level.WARNING, warning.get());
    }

    private static String describeReason(IOException exception) {
        return DefinitionVersionText.describeReason(String.valueOf(exception.getMessage()));
    }

    /** The definition copies cannot be listed. The definition folder can still be read. */
    private static final class UnreadableCopiesException extends IOException {

        UnreadableCopiesException(IOException cause) {
            super(cause.getMessage(), cause);
        }
    }
}
