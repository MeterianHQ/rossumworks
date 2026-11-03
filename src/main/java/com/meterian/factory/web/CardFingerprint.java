package com.meterian.factory.web;

import com.meterian.factory.board.IssueCard;
import com.meterian.factory.state.IssueRecord;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * A short value that changes when the thing to act on changes. Each form of the page sends it in the field
 * "state", so that a form from an old page cannot act on a different approval.
 */
public final class CardFingerprint {

    static final String FIELD_NAME = "state";
    private static final int LENGTH = 16;

    private CardFingerprint() {
    }

    /** The first 16 hexadecimal characters of the SHA-256 of the fields that define the thing to act on. */
    public static String of(IssueCard card) {
        return HexFormat.of().formatHex(hash(describe(card.record()))).substring(0, LENGTH);
    }

    private static String describe(IssueRecord record) {
        return String.join("\n",
                record.status().labelName(),
                record.waitReason().map(Enum::name).orElse(""),
                record.state(),
                Integer.toString(record.fixLoops()),
                record.reviewedTreeHash().orElse(""),
                record.issueTextHash().orElse(""));
    }

    private static byte[] hash(String text) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("The JDK has no SHA-256.", exception);
        }
    }
}
