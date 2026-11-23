package com.meterian.factory.setup;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;

/** The folder of jars that .factory/run.sh reads before it downloads a release. */
final class JarCache {

    private static final String FOLDER = ".rossumworks/jars";
    private static final String PARTIAL_SUFFIX = ".partial";

    private final Path directory;

    JarCache(Path home) {
        this.directory = home.resolve(FOLDER);
    }

    Path directory() {
        return directory;
    }

    /** Copies the jar with its file name. Returns the copy, or nothing when the cache has this jar already. */
    Optional<Path> copy(Path jar) throws IOException {
        Path target = directory.resolve(jar.getFileName().toString());
        if (Files.exists(target)) {
            return Optional.empty();
        }
        Files.createDirectories(directory);
        Path partialTarget = directory.resolve(jar.getFileName() + PARTIAL_SUFFIX);
        Files.copy(jar, partialTarget, StandardCopyOption.REPLACE_EXISTING);
        Files.move(partialTarget, target, StandardCopyOption.ATOMIC_MOVE);
        return Optional.of(target);
    }
}
