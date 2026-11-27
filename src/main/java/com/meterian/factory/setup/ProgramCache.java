package com.meterian.factory.setup;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;

/** A folder of programs that .factory/run.sh reads before it downloads a release. */
final class ProgramCache {

    private static final String PARTIAL_SUFFIX = ".partial";

    private final Path directory;

    ProgramCache(Path directory) {
        this.directory = directory;
    }

    Path directory() {
        return directory;
    }

    /**
     * Copies the program with its file name and its permissions, so that a native program stays executable.
     * Returns the copy, or nothing when the cache has this program already.
     */
    Optional<Path> copy(Path program) throws IOException {
        Path target = directory.resolve(program.getFileName().toString());
        if (Files.exists(target)) {
            return Optional.empty();
        }
        Files.createDirectories(directory);
        Path partialTarget = directory.resolve(program.getFileName() + PARTIAL_SUFFIX);
        Files.copy(program, partialTarget, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
        Files.move(partialTarget, target, StandardCopyOption.ATOMIC_MOVE);
        return Optional.of(target);
    }
}
