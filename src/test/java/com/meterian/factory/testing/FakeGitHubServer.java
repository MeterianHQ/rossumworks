package com.meterian.factory.testing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.meterian.factory.github.AssignmentEvent;
import com.meterian.factory.github.CheckRun;
import com.meterian.factory.github.GitHubException;
import com.meterian.factory.github.Issue;
import com.meterian.factory.github.IssueComment;
import com.meterian.factory.github.MergeOutcome;
import com.meterian.factory.github.PullRequest;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A local HTTP server that answers the REST requests of HttpGitHubClient from the state of a FakeGitHubClient.
 * It sends an ETag with each answer to a GET and answers 304 to a request with the same If-None-Match, as GitHub
 * does. It never reaches the network.
 */
public final class FakeGitHubServer implements AutoCloseable {

    private static final Pattern ISSUE = Pattern.compile("/issues/(\\d+)");
    private static final Pattern ISSUE_COMMENTS = Pattern.compile("/issues/(\\d+)/comments");
    private static final Pattern ISSUE_EVENTS = Pattern.compile("/issues/(\\d+)/events");
    private static final Pattern COMMENT = Pattern.compile("/issues/comments/(\\d+)");
    private static final Pattern ISSUE_LABELS = Pattern.compile("/issues/(\\d+)/labels");
    private static final Pattern ISSUE_ASSIGNEES = Pattern.compile("/issues/(\\d+)/assignees");
    private static final Pattern ISSUE_LABEL = Pattern.compile("/issues/(\\d+)/labels/(.+)");
    private static final Pattern PULL_REQUEST = Pattern.compile("/pulls/(\\d+)");
    private static final Pattern BRANCH = Pattern.compile("/branches/([^/]+)");
    private static final Pattern PULL_REQUEST_MERGE = Pattern.compile("/pulls/(\\d+)/merge");
    private static final Pattern MERGE_COMMIT = Pattern.compile("/git/commits/merge-of-(\\d+)");
    private static final Pattern CHECK_RUNS = Pattern.compile("/commits/([0-9a-f]{40})/check-runs");
    private static final Pattern COMMIT_SEGMENT = Pattern.compile("/[0-9a-f]{40}(?=/|$)");
    private static final Pattern NUMBER_SEGMENT = Pattern.compile("/\\d+(?=/|$)");

    private record Answer(int status, JsonNode body) {
    }

    private final FakeGitHubClient gitHub;
    private final String repositoryPath;
    private final HttpServer server;
    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicInteger receivedRequests = new AtomicInteger();
    private final Map<String, Integer> requestCounts = new TreeMap<>();
    private volatile Map<String, String> rateLimitHeaders = Map.of();
    private volatile boolean refusesWithTheRateLimit;
    private volatile boolean refusesTheCheckRunsReads;

