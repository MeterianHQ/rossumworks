package com.meterian.factory.file;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Arrays;

/** Writes a file in one step: a reader never sees a missing or a half written file. */
public final class AtomicFileWriter {

    private AtomicFileWriter() {
    }

    /**
     * Writes the content to a temporary file in the same folder and moves it in place. A file that
     * already has the same content and the same permissions stays as it is.
     */
    public static void write(Path file, byte[] content, String permissions) throws IOException {
        if (isUpToDate(file, content, permissions)) {
            return;
        }
        Path directory = file.toAbsolutePath().getParent();
        Path temporaryFile = null;
        try {
            Files.createDirectories(directory);
            temporaryFile = Files.createTempFile(directory, "." + file.getFileName() + "-", ".tmp");
            Files.write(temporaryFile, content);
            Files.setPosixFilePermissions(temporaryFile, PosixFilePermissions.fromString(permissions));
            Files.move(temporaryFile, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            deleteIfPresent(temporaryFile);
        }
    }

    private static boolean isUpToDate(Path file, byte[] content, String permissions) {
        try {
            return Files.isRegularFile(file)
                    && PosixFilePermissions.toString(Files.getPosixFilePermissions(file)).equals(permissions)
                    && Arrays.equals(Files.readAllBytes(file), content);
        } catch (IOException exception) {
            return false;
        }
    }

    /** After a successful move the temporary file is gone, so this deletes only what a failure leaves. */
    private static void deleteIfPresent(Path temporaryFile) {
        if (temporaryFile == null) {
            return;
        }
        try {
            Files.deleteIfExists(temporaryFile);
        } catch (IOException ignored) {
            // The first failure is the one to report, so this one stays quiet.
        }
    }
}
