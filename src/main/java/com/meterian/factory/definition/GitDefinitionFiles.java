package com.meterian.factory.definition;

import com.meterian.factory.git.Git;
import com.meterian.factory.git.GitException;
import java.nio.file.Path;
import java.util.Optional;

/** The folder .factory of one commit, read with git in the clone of the worker, never from a worktree. */
public final class GitDefinitionFiles implements DefinitionFiles {

    private static final String FOLDER = ".factory/";

    private final Git git;
    private final Path clone;
    private final String commit;

    public GitDefinitionFiles(Git git, Path clone, String commit) {
        this.git = git;
        this.clone = clone;
        this.commit = commit;
    }

    @Override
    public Optional<String> read(String relativePath) throws DefinitionException {
        DefinitionFiles.verifyPathStaysInFolder(relativePath);
        try {
            return git.showFile(clone, commit, FOLDER + relativePath);
        } catch (GitException exception) {
            throw new DefinitionException("Cannot read the file .factory/" + relativePath + ": "
                    + exception.getMessage(), exception);
        }
    }
}