    public FakeGitHubServer(FakeGitHubClient gitHub, String owner, String name) {
        this.gitHub = gitHub;
        this.repositoryPath = "/repos/" + owner + "/" + name;
        try {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
        server.createContext("/", this::handle);
        server.start();
    }

    public URI apiBase() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    /** Each answer gets these headers, as GitHub sends them. */
    public void sendRateLimitHeaders(int remaining, Instant reset) {
        rateLimitHeaders = Map.of("X-RateLimit-Remaining", Integer.toString(remaining),
                "X-RateLimit-Reset", Long.toString(reset.getEpochSecond()));
    }

    /** Each request gets a 403 with no remaining requests, until the reset time of the headers. */
    public void refuseWithTheRateLimit(Instant reset) {
        sendRateLimitHeaders(0, reset);
        refusesWithTheRateLimit = true;
    }

    /** Each read of the checks of a commit gets a 403 with no remaining requests. The other requests pass. */
    public void refuseTheCheckRunsReadsWithTheRateLimit(Instant reset) {
        sendRateLimitHeaders(0, reset);
        refusesTheCheckRunsReads = true;
    }

    public void answerNormally() {
        refusesTheCheckRunsReads = false;
        refusesWithTheRateLimit = false;
        rateLimitHeaders = Map.of();
    }

    public int receivedRequestCount() {
        return receivedRequests.get();
    }

    /** The requests that the server received, by method and path template. A 304 answer has its own key. */
    public synchronized Map<String, Integer> requestCounts() {
        return Map.copyOf(requestCounts);
    }

    public synchronized void resetRequestCounts() {
        requestCounts.clear();
    }

    private synchronized void countRequest(String method, URI uri, int status) {
        String withCommitTemplate = COMMIT_SEGMENT.matcher(uri.getRawPath().replace(repositoryPath, "/repos/{o}/{r}"))
                .replaceAll("/{sha}");
        String template = NUMBER_SEGMENT.matcher(withCommitTemplate).replaceAll("/{n}");
        requestCounts.merge(method + " " + template + (status == 304 ? " (304)" : ""), 1, Integer::sum);
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            receivedRequests.incrementAndGet();
            String method = exchange.getRequestMethod();
            URI uri = exchange.getRequestURI();
            JsonNode payload = readPayload(exchange);
            Answer answer = refusesWithTheRateLimit || isRefusedCheckRunsRead(uri)
                    ? new Answer(403, mapper.createObjectNode().put("message", "API rate limit exceeded"))
                    : answerOrFail(method, uri, payload);
            byte[] body = mapper.writeValueAsBytes(answer.body());
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            rateLimitHeaders.forEach(exchange.getResponseHeaders()::add);
            if ("GET".equals(method) && answer.status() == 200) {
                String entityTag = "\"" + hash(body) + "\"";
                exchange.getResponseHeaders().add("ETag", entityTag);
                if (entityTag.equals(exchange.getRequestHeaders().getFirst("If-None-Match"))) {
                    countRequest(method, uri, 304);
                    exchange.sendResponseHeaders(304, -1);
                    return;
                }
            }
            countRequest(method, uri, answer.status());
            exchange.sendResponseHeaders(answer.status(), body.length);
            exchange.getResponseBody().write(body);
        }
    }

    private boolean isRefusedCheckRunsRead(URI uri) {
        return refusesTheCheckRunsReads && CHECK_RUNS.matcher(uri.getRawPath()).find();
    }

    private JsonNode readPayload(HttpExchange exchange) throws IOException {
        byte[] bytes = exchange.getRequestBody().readAllBytes();
        return bytes.length == 0 ? mapper.createObjectNode() : mapper.readTree(bytes);
    }

    /** A failure of the fake, for example a scripted failure, becomes an answer 502. */
    private Answer answerOrFail(String method, URI uri, JsonNode payload) {
        try {
            return answer(method, uri, payload);
        } catch (GitHubException exception) {
            return new Answer(502, mapper.createObjectNode().put("message", exception.getMessage()));
        }
    }

    private Answer answer(String method, URI uri, JsonNode payload) {
        String path = uri.getRawPath();
        if (path.equals("/user")) {
            return ok(mapper.createObjectNode().put("login", gitHub.authenticatedLogin()));
        }
        if (!path.startsWith(repositoryPath)) {
            return notFound();
        }
        String route = path.substring(repositoryPath.length());
        Map<String, String> query = readQuery(uri);
        return switch (method) {
            case "GET" -> answerRead(route, query);
            case "POST" -> answerCreate(route, payload);
            case "PATCH" -> answerEdit(route, payload);
            case "DELETE" -> answerDelete(route, payload);
            case "PUT" -> answerMerge(route, payload);
            default -> notFound();
        };
    }

    private Answer answerRead(String route, Map<String, String> query) {
        Matcher matcher;
        if (route.equals("/issues")) {
            return ok(toIssueArray(gitHub.openIssuesWithLabel(query.getOrDefault("labels", ""))));
        }
        if ((matcher = ISSUE.matcher(route)).matches()) {
            return ok(toIssueJson(gitHub.issue(number(matcher))));
        }
        if ((matcher = ISSUE_COMMENTS.matcher(route)).matches()) {
            return ok(toCommentArray(gitHub.comments(number(matcher))));
        }
        if ((matcher = ISSUE_EVENTS.matcher(route)).matches()) {
            return ok(toAssignmentEventArray(gitHub.assignmentEvents(number(matcher))));
        }
        if (route.equals("/pulls")) {
            String branch = query.getOrDefault("head", "").replaceFirst("^[^:]*:", "");
            ArrayNode pullRequests = mapper.createArrayNode();
            gitHub.openPullRequest(branch).ifPresent(pullRequest -> pullRequests.add(toPullRequestJson(pullRequest)));
            return ok(pullRequests);
        }
        if ((matcher = BRANCH.matcher(route)).matches()) {
            return gitHub.findBranchHead(URLDecoder.decode(matcher.group(1), StandardCharsets.UTF_8))
                    .map(this::toBranchJson).map(this::ok).orElseGet(this::notFound);
        }
        if ((matcher = PULL_REQUEST.matcher(route)).matches()) {
            return findPullRequest(number(matcher)).map(this::toFullPullRequestJson).map(this::ok)
                    .orElseGet(this::notFound);
        }
        if ((matcher = CHECK_RUNS.matcher(route)).matches()) {
            return ok(toCheckRunsJson(gitHub.checkRuns(matcher.group(1))));
        }
        if ((matcher = MERGE_COMMIT.matcher(route)).matches()) {
            return gitHub.mergeCommitTree(number(matcher))
                    .map(tree -> ok(mapper.createObjectNode().set("tree", mapper.createObjectNode().put("sha", tree))))
                    .orElseGet(this::notFound);
        }
        return notFound();
    }

