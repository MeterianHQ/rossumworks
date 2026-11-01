package com.meterian.factory.workflow;

import com.meterian.factory.github.Issue;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** The hash of the issue text: the title, a newline, and the body. A change of the text changes the hash. */
public final class IssueText {

    private IssueText() {
    }

    /** Returns the SHA-256 hash in hexadecimal. */
    public static String computeHash(Issue issue) {
        String text = issue.title() + "\n" + issue.body();
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("The SHA-256 algorithm is missing", exception);
        }
    }
}
