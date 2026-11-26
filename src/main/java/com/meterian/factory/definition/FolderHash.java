package com.meterian.factory.definition;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** The SHA-256 hash of the files of a folder: the same content always gives the same hash. */
public final class FolderHash {

    private static final String ALGORITHM = "SHA-256";

    private FolderHash() {
    }

    /**
     * Calculates the hash of the relative paths and of the content of the files of the folder.
     *
     * @return the hash in lowercase hexadecimal, 64 characters
     * @throws IOException when a file cannot be read, or when an entry is not a file or a folder
     */
    public static String calculate(Path folder) throws IOException {
        MessageDigest digest = createDigest();
        for (DefinitionFolderEntry entry : DefinitionFolderEntries.list(folder)) {
            addFile(digest, entry.relativePath(), Files.readAllBytes(entry.file()));
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /** The lengths separate the path and the content, so text that moves from one file to the next changes the hash. */
    private static void addFile(MessageDigest digest, String relativePath, byte[] content) {
        byte[] path = relativePath.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Long.BYTES).putLong(path.length).array());
        digest.update(path);
        digest.update(ByteBuffer.allocate(Long.BYTES).putLong(content.length).array());
        digest.update(content);
    }

    private static MessageDigest createDigest() {
        try {
            return MessageDigest.getInstance(ALGORITHM);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("The JDK has no " + ALGORITHM + ".", exception);
        }
    }
}