    private JsonNode toCheckRunsJson(List<CheckRun> checkRuns) {
        ObjectNode answer = mapper.createObjectNode().put("total_count", checkRuns.size());
        ArrayNode items = answer.putArray("check_runs");
        for (CheckRun checkRun : checkRuns) {
            ObjectNode item = items.addObject().put("name", checkRun.name()).put("status", checkRun.status())
                    .put("html_url", checkRun.url());
            checkRun.conclusion().ifPresentOrElse(conclusion -> item.put("conclusion", conclusion),
                    () -> item.putNull("conclusion"));
        }
        return answer;
    }

    private JsonNode toBranchJson(String head) {
        ObjectNode branch = mapper.createObjectNode();
        branch.putObject("commit").put("sha", head);
        return branch;
    }

    private Answer answerCreate(String route, JsonNode payload) {
        Matcher matcher;
        if (route.equals("/labels")) {
            return new Answer(201, mapper.createObjectNode());
        }
        if ((matcher = ISSUE_COMMENTS.matcher(route)).matches()) {
            long id = gitHub.createComment(number(matcher), payload.path("body").asText());
            return new Answer(201, mapper.createObjectNode().put("id", id));
        }
        if ((matcher = ISSUE_LABELS.matcher(route)).matches()) {
            int issueNumber = number(matcher);
            payload.path("labels").forEach(label -> gitHub.addLabel(issueNumber, label.asText()));
            return ok(mapper.createArrayNode());
        }
        if ((matcher = ISSUE_ASSIGNEES.matcher(route)).matches()) {
            int issueNumber = number(matcher);
            payload.path("assignees").forEach(login -> gitHub.addAssignee(issueNumber, login.asText()));
            return new Answer(201, toIssueJson(gitHub.issue(issueNumber)));
        }
        if (route.equals("/pulls")) {
            PullRequest created = gitHub.createPullRequest(payload.path("head").asText(),
                    payload.path("base").asText(), payload.path("title").asText(), payload.path("body").asText());
            return new Answer(201, toPullRequestJson(created));
        }
        return notFound();
    }

    private Answer answerEdit(String route, JsonNode payload) {
        Matcher matcher;
        if ((matcher = COMMENT.matcher(route)).matches()) {
            gitHub.editComment(Long.parseLong(matcher.group(1)), payload.path("body").asText());
            return ok(mapper.createObjectNode());
        }
        if ((matcher = PULL_REQUEST.matcher(route)).matches()) {
            gitHub.editPullRequestBody(number(matcher), payload.path("body").asText());
            return ok(mapper.createObjectNode());
        }
        return notFound();
    }

    private Answer answerDelete(String route, JsonNode payload) {
        Matcher assigneesMatcher = ISSUE_ASSIGNEES.matcher(route);
        if (assigneesMatcher.matches()) {
            int issueNumber = number(assigneesMatcher);
            payload.path("assignees").forEach(login -> gitHub.removeAssignee(issueNumber, login.asText()));
            return ok(toIssueJson(gitHub.issue(issueNumber)));
        }
        Matcher matcher = ISSUE_LABEL.matcher(route);
        if (!matcher.matches()) {
            return notFound();
        }
        int issueNumber = number(matcher);
        String label = URLDecoder.decode(matcher.group(2), StandardCharsets.UTF_8);
        if (!gitHub.labelsOf(issueNumber).contains(label)) {
            return notFound();
        }
        gitHub.removeLabel(issueNumber, label);
        return ok(mapper.createArrayNode());
    }

