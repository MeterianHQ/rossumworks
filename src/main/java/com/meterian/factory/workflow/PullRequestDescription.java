package com.meterian.factory.workflow;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Builds the body of a pull request from the description that the agent wrote in the scratch folder. */
final class PullRequestDescription {

    static final int MAXIMUM_CHARACTERS = 20_000;
    static final int MAXIMUM_NOTE_CHARACTERS = 1_000;

    private static final long MAXIMUM_BYTES = 1_000_000;
    private static final String FALLBACK = "The agent wrote no description.";
    private static final String CUT_NOTE = "The description is too long; the factory cut it.";
    private static final Pattern CLOSING_KEYWORD = Pattern.compile(
            "\\b(close[sd]?|fix(?:e[sd])?|resolve[sd]?)\\b(?:\\s*:)?\\s+"
                    + "(?:#\\d+|GH-\\d+|[\\w.-]+/[\\w.-]+#\\d+|https?://github\\.com/[\\w.-]+/[\\w.-]+/issues/\\d+)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CHARACTER_CLASS);
    private static final String NOTES_HEADING = "\n\n## Notes of the reviews\n\n";
    private static final String NOTE_CUT = " (cut)";
    private static final Pattern AGENT_NOTES_HEADING = Pattern.compile(
            "^[ \\t]*#{1,6}[ \\t]*(Notes of the reviews)[ \\t]*$", Pattern.MULTILINE | Pattern.CASE_INSENSITIVE);
    private static final Pattern LINE_BREAKS = Pattern.compile("\\s*\\R\\s*");
    private static final Pattern TRAILING_NUMBER = Pattern.compile("\\d+$");

    private PullRequestDescription() {
    }

    static String bodyFor(int issueNumber, Path worktree) {
        return bodyIfDescribed(issueNumber, worktree).orElseGet(() -> buildBody(issueNumber, FALLBACK));
    }

    /** The body, or empty when the agent wrote no usable description. */
    static Optional<String> bodyIfDescribed(int issueNumber, Path worktree) {
        String text = readFile(worktree.resolve(ScratchFolder.NAME).resolve("description.md")).strip();
        if (text.isEmpty()) {
            return Optional.empty();
        }
        String neutralized = neutralizeNotesHeading(neutralizeClosingKeywords(cutToMaximum(text)));
        return Optional.of(buildBody(issueNumber, neutralized));
    }

    /** Only the section of the factory may have the notes heading, so the heading of the agent loses its marks. */
    private static String neutralizeNotesHeading(String text) {
        return AGENT_NOTES_HEADING.matcher(text).replaceAll("$1");
    }

    /**
     * Replaces the notes section of the body with the notes of the last review round. Without a note, the body has
     * no notes section. Each note is agent text, so it gets one line, a length cut, and no closing keyword.
     */
    static String withReviewNotes(String body, List<ReviewNote> notes) {
        String withoutNotes = removeNotesSection(body);
        if (notes.isEmpty()) {
            return withoutNotes;
        }
        String lines = notes.stream().map(PullRequestDescription::renderNoteLine).collect(Collectors.joining("\n"));
        return withoutNotes + NOTES_HEADING + cutToMaximum(lines);
    }

    private static String removeNotesSection(String body) {
        int sectionStart = body.lastIndexOf(NOTES_HEADING);
        return sectionStart < 0 ? body : body.substring(0, sectionStart);
    }

    private static String renderNoteLine(ReviewNote reviewNote) {
        String kind = reviewNote.kind().name().toLowerCase(Locale.ROOT);
        String text = reviewNote.note().title() + ": " + reviewNote.note().detail();
        String oneLine = LINE_BREAKS.matcher(text.strip()).replaceAll(" ");
        return "- " + kind + ": " + neutralizeClosingKeywords(cutNoteToMaximum(oneLine));
    }

    private static String cutNoteToMaximum(String line) {
        return line.length() <= MAXIMUM_NOTE_CHARACTERS ? line : line.substring(0, MAXIMUM_NOTE_CHARACTERS) + NOTE_CUT;
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
