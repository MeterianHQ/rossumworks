package com.meterian.factory.state;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.meterian.factory.github.IssueComment;
import java.io.IOException;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public final class StatusComment {

    private static final String RECORD_START = "<!-- factory-record";
    private static final String RECORD_END = "-->";
    private static final String NOT_SET = "—";
    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new Jdk8Module());

    private StatusComment() {
    }

    public static String render(IssueRecord record) {
        StringBuilder body = new StringBuilder();
        body.append(renderHeadline(record)).append('\n');
        body.append(renderReviews(record)).append('\n');
        body.append(renderCounters(record)).append('\n');
        record.notes().forEach(note -> body.append(note).append('\n'));
        body.append('\n').append(RECORD_START).append('\n');
        body.append(writeJson(record)).append('\n');
        body.append(RECORD_END);
        return body.toString();
    }

    public static Optional<IssueRecord> findRecord(List<IssueComment> comments, String workerLogin) {
        return comments.stream()
                .filter(comment -> workerLogin.equals(comment.authorLogin()))
                .sorted(Comparator.comparingLong(IssueComment::id).reversed())
                .map(comment -> readRecord(comment.body()))
                .flatMap(Optional::stream)
                .findFirst();
    }

    private static String renderHeadline(IssueRecord record) {
        String headline = "**Factory status:** " + record.status().displayName() + " — phase " + record.phase();
        return record.waitReason()
                .map(reason -> headline + "\n" + reason.sentence())
                .orElse(headline);
    }

    private static String renderReviews(IssueRecord record) {
        String reviews = Stream.of(ReviewKind.values())
                .map(kind -> kind.name().toLowerCase() + " " + describeVerdict(record, kind))
                .collect(Collectors.joining(", "));
        return "Reviews: " + reviews;
    }

    private static String describeVerdict(IssueRecord record, ReviewKind kind) {
        Verdict verdict = record.reviews().get(kind);
        return verdict == null ? NOT_SET : verdict.name().toLowerCase();
    }

    private static String renderCounters(IssueRecord record) {
        String risk = record.risk().map(level -> level.name().toLowerCase()).orElse(NOT_SET);
        String pullRequest = record.pullRequestNumber().map(number -> "#" + number).orElse(NOT_SET);
        return "Fix loops: " + record.fixLoops() + " · Risk: " + risk + " · Pull request: " + pullRequest;
    }

    private static String writeJson(IssueRecord record) {
        try {
            return MAPPER.writeValueAsString(record).replace("--", "\\u002d\\u002d");
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot write the issue record.", exception);
        }
    }

    private static Optional<IssueRecord> readRecord(String body) {
        int start = body.indexOf("\n" + RECORD_START + "\n");
        if (start < 0) {
            return Optional.empty();
        }
        int jsonStart = start + RECORD_START.length() + 2;
        int end = body.indexOf("\n" + RECORD_END, jsonStart);
        if (end < 0) {
            return Optional.empty();
        }
        try {
            return Optional.of(MAPPER.readValue(body.substring(jsonStart, end), IssueRecord.class));
        } catch (IOException | RuntimeException exception) {
            return Optional.empty();
        }
    }
}