    private Answer answerMerge(String route, JsonNode payload) {
        Matcher matcher = PULL_REQUEST_MERGE.matcher(route);
        if (!matcher.matches()) {
            return notFound();
        }
        MergeOutcome outcome = gitHub.squashMerge(number(matcher), payload.path("sha").asText());
        return switch (outcome) {
            case MERGED -> ok(mapper.createObjectNode().put("merged", true));
            case NOT_MERGEABLE -> new Answer(405, mapper.createObjectNode());
            case HEAD_CHANGED -> new Answer(409, mapper.createObjectNode());
        };
    }

    private Optional<PullRequest> findPullRequest(int number) {
        return gitHub.pullRequests().stream().filter(pullRequest -> pullRequest.number() == number).findFirst();
    }

    private ArrayNode toIssueArray(List<Issue> issues) {
        ArrayNode array = mapper.createArrayNode();
        issues.forEach(issue -> array.add(toIssueJson(issue)));
        return array;
    }

    private ObjectNode toIssueJson(Issue issue) {
        ObjectNode json = mapper.createObjectNode().put("number", issue.number()).put("title", issue.title())
                .put("body", issue.body());
        ArrayNode labels = json.putArray("labels");
        gitHub.labelsOf(issue.number()).stream().sorted()
                .forEach(label -> labels.add(mapper.createObjectNode().put("name", label)));
        ArrayNode assignees = json.putArray("assignees");
        gitHub.assigneesOf(issue.number()).stream().sorted()
                .forEach(login -> assignees.add(mapper.createObjectNode().put("login", login)));
        return json;
    }

    private ArrayNode toAssignmentEventArray(List<AssignmentEvent> events) {
        ArrayNode array = mapper.createArrayNode();
        for (AssignmentEvent event : events) {
            ObjectNode json = mapper.createObjectNode().put("id", event.id()).put("event", "assigned")
                    .put("created_at", event.createdAt().toString());
            json.set("assignee", mapper.createObjectNode().put("login", event.assignee()));
            array.add(json);
        }
        return array;
    }

    private ArrayNode toCommentArray(List<IssueComment> comments) {
        ArrayNode array = mapper.createArrayNode();
        for (IssueComment comment : comments) {
            ObjectNode json = mapper.createObjectNode().put("id", comment.id()).put("body", comment.body());
            json.set("user", mapper.createObjectNode().put("login", comment.authorLogin()));
            array.add(json);
        }
        return array;
    }

    private ObjectNode toPullRequestJson(PullRequest pullRequest) {
        ObjectNode json = mapper.createObjectNode().put("number", pullRequest.number())
                .put("html_url", pullRequest.url()).put("body", pullRequest.body());
        json.set("head", mapper.createObjectNode().put("ref", pullRequest.headBranch()));
        return json;
    }

    private ObjectNode toFullPullRequestJson(PullRequest pullRequest) {
        int number = pullRequest.number();
        ObjectNode json = toPullRequestJson(pullRequest).put("merged", gitHub.isMerged(number));
        ((ObjectNode) json.get("head")).put("sha", gitHub.pullRequestHead(number));
        if (gitHub.mergeCommitTree(number).isPresent()) {
            json.put("merge_commit_sha", "merge-of-" + number);
        } else {
            json.putNull("merge_commit_sha");
        }
        return json;
    }

    private Answer ok(JsonNode body) {
        return new Answer(200, body);
    }

    private Answer notFound() {
        return new Answer(404, mapper.createObjectNode().put("message", "Not Found"));
    }

    private static int number(Matcher matcher) {
        return Integer.parseInt(matcher.group(1));
    }

    private static Map<String, String> readQuery(URI uri) {
        String rawQuery = uri.getRawQuery();
        if (rawQuery == null || rawQuery.isEmpty()) {
            return Map.of();
        }
        Map<String, String> query = new TreeMap<>();
        for (String pair : rawQuery.split("&")) {
            String[] parts = pair.split("=", 2);
            query.put(parts[0], parts.length > 1 ? URLDecoder.decode(parts[1], StandardCharsets.UTF_8) : "");
        }
        return query;
    }

    private static String hash(byte[] body) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
