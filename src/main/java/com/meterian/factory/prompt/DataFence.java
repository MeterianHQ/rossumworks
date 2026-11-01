package com.meterian.factory.prompt;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/** Shared parts of the fences: the random code of the markers and the removal of forged marker lines. */
final class DataFence {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final List<String> MARKER_NAMES =
            List.of("issue-textstart", "issue-textend", "findingsstart", "findingsend");

    private DataFence() {
    }

    static String createNonce() {
        byte[] bytes = new byte[8];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    static String removeForgedMarkerLines(String text) {
        if (text == null) {
            return "";
        }
        return text.lines().filter(line -> !looksLikeMarker(line)).collect(Collectors.joining("\n"));
    }

    private static boolean looksLikeMarker(String line) {
        String normalized = line.replaceAll("[\\s\\u200B\\u200C\\u200D\\uFEFF]", "").toLowerCase(Locale.ROOT);
        return MARKER_NAMES.stream().anyMatch(normalized::contains);
    }
}
