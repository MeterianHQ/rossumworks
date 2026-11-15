package com.meterian.factory.github;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meterian.factory.configuration.TargetRepository;
import com.meterian.factory.state.IssueStatus;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class HttpGitHubClient implements GitHubClient {

    /** Enough for the issue list and the comments of many issues, and small in memory. */
    static final int CACHED_LIST_LIMIT = 200;
    private static final int NOT_MODIFIED = 304;
    /** The page size of each list request, the maximum of GitHub. */
    private static final int PAGE_SIZE = 100;
    private static final String API_VERSION = "2022-11-28";
    private static final Pattern NEXT_LINK = Pattern.compile("<([^>]+)>;\\s*rel=\"next\"");

    /** A request that gets no answer in this time fails, so that a poll never waits without an end. */
    static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(60);

    private final URI apiBase;
    private final String token;
    private final String repositoryPath;
    private final HttpClient httpClient;
    private final Duration requestTimeout;
    private final ObjectMapper mapper = new ObjectMapper();
    private final GitHubRequestCounter requestCounter;
    private final GitHubRateLimit rateLimit;
    private final ConditionalRequestCache conditionalCache = new ConditionalRequestCache(CACHED_LIST_LIMIT);
    private final StatusLabelMemory statusLabels = new StatusLabelMemory();

    public HttpGitHubClient(URI apiBase, String token, TargetRepository target, HttpClient httpClient) {
        this(apiBase, token, target, httpClient, Clock.systemUTC());
    }

    public HttpGitHubClient(URI apiBase, String token, TargetRepository target, HttpClient httpClient, Clock clock) {
        this(apiBase, token, target, httpClient, REQUEST_TIMEOUT, clock);
    }

    HttpGitHubClient(URI apiBase, String token, TargetRepository target, HttpClient httpClient,
                     Duration requestTimeout) {
        this(apiBase, token, target, httpClient, requestTimeout, Clock.systemUTC());
    }

    HttpGitHubClient(URI apiBase, String token, TargetRepository target, HttpClient httpClient,
                     Duration requestTimeout, Clock clock) {
        this.apiBase = apiBase;
        this.token = token;
        this.repositoryPath = "/repos/" + target.fullName();
        this.httpClient = httpClient;
        this.requestTimeout = requestTimeout;
        this.requestCounter = new GitHubRequestCounter(repositoryPath);
        this.rateLimit = new GitHubRateLimit(clock);
    }

    /** The number of requests since the start or the last reset, by method and path template. */
    public Map<String, Integer> requestCounts() {
        return requestCounter.counts();
    }

    public void resetRequestCounts() {
        requestCounter.reset();
    }

    @Override
    public Optional<Instant> findLowRemainingReset() {
        return rateLimit.findLowRemainingReset();
    }

    @Override
    public Map<String, Integer> takeRequestCounts() {
        return requestCounter.take();
    }

    @Override
    public String authenticatedLogin() {
        return send("GET", "/user", null).path("login").asText();
    }

    @Override
    public List<Issue> openIssuesWithLabel(String label) {
        List<Issue> issues = new ArrayList<>();
        for (JsonNode item : readAllPagesConditionally(repositoryPath + "/issues?labels=" + encode(label)
                + "&state=open&per_page=" + PAGE_SIZE)) {
            if (!item.has("pull_request")) {
                issues.add(toIssue(item));
            }
        }
        return issues;
    }

    @Override
    public Issue issue(int number) {
        return toIssue(send("GET", repositoryPath + "/issues/" + number, null));
    }

    @Override
    public List<IssueComment> comments(int issueNumber) {
        List<IssueComment> comments = new ArrayList<>();
        for (JsonNode item : readAllPagesConditionally(repositoryPath + "/issues/" + issueNumber
                + "/comments?per_page=" + PAGE_SIZE)) {
            comments.add(new IssueComment(item.path("id").asLong(), item.path("user").path("login").asText(),
                    item.path("body").asText("")));
        }
        return comments;
    }

    @Override
    public long createComment(int issueNumber, String body) {
        return send("POST", repositoryPath + "/issues/" + issueNumber + "/comments", Map.of("body", body))
                .path("id").asLong();
    }

    @Override
    public void editComment(long commentId, String body) {
        send("PATCH", repositoryPath + "/issues/comments/" + commentId, Map.of("body", body));
    }

    @Override
    public void addAssignee(int issueNumber, String login) {
        send("POST", repositoryPath + "/issues/" + issueNumber + "/assignees", Map.of("assignees", List.of(login)));
    }

    @Override
    public void removeAssignee(int issueNumber, String login) {
        String path = repositoryPath + "/issues/" + issueNumber + "/assignees";
        HttpResponse<String> response = sendRaw("DELETE", path, Map.of("assignees", List.of(login)));
        if (response.statusCode() != 404) {
            requireSuccess("DELETE", path, response);
        }
    }

    @Override
    public List<AssignmentEvent> assignmentEvents(int issueNumber) {
        List<AssignmentEvent> assignmentEvents = new ArrayList<>();
        String eventsPath = repositoryPath + "/issues/" + issueNumber + "/events?per_page=" + PAGE_SIZE;
        for (JsonNode event : readAllPages(eventsPath)) {
            if ("assigned".equals(event.path("event").asText())) {
                assignmentEvents.add(new AssignmentEvent(event.path("id").asLong(),
                        event.path("assignee").path("login").asText(),
                        Instant.parse(event.path("created_at").asText())));
            }
        }
        return List.copyOf(assignmentEvents);
    }

    @Override
    public void ensureLabel(String label, String color, String description) {
        HttpResponse<String> response = sendRaw("POST", repositoryPath + "/labels",
                Map.of("name", label, "color", color, "description", description));
        boolean alreadyExists = response.statusCode() == 422;
        if (!alreadyExists) {
            requireSuccess("POST", repositoryPath + "/labels", response);
        }
    }

    @Override
    public void addLabel(int issueNumber, String label) {
        send("POST", repositoryPath + "/issues/" + issueNumber + "/labels", Map.of("labels", List.of(label)));
    }

    @Override
    public void removeLabel(int issueNumber, String label) {
        String path = repositoryPath + "/issues/" + issueNumber + "/labels/" + encode(label);
        HttpResponse<String> response = sendRaw("DELETE", path, null);
        if (response.statusCode() != 404) {
            requireSuccess("DELETE", path, response);
        }
    }

    /** The caller knows the labels of the issue, so the client does not read the issue again. */
    @Override
    public void setStatus(Issue issue, IssueStatus status) {
        String wantedLabel = status.labelName();
        for (String label : statusLabels.findPossibleStatusLabels(issue)) {
            if (!label.equals(wantedLabel)) {
                removeLabel(issue.number(), label);
            }
        }
        if (!statusLabels.isSurelyOnTheIssue(issue, wantedLabel)) {
            addLabel(issue.number(), wantedLabel);
        }
        statusLabels.remember(issue.number(), wantedLabel);
    }

    @Override
    public Optional<PullRequest> openPullRequest(String headBranch) {
        String head = encode(repositoryOwner() + ":" + headBranch);
        return readAllPages(repositoryPath + "/pulls?head=" + head + "&state=open&per_page=" + PAGE_SIZE).stream()
                .findFirst().map(this::toPullRequest);
    }

    @Override
    public PullRequest createPullRequest(String headBranch, String baseBranch, String title, String body) {
        JsonNode created = send("POST", repositoryPath + "/pulls",
                Map.of("head", headBranch, "base", baseBranch, "title", title, "body", body));
        return toPullRequest(created);
    }

    @Override
    public void editPullRequestBody(int pullRequestNumber, String body) {
        send("PATCH", repositoryPath + "/pulls/" + pullRequestNumber, Map.of("body", body));
    }

    @Override
    public Optional<String> findBranchHead(String branch) {
        String path = repositoryPath + "/branches/" + encode(branch);
        HttpResponse<String> response = sendRaw("GET", path, null);
        if (response.statusCode() == 404) {
            return Optional.empty();
        }
        requireSuccess("GET", path, response);
        return Optional.of(parse(response.body()).path("commit").path("sha").asText()).filter(sha -> !sha.isBlank());
    }

    @Override
    public boolean isMerged(int pullRequestNumber) {
        return send("GET", repositoryPath + "/pulls/" + pullRequestNumber, null).path("merged").asBoolean(false);
    }

    @Override
    public String pullRequestHead(int pullRequestNumber) {
        JsonNode head = send("GET", repositoryPath + "/pulls/" + pullRequestNumber, null).path("head").path("sha");
        if (!head.isTextual() || head.asText().isBlank()) {
            throw new GitHubException("GitHub sent the pull request #" + pullRequestNumber + " without a head commit.");
        }
        return head.asText();
    }

    @Override
    public Optional<String> mergeCommitTree(int pullRequestNumber) {
        JsonNode mergeCommit = send("GET", repositoryPath + "/pulls/" + pullRequestNumber, null)
                .path("merge_commit_sha");
        if (!mergeCommit.isTextual()) {
            return Optional.empty();
        }
        JsonNode tree = send("GET", repositoryPath + "/git/commits/" + mergeCommit.asText(), null)
                .path("tree").path("sha");
        return tree.isTextual() ? Optional.of(tree.asText()) : Optional.empty();
    }

    @Override
    public MergeOutcome squashMerge(int pullRequestNumber, String expectedHeadSha) {
        String path = repositoryPath + "/pulls/" + pullRequestNumber + "/merge";
        HttpResponse<String> response = sendRaw("PUT", path, Map.of("merge_method", "squash", "sha", expectedHeadSha));
        if (response.statusCode() == 405) {
            return MergeOutcome.NOT_MERGEABLE;
        }
        if (response.statusCode() == 409) {
            return MergeOutcome.HEAD_CHANGED;
        }
        requireSuccess("PUT", path, response);
        return MergeOutcome.MERGED;
    }

    private String repositoryOwner() {
        return repositoryPath.split("/")[2];
    }

    private Issue toIssue(JsonNode item) {
        Set<String> labels = new LinkedHashSet<>();
        for (JsonNode label : item.path("labels")) {
            labels.add(label.path("name").asText());
        }
        Set<String> assignees = new LinkedHashSet<>();
        for (JsonNode assignee : item.path("assignees")) {
            assignees.add(assignee.path("login").asText());
        }
        return new Issue(item.path("number").asInt(), item.path("title").asText(), item.path("body").asText(""),
                labels, assignees);
    }

    private PullRequest toPullRequest(JsonNode item) {
        return new PullRequest(item.path("number").asInt(), item.path("html_url").asText(),
                item.path("head").path("ref").asText(), item.path("body").asText(""));
    }

    private List<JsonNode> readAllPages(String firstPath) {
        List<JsonNode> items = new ArrayList<>();
        URI next = apiBase.resolve(firstPath);
        while (next != null) {
            HttpResponse<String> response = sendRaw("GET", next, null);
            requireSuccess("GET", next.getRawPath(), response);
            parse(response.body()).forEach(items::add);
            next = nextPage(response.headers().firstValue("Link"));
        }
        return items;
    }

    /** Sends the ETag of the last answer of each page. A 304 answer gives the cached page. */
    private List<JsonNode> readAllPagesConditionally(String firstPath) {
        List<JsonNode> items = new ArrayList<>();
        URI next = apiBase.resolve(firstPath);
        while (next != null) {
            ConditionalRequestCache.CachedPage page = readPageConditionally(next);
            parse(page.body()).forEach(items::add);
            next = nextPage(page.link());
        }
        return items;
    }

    private ConditionalRequestCache.CachedPage readPageConditionally(URI uri) {
        String cacheKey = uri.getRawPath() + "?" + uri.getRawQuery();
        Optional<ConditionalRequestCache.CachedPage> cached = conditionalCache.find(cacheKey);
        HttpResponse<String> response = sendRaw("GET", uri, null,
                cached.map(ConditionalRequestCache.CachedPage::entityTag));
        if (response.statusCode() == NOT_MODIFIED && cached.isPresent()) {
            return cached.get();
        }
        requireSuccess("GET", uri.getRawPath(), response);
        ConditionalRequestCache.CachedPage page = new ConditionalRequestCache.CachedPage(
                response.headers().firstValue("ETag").orElse(""), response.body(),
                response.headers().firstValue("Link"));
        if (!page.entityTag().isEmpty() && !isFullLastPage(page)) {
            conditionalCache.store(cacheKey, page);
        } else {
            conditionalCache.remove(cacheKey);
        }
        return page;
    }

    /**
     * A full page without a next link can get a next page later, and a 304 answer would hide it. So such a page
     * is never cached.
     */
    private boolean isFullLastPage(ConditionalRequestCache.CachedPage page) {
        boolean hasNextPage = NEXT_LINK.matcher(page.link().orElse("")).find();
        return !hasNextPage && parse(page.body()).size() >= PAGE_SIZE;
    }

    private URI nextPage(Optional<String> linkHeader) {
        Matcher matcher = NEXT_LINK.matcher(linkHeader.orElse(""));
        if (!matcher.find()) {
            return null;
        }
        URI next = URI.create(matcher.group(1));
        boolean sameServer = next.getScheme().equals(apiBase.getScheme()) && next.getHost().equals(apiBase.getHost())
                && next.getPort() == apiBase.getPort();
        if (!sameServer) {
            throw new GitHubException("GitHub sent a next page link to another server: " + next);
        }
        return next;
    }

    private JsonNode send(String method, String path, Object payload) {
        HttpResponse<String> response = sendRaw(method, path, payload);
        requireSuccess(method, path, response);
        return parse(response.body());
    }

    private HttpResponse<String> sendRaw(String method, String path, Object payload) {
        return sendRaw(method, apiBase.resolve(path), payload);
    }

    private HttpResponse<String> sendRaw(String method, URI uri, Object payload) {
        return sendRaw(method, uri, payload, Optional.empty());
    }

    private HttpResponse<String> sendRaw(String method, URI uri, Object payload, Optional<String> entityTag) {
        try {
            HttpRequest.BodyPublisher publisher = payload == null ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(payload));
            HttpRequest.Builder request = HttpRequest.newBuilder(uri)
                    .method(method, publisher)
                    .timeout(requestTimeout)
                    .header("Authorization", "Bearer " + token)
                    .header("Accept", "application/vnd.github+json")
                    .header("Content-Type", "application/json")
                    .header("X-GitHub-Api-Version", API_VERSION);
            entityTag.ifPresent(tag -> request.header("If-None-Match", tag));
            rateLimit.verifyRequestIsAllowed();
            HttpResponse<String> response = sendAndCount(method, uri, request.build());
            rateLimit.readAnswer(response.statusCode(), response.headers());
            return response;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new GitHubException("the request " + method + " " + uri.getRawPath() + " was interrupted");
        } catch (IOException exception) {
            throw new GitHubException("the request " + method + " " + uri.getRawPath() + " failed: "
                    + exception.getClass().getSimpleName());
        }
    }

    /** A request that fails without an answer still counts. A 304 answer counts under its own key. */
    private HttpResponse<String> sendAndCount(String method, URI uri, HttpRequest request)
            throws IOException, InterruptedException {
        try {
            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            requestCounter.count(method, uri, response.statusCode() == NOT_MODIFIED);
            return response;
        } catch (IOException exception) {
            requestCounter.count(method, uri, false);
            throw exception;
        }
    }

    private void requireSuccess(String method, String path, HttpResponse<String> response) {
        if (response.statusCode() / 100 != 2) {
            throw new GitHubException("GitHub answered " + response.statusCode() + " for " + method + " " + path);
        }
    }

    private JsonNode parse(String body) {
        try {
            return body == null || body.isBlank() ? mapper.createObjectNode() : mapper.readTree(body);
        } catch (JsonProcessingException exception) {
            throw new GitHubException("GitHub sent an answer that is not JSON");
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
