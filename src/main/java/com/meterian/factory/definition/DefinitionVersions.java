package com.meterian.factory.definition;

import com.meterian.factory.git.CloneLock;
import com.meterian.factory.git.Git;
import com.meterian.factory.git.GitException;
import com.meterian.factory.state.IssueRecord;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The versions of the folder .factory on the default branch of the starting repository. The newest version that
 * loads is the current version: a new issue starts with it. Each issue keeps the version of its record.
 */
public final class DefinitionVersions implements DefinitionLookup {

    private static final System.Logger LOGGER = System.getLogger(DefinitionVersions.class.getName());
    private static final String ORIGIN = "origin";
    private static final int SHORT_COMMIT_LENGTH = 7;
    private static final int WALK_BACK_LIMIT = 20;
    private static final String FOLDER = ".factory";

    private final Git git;
    private final Path clone;
    private final String defaultBranch;
    private final FactoryDefinition builtIn;
    private final Map<String, FactoryDefinition> definitionsByCommit = new ConcurrentHashMap<>();
    /** A commit that does not load gives the same failure at each read, so the failure is kept. */
    private final Map<String, String> failuresByCommit = new ConcurrentHashMap<>();
    private volatile Optional<DefinitionVersion> current = Optional.empty();
    private volatile Optional<String> warning = Optional.empty();
    /** The newest commit that refresh read. Refresh reads each commit and logs its failure only once. */
    private String newestCommit;

    public DefinitionVersions(Git git, Path clone, String defaultBranch, FactoryDefinition builtIn) {
        this.git = git;
        this.clone = clone;
        this.defaultBranch = defaultBranch;
        this.builtIn = builtIn;
    }

    /**
     * Reads origin/<default branch>. A new commit that loads becomes the current version. A failure of git keeps
     * the current version, and the next refresh reads the same commit again.
     */
    public synchronized void refresh() {
        Optional<String> fetched = fetchNewestCommit();
        if (fetched.isEmpty() || fetched.get().equals(newestCommit)) {
            return;
        }
        String commit = fetched.get();
        try {
            current = Optional.of(new DefinitionVersion(commit, definitionOfCommit(commit)));
            warning = Optional.empty();
        } catch (UnreadableCommitException exception) {
            LOGGER.log(System.Logger.Level.WARNING, "The read of .factory of {0} failed, so the factory keeps its "
                    + "version of .factory: {1}", shorten(commit), exception.getMessage());
            return;
        } catch (IllegalStateException exception) {
            if (current.isEmpty()) {
                current = findNewestGoodVersionBefore(commit);
            }
            warning = Optional.of(describeFailure(commit, exception.getMessage()));
            LOGGER.log(System.Logger.Level.WARNING, warning.get());
        }
        newestCommit = commit;
    }

    /**
     * After a start, the newest commit can fail while an older commit loads. This reads the history of the folder
     * .factory, newest first, within the limit, and takes the first commit that loads. Fail closed: no commit gives
     * no version.
     */
    private Optional<DefinitionVersion> findNewestGoodVersionBefore(String failedCommit) {
        List<String> commits;
        try {
            commits = git.commitsOfPath(clone, failedCommit, FOLDER, WALK_BACK_LIMIT);
        } catch (GitException exception) {
            LOGGER.log(System.Logger.Level.WARNING, "The history of .factory before {0} cannot be read: {1}",
                    shorten(failedCommit), exception.getMessage());
            return Optional.empty();
        }
        for (String commit : commits) {
            try {
                return Optional.of(new DefinitionVersion(commit, definitionOfCommit(commit)));
            } catch (IllegalStateException exception) {
                LOGGER.log(System.Logger.Level.INFO, "The version {0} of .factory does not load: {1}",
                        shorten(commit), exception.getMessage());
            }
        }
        return Optional.empty();
    }

    public Optional<DefinitionVersion> current() {
        return current;
    }

    /** The reason why the newest commit does not load, while an older good version stays current. */
    public Optional<String> warning() {
        return warning;
    }

    /**
     * The version of the record: its commit, or the built-in version for a record without a commit.
     *
     * @throws IllegalStateException when the commit of the record does not load. Its message is one line without
     *                               a final period.
     */
    @Override
    public FactoryDefinition definitionOf(IssueRecord record) {
        return record.definitionCommit().map(this::definitionOfCommit).orElse(builtIn);
    }

    /** The accounts of the newest good version; empty without a good version. */
    public List<String> allowedAccounts() {
        return current.map(version -> version.definition().allowedAccounts()).orElse(List.of());
    }

    /** A failure of the fetch keeps the current version. */
    private Optional<String> fetchNewestCommit() {
        try {
            return Optional.of(CloneLock.run(clone, () -> {
                git.fetch(clone, ORIGIN, defaultBranch);
                return git.resolveCommit(clone, ORIGIN + "/" + defaultBranch);
            }));
        } catch (GitException exception) {
            LOGGER.log(System.Logger.Level.WARNING, "The fetch of the branch {0} failed, so the factory keeps its "
                    + "version of .factory: {1}", defaultBranch, exception.getMessage());
            return Optional.empty();
        }
    }

    private FactoryDefinition definitionOfCommit(String commit) {
        FactoryDefinition known = definitionsByCommit.get(commit);
        if (known != null) {
            return known;
        }
        String knownFailure = failuresByCommit.get(commit);
        if (knownFailure != null) {
            throw new IllegalStateException(knownFailure);
        }
        return loadCommit(commit);
    }

    /** Only a failure of the content is kept: a failure of git can go away, so the next read tries again. */
    private FactoryDefinition loadCommit(String commit) {
        try {
            FactoryDefinition definition = DefinitionLoader.load(new GitDefinitionFiles(git, clone, commit));
            definitionsByCommit.put(commit, definition);
            return definition;
        } catch (DefinitionException exception) {
            String reason = describeReason(exception.getMessage());
            if (exception.getCause() instanceof GitException) {
                throw new UnreadableCommitException(reason, exception);
            }
            failuresByCommit.put(commit, reason);
            throw new IllegalStateException(reason, exception);
        }
    }

    /** Git did not read the folder .factory of the commit. The content of the commit is not known. */
    private static final class UnreadableCommitException extends IllegalStateException {

        UnreadableCommitException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private String describeFailure(String commit, String reason) {
        return current
                .map(version -> "The .factory of " + shorten(commit) + " does not load: " + reason
                        + ". The factory keeps the version " + shorten(version.commit()) + ".")
                .orElseGet(() -> "The default branch has no .factory that loads: " + reason
                        + ". The factory takes no new issue.");
    }

    /** The first characters of a commit, as git shows them. */
    public static String shorten(String commit) {
        return commit.length() <= SHORT_COMMIT_LENGTH ? commit : commit.substring(0, SHORT_COMMIT_LENGTH);
    }

    /** One line without a final period, so that the reason fits in the sentence of the warning. */
    private static String describeReason(String message) {
        String trimmed = message.replaceAll("\\s*\\R\\s*", " ").strip();
        return trimmed.endsWith(".") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }
}
