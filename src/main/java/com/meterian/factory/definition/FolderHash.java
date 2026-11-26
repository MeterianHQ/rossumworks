package com.meterian.factory.definition;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;

/** The SHA-256 hash of the files of a folder: the same content always gives the same hash. */
public final class FolderHash {

    private static final String ALGORITHM = "SHA-256";

    private FolderHash() {
    }

    /**
     * @return the hash in lowercase hexadecimal, 64 characters
     * @throws IOException when a file cannot be read, or when an entry is not a file or a folder
     */
    public static String calculate(Path folder) throws IOException {
        MessageDigest digest = createDigest();
        for (Path file : listFiles(folder)) {
            addFile(digest, folder.relativize(file), Files.readAllBytes(file));
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /** The regular files of the folder and of its subfolders, sorted by their relative path. */
    public static List<Path> listFiles(Path folder) throws IOException {
        try (Stream<Path> entries = Files.walk(folder)) {
            List<Path> sorted = entries.sorted().toList();
            for (Path entry : sorted) {
                verifyIsFileOrFolder(folder, entry);
            }
            return sorted.stream().filter(entry -> Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)).toList();
        }
    }

    private static void verifyIsFileOrFolder(Path folder, Path entry) throws IOException {
        boolean isFile = Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS);
        boolean isFolder = Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS);
        if (!isFile && !isFolder) {
            throw new IOException("The definition folder " + folder + " has the entry " + folder.relativize(entry)
                    + ", which is not a file or a folder.");
        }
    }

    /** The lengths separate the path and the content, so text that moves from one file to the next changes the hash. */
    private static void addFile(MessageDigest digest, Path relativePath, byte[] content) {
        byte[] path = relativePath.toString().replace('\\', '/').getBytes(StandardCharsets.UTF_8);
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
