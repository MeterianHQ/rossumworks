package com.meterian.factory.workflow;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Builds the body of a pull request from the description that the agent wrote in .factory/description.md. */
final class PullRequestDescription {

    static final int MAXIMUM_CHARACTERS = 20_000;

    private static final long MAXIMUM_BYTES = 1_000_000;
    private static final String FALLBACK = "The agent wrote no description.";
    private static final String CUT_NOTE = "The description is too long; the factory cut it.";
    private static final Pattern CLOSING_KEYWORD = Pattern.compile(
            "\\b(close[sd]?|fix(?:e[sd])?|resolve[sd]?)\\b(?:\\s*:)?\\s+"
                    + "(?:#\\d+|GH-\\d+|[\\w.-]+/[\\w.-]+#\\d+|https?://github\\.com/[\\w.-]+/[\\w.-]+/issues/\\d+)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CHARACTER_CLASS);
    private static final Pattern TRAILING_NUMBER = Pattern.compile("\\d+$");

    private PullRequestDescription() {
    }

    static String bodyFor(int issueNumber, Path worktree) {
        return bodyIfDescribed(issueNumber, worktree).orElseGet(() -> buildBody(issueNumber, FALLBACK));
    }

    /** The body, or empty when the agent wrote no usable description. */
    static Optional<String> bodyIfDescribed(int issueNumber, Path worktree) {
        String text = readFile(worktree.resolve(".factory").resolve("description.md")).strip();
        if (text.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(buildBody(issueNumber, neutralizeClosingKeywords(cutToMaximum(text))));
    }

    private static String buildBody(int issueNumber, String description) {
        return "Fixes #" + issueNumber + "\n\n## The change\n\n" + description
                + "\n\nThe factory made this change for the issue #" + issueNumber + ".";
    }

    private static String cutToMaximum(String text) {
        if (text.length() <= MAXIMUM_CHARACTERS) {
            return text;
        }
        return text.substring(0, MAXIMUM_CHARACTERS) + "\n\n" + CUT_NOTE;
    }

    private static String neutralizeClosingKeywords(String text) {
        return CLOSING_KEYWORD.matcher(text)
                .replaceAll(match -> match.group(1) + " issue " + numberAtTheEnd(match.group()));
    }

    private static String numberAtTheEnd(String reference) {
        Matcher matcher = TRAILING_NUMBER.matcher(reference);
        matcher.find();
        return matcher.group();
    }

    private static String readFile(Path file) {
        try {
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                return "";
            }
            try (InputStream stream = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
                byte[] content = stream.readNBytes((int) MAXIMUM_BYTES + 1);
                return content.length > MAXIMUM_BYTES ? "" : new String(content, StandardCharsets.UTF_8);
            }
        } catch (IOException exception) {
            return "";
        }
    }
}
