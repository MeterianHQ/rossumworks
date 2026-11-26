package com.meterian.factory.definition;

import java.util.Optional;
import java.util.regex.Pattern;

/** The version of a definition folder: local: and the SHA-256 hash of the content, in lowercase hexadecimal. */
public final class LocalVersion {

    private static final String PREFIX = "local:";
    private static final Pattern HASH = Pattern.compile("[0-9a-f]{64}");

    private LocalVersion() {
    }

    public static String of(String hash) {
        return PREFIX + hash;
    }

    public static boolean isLocal(String version) {
        return version.startsWith(PREFIX);
    }

    /** The hash only when it is valid, because the hash becomes the name of a folder. */
    public static Optional<String> findHash(String version) {
        if (!isLocal(version)) {
            return Optional.empty();
        }
        String hash = version.substring(PREFIX.length());
        return HASH.matcher(hash).matches() ? Optional.of(hash) : Optional.empty();
    }
}
