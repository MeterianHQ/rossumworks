package com.meterian.factory.state;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.meterian.factory.definition.ColumnNames;
import com.meterian.factory.github.IssueComment;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

public final class StatusComment {

    private static final String HEADLINE_START = "**Factory status:**";
    private static final String RECORD_START = "<!-- factory-record";
    private static final String RECORD_END = "-->";
    private static final String NOT_SET = "—";
    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new Jdk8Module());

    private StatusComment() {
    }

    /**
     * @param limits      the limit of each state with a limit, by state name, in the order of the workflow
     * @param reviewNames the names of the reviews of the definition, in its order
     */
    public static String render(IssueRecord record, Map<String, Integer> limits, List<String> reviewNames) {
        StringBuilder body = new StringBuilder();
        body.append(renderHeadline(record)).append('\n');
        body.append(renderReviews(record, reviewNames)).append('\n');
        body.append(renderCounters(record, limits)).append('\n');
        describeRisk(record).forEach(line -> body.append(line).append('\n'));
        renderLiveLink(record).ifPresent(line -> body.append(line).append('\n'));
        record.notes().forEach(note -> body.append(sanitizeLine(note)).append('\n'));
        body.append('\n').append(RECORD_START).append('\n');
        body.append(writeJson(record)).append('\n');
        body.append(RECORD_END);
        return body.toString();
    }

    /** Reads the record of the newest status comment of the worker. Other comments of the worker do not count. */
    public static Optional<IssueRecord> findRecord(List<IssueComment> comments, String workerLogin) {
        return findNewestStatusComment(comments, workerLogin).flatMap(comment -> readRecord(comment.body()));
    }

    /** True when the newest status comment of the worker has a record that does not parse. */
    public static boolean hasBrokenRecord(List<IssueComment> comments, String workerLogin) {
        return findNewestStatusComment(comments, workerLogin)
                .filter(comment -> readRecord(comment.body()).isEmpty())
                .isPresent();
    }

    /** Changes the HTML comment markers, so that a text cannot open or close a hidden block. */
    public static String neutralizeCommentMarkers(String text) {
        return text.replace("<!--", "<! --").replace("-->", "-- >");
    }

    private static Optional<IssueComment> findNewestStatusComment(List<IssueComment> comments, String workerLogin) {
        return comments.stream()
                .filter(comment -> workerLogin.equals(comment.authorLogin()))
                .filter(StatusComment::isStatusComment)
                .max(Comparator.comparingLong(IssueComment::id));
    }

    /** Returns the id of the newest status comment of the worker. */
    public static Optional<Long> findNewestStatusCommentId(List<IssueComment> comments, String workerLogin) {
        return findNewestStatusComment(comments, workerLogin).map(IssueComment::id);
    }

    /** Reads the records of the status comments that other accounts wrote, the newest first. */
    public static List<AuthoredRecord> findRecordsOfOtherAuthors(List<IssueComment> comments, String workerLogin) {
        return comments.stream()
                .filter(comment -> !workerLogin.equalsIgnoreCase(comment.authorLogin()))
                .filter(StatusComment::isStatusComment)
                .sorted(Comparator.comparingLong(IssueComment::id).reversed())
                .flatMap(comment -> readRecord(comment.body()).stream()
                        .map(record -> new AuthoredRecord(comment.id(), comment.authorLogin(), record)))
                .toList();
    }

    private static boolean isStatusComment(IssueComment comment) {
        return comment.body().startsWith(HEADLINE_START) && comment.body().contains(RECORD_START);
    }

    /** Makes one line of a text that cannot open or close a hidden block. */
    public static String sanitizeLine(String text) {
        return neutralizeCommentMarkers(text.replaceAll("\\R", " "));
    }

    private static String renderHeadline(IssueRecord record) {
        return HEADLINE_START + " " + sanitizeLine(ColumnNames.displayName(record.column())) + " — state "
                + sanitizeLine(record.state());
    }

    private static String renderReviews(IssueRecord record, List<String> reviewNames) {
        if (reviewNames.isEmpty()) {
            return "Reviews: " + NOT_SET;
        }
        String reviews = reviewNames.stream()
                .map(reviewName -> sanitizeLine(reviewName) + " " + describeVerdict(record, reviewName))
                .collect(Collectors.joining(", "));
        return "Reviews: " + reviews;
    }

    private static String describeVerdict(IssueRecord record, String reviewName) {
        Verdict verdict = record.reviews().get(reviewName);
        return verdict == null ? NOT_SET : verdict.name().toLowerCase();
    }

    private static String renderCounters(IssueRecord record, Map<String, Integer> limits) {
        String risk = record.risk().map(level -> level.name().toLowerCase()).orElse(NOT_SET);
        String pullRequest = record.pullRequestNumber().map(number -> "#" + number).orElse(NOT_SET);
        return "Entries: " + describeEntries(record, limits) + " · Risk: " + risk + " · Pull request: " + pullRequest;
    }

    /** The line "risk: high, not small" and one line "- reason" for each reason. Empty before the risk result. */
    public static List<String> describeRisk(IssueRecord record) {
        return record.risk().map(level -> {
            String size = record.small() ? "small" : "not small";
            List<String> lines = new ArrayList<>();
            lines.add("risk: " + level.name().toLowerCase() + ", " + size);
            record.riskReasons().forEach(reason -> lines.add("- " + sanitizeLine(reason)));
            return List.copyOf(lines);
        }).orElse(List.of());
    }

    /** Each state with a limit, for example "implement 1 of 3". */
    public static String describeEntries(IssueRecord record, Map<String, Integer> limits) {
        if (limits.isEmpty()) {
            return NOT_SET;
        }
        return limits.entrySet().stream()
                .map(limit -> sanitizeLine(limit.getKey()) + " " + record.entriesOf(limit.getKey()) + " of "
                        + limit.getValue())
                .collect(Collectors.joining(", "));
    }

    private static Optional<String> renderLiveLink(IssueRecord record) {
        return record.live().flatMap(LiveSession::link).map(link -> "Live conversation: " + sanitizeLine(link));
    }

    private static String writeJson(IssueRecord record) {
        try {
            return MAPPER.writeValueAsString(record).replace("--", "\\u002d\\u002d");
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot write the issue record.", exception);
        }
    }

    private static Optional<IssueRecord> readRecord(String body) {
        String trimmed = body.stripTrailing();
        if (!trimmed.endsWith(RECORD_END)) {
            return Optional.empty();
        }
        String marker = "\n" + RECORD_START + "\n";
        int start = trimmed.lastIndexOf(marker);
        if (start < 0) {
            return Optional.empty();
        }
        String json = trimmed.substring(start + marker.length(), trimmed.length() - RECORD_END.length());
        try {
            JsonNode tree = MAPPER.readTree(json);
            if (isRecordOfMain(tree)) {
                return MainRecordReader.read(tree);
            }
            return Optional.of(MAPPER.treeToValue(tree, IssueRecord.class));
        } catch (IOException | RuntimeException exception) {
            return Optional.empty();
        }
    }

    /** Main writes the property phase and no property column. */
    private static boolean isRecordOfMain(JsonNode tree) {
        return tree.has("phase") && !tree.has("column");
    }
}
