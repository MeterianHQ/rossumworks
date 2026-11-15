package com.meterian.factory.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.meterian.factory.configuration.RepositoryName;
import com.meterian.factory.state.ColumnLabels;
import com.meterian.factory.testing.MutableClock;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class HttpGitHubClientTest {

    private static final String TOKEN = "secret-token-value";
    private static final String LABEL_CREATION = "POST /repos/o/r/labels";
    private static final Instant NOW = Instant.parse("2026-11-01T10:00:00Z");
    private static final Instant RESET = Instant.parse("2026-11-01T10:20:00Z");

    private record RecordedRequest(String method, String target, Map<String, String> headers, String body) {
    }

    private record Answer(int status, String body, String link, Map<String, String> headers) {
        Answer(int status, String body) {
            this(status, body, null, Map.of());
        }

        Answer(int status, String body, String link) {
            this(status, body, link, Map.of());
        }

        Answer(int status, String body, Map<String, String> headers) {
            this(status, body, null, headers);
        }
    }

    private HttpServer server;
    private final List<RecordedRequest> requests = new CopyOnWriteArrayList<>();
    private Function<RecordedRequest, Answer> responder = request -> new Answer(200, "{}");
    private HttpGitHubClient client;
    private final MutableClock clock = new MutableClock(NOW);

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            RecordedRequest request = new RecordedRequest(exchange.getRequestMethod(),
                    exchange.getRequestURI().toString(),
                    Map.of("Authorization", header(exchange.getRequestHeaders().getFirst("Authorization")),
                            "X-GitHub-Api-Version", header(exchange.getRequestHeaders().getFirst("X-GitHub-Api-Version")),
                            "If-None-Match", header(exchange.getRequestHeaders().getFirst("If-None-Match"))),
                    body);
            requests.add(request);
            Answer answer = responder.apply(request);
            byte[] bytes = answer.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            if (answer.link() != null) {
                exchange.getResponseHeaders().add("Link", answer.link());
            }
            answer.headers().forEach(exchange.getResponseHeaders()::add);
            exchange.sendResponseHeaders(answer.status(), bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) {
                exchange.getResponseBody().write(bytes);
            }
            exchange.close();
        });
        server.start();
        URI apiBase = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        RepositoryName repository = new RepositoryName("o", "r");
        client = new HttpGitHubClient(apiBase, TOKEN, repository, HttpClient.newHttpClient(),
                HttpGitHubClient.REQUEST_TIMEOUT, clock);
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private static String header(String value) {
        return value == null ? "" : value;
    }

    @Test
    void usesARequestTimeoutOfSixtySeconds() {
        assertThat(HttpGitHubClient.REQUEST_TIMEOUT).isEqualTo(Duration.ofSeconds(60));
    }

    @Test
    void stopsARequestAfterTheTimeout() {
        responder = request -> {
            sleepQuietly(Duration.ofSeconds(3));
            return new Answer(200, "{\"login\":\"bot\"}");
        };
        URI apiBase = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        RepositoryName repository = new RepositoryName("o", "r");
        HttpGitHubClient slowClient = new HttpGitHubClient(apiBase, TOKEN, repository, HttpClient.newHttpClient(),
                Duration.ofMillis(300));

        assertThatThrownBy(slowClient::authenticatedLogin)
                .isInstanceOf(GitHubException.class)
                .hasMessageContaining("HttpTimeoutException")
                .hasMessageNotContaining(TOKEN);
    }

    private static void sleepQuietly(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    void sendsTheTokenAndTheApiVersion() {
        responder = request -> new Answer(200, "{\"login\":\"bot\"}");

        assertThat(client.authenticatedLogin()).isEqualTo("bot");

        RecordedRequest request = requests.get(0);
        assertThat(request.headers()).containsEntry("Authorization", "Bearer " + TOKEN)
                .containsEntry("X-GitHub-Api-Version", "2022-11-28");
    }

    @Test
    void listsTheOpenIssuesWithTheLabel() {
        responder = request -> new Answer(200, "["
                + "{\"number\":1,\"node_id\":\"N1\",\"title\":\"One\",\"body\":\"text\",\"labels\":[{\"name\":\"factory\"}]},"
                + "{\"number\":2,\"node_id\":\"N2\",\"title\":\"Pull\",\"body\":null,\"labels\":[],\"pull_request\":{}}]");

        List<Issue> issues = client.openIssuesWithLabel("factory");

        assertThat(requests.get(0).target()).startsWith("/repos/o/r/issues?labels=factory&state=open");
        assertThat(issues).containsExactly(new Issue(1, "One", "text", Set.of("factory")));
    }

    @Test
    void issueReadsTheAssignees() {
        responder = request -> new Answer(200, "{\"number\":7,\"title\":\"T\",\"body\":\"B\",\"labels\":[],"
                + "\"assignees\":[{\"login\":\"alice\"},{\"login\":\"bob\"}]}");

        Issue issue = client.issue(7);

        assertThat(issue.assignees()).isEqualTo(Set.of("alice", "bob"));
    }

    @Test
    void addAssigneeSendsTheLogin() {
        client.addAssignee(7, "alice");

        RecordedRequest request = requests.get(0);
        assertThat(request.method()).isEqualTo("POST");
        assertThat(request.target()).isEqualTo("/repos/o/r/issues/7/assignees");
        assertThat(request.body()).isEqualTo("{\"assignees\":[\"alice\"]}");
    }

    @Test
    void removeAssigneeSendsTheLogin() {
        client.removeAssignee(7, "alice");

        RecordedRequest request = requests.get(0);
        assertThat(request.method()).isEqualTo("DELETE");
        assertThat(request.target()).isEqualTo("/repos/o/r/issues/7/assignees");
        assertThat(request.body()).isEqualTo("{\"assignees\":[\"alice\"]}");
    }

    @Test
    void assignmentEventsReadOnlyAssignedEvents() {
        responder = request -> new Answer(200, "["
                + "{\"id\":10,\"event\":\"labeled\",\"created_at\":\"2026-11-15T09:00:00Z\"},"
                + "{\"id\":11,\"event\":\"assigned\",\"created_at\":\"2026-11-15T10:00:00Z\","
                + "\"assignee\":{\"login\":\"alice\"}},"
                + "{\"id\":12,\"event\":\"unassigned\",\"created_at\":\"2026-11-15T11:00:00Z\","
                + "\"assignee\":{\"login\":\"alice\"}}]");

        List<AssignmentEvent> events = client.assignmentEvents(7);

        assertThat(events).isEqualTo(List.of(new AssignmentEvent(11, "alice", Instant.parse("2026-11-15T10:00:00Z"))));
    }

    @Test
    void addAssigneeOnAPullRequestSendsTheLogin() {
        responder = request -> new Answer(201, "{}");

        client.addAssignee(12, "bbossola");

        RecordedRequest request = requests.get(0);
        assertThat(request.method()).isEqualTo("POST");
        assertThat(request.target()).isEqualTo("/repos/o/r/issues/12/assignees");
        assertThat(request.body()).isEqualTo("{\"assignees\":[\"bbossola\"]}");
    }

    @Test
    void removeLabelAcceptsAMissingLabel() {
        responder = request -> new Answer(404, "{\"message\":\"Label does not exist\"}");

        assertThatCode(() -> client.removeLabel(4, "factory:approve")).doesNotThrowAnyException();
        assertThat(requests.get(0).method()).isEqualTo("DELETE");
    }

    private static String issueJson(String... labelNames) {
        List<String> labels = new ArrayList<>();
        for (String name : labelNames) {
            labels.add("{\"name\":\"" + name + "\"}");
        }
        return "{\"number\":4,\"node_id\":\"N4\",\"title\":\"T\",\"body\":\"\",\"labels\":["
                + String.join(",", labels) + "]}";
    }

    private void answerIssueWithLabels(String... labelNames) {
        responder = request -> "GET".equals(request.method()) ? new Answer(200, issueJson(labelNames))
                : new Answer(200, "[]");
    }

    private List<String> changes() {
        return requests.stream().filter(request -> !"GET".equals(request.method()))
                .map(request -> request.method() + " " + request.target())
                .filter(change -> !change.equals(LABEL_CREATION)).toList();
    }

    private static Issue issueWithLabels(String... labelNames) {
        return new Issue(4, "T", "", Set.of(labelNames));
    }

    private List<String> reads() {
        return requests.stream().filter(request -> "GET".equals(request.method())).map(RecordedRequest::target)
                .toList();
    }

    @Test
    void setsTheColumnAsALabel() {
        answerIssueWithLabels();

        client.setColumn(issueWithLabels("factory:is:queued", "bug"), "in-review");

        assertThat(reads()).isEmpty();
        assertThat(changes()).containsExactly("DELETE /repos/o/r/issues/4/labels/factory%3Ais%3Aqueued",
                "POST /repos/o/r/issues/4/labels");
        assertThat(requests.get(requests.size() - 1).body()).contains("factory:is:in-review");
    }

    @Test
    void keepsOnlyOneColumnLabel() {
        answerIssueWithLabels();

        client.setColumn(issueWithLabels("factory:is:queued", "factory:is:blocked", "factory:is:done"),
                "done");

        assertThat(changes()).containsExactlyInAnyOrder("DELETE /repos/o/r/issues/4/labels/factory%3Ais%3Aqueued",
                "DELETE /repos/o/r/issues/4/labels/factory%3Ais%3Ablocked");
    }

    @Test
    void addsNothingWhenTheColumnIsAlreadySet() {
        answerIssueWithLabels();

        client.setColumn(issueWithLabels("factory:is:in-review", "bug"), "in-review");

        assertThat(changes()).isEmpty();
    }

    @Test
    void remembersTheColumnLabelThatItSetWhenTheCallerHasOlderLabels() {
        answerIssueWithLabels();
        Issue readBeforeTheAction = issueWithLabels("factory:is:in-progress");
        client.setColumn(readBeforeTheAction, "waits-for-you");
        requests.clear();

        client.setColumn(readBeforeTheAction, "in-progress");

        assertThat(reads()).isEmpty();
        assertThat(changes()).containsExactly(
                "DELETE /repos/o/r/issues/4/labels/factory%3Ais%3Awaits-for-you",
                "POST /repos/o/r/issues/4/labels");
        assertThat(requests.get(requests.size() - 1).body()).contains("factory:is:in-progress");
    }

    @Test
    void setsTheLabelOfTheColumnAndRemovesTheOldStatusLabel() {
        answerIssueWithLabels();

        client.setColumn(issueWithLabels("factory:status:queued", "bug"), "in-review");

        assertThat(changes()).containsExactlyInAnyOrder("DELETE /repos/o/r/issues/4/labels/factory%3Astatus%3Aqueued",
                "POST /repos/o/r/issues/4/labels");
        assertThat(requests.get(requests.size() - 1).body()).contains("factory:is:in-review");
    }

    @Test
    void createsTheLabelOfANewColumnOnce() {
        answerIssueWithLabels();

        client.setColumn(issueWithLabels(), "needs-me");
        client.setColumn(issueWithLabels("factory:is:needs-me"), "needs-me");
        client.setColumn(issueWithLabels("factory:is:needs-me"), "done");

        List<RecordedRequest> creations = requests.stream()
                .filter(request -> "POST".equals(request.method()) && request.target().equals("/repos/o/r/labels"))
                .toList();
        assertThat(creations).hasSize(2);
        assertThat(creations.get(0).body()).contains("\"name\":\"factory:is:needs-me\"")
                .contains("\"color\":\"" + ColumnLabels.colorOf("needs-me") + "\"");
        assertThat(creations.get(1).body()).contains("\"name\":\"factory:is:done\"");
    }

    @Test
    void writesTheDisplayNameOfAFreeColumn() {
        answerIssueWithLabels();

        client.setColumn(issueWithLabels(), "needs-me");

        assertThat(requests.get(0).body())
                .contains("\"description\":\"Factory column: Needs me. The factory sets this label.\"");
    }

    @Test
    void reportsNotMergeableOnA405() {
        responder = request -> new Answer(405, "{\"message\":\"Pull Request is not mergeable\"}");

        assertThat(client.squashMerge(5, "abc123")).isEqualTo(MergeOutcome.NOT_MERGEABLE);
        assertThat(requests.get(0).method()).isEqualTo("PUT");
        assertThat(requests.get(0).target()).isEqualTo("/repos/o/r/pulls/5/merge");
    }

    @Test
    void squashMergeSendsTheSquashMethod() {
        responder = request -> new Answer(200, "{\"merged\":true}");

        assertThat(client.squashMerge(5, "abc123")).isEqualTo(MergeOutcome.MERGED);
        assertThat(requests.get(0).body()).contains("\"merge_method\":\"squash\"");
    }

    @Test
    void squashMergePinsTheHeadWithTheSha() {
        responder = request -> new Answer(200, "{\"merged\":true}");

        client.squashMerge(5, "abc123");

        assertThat(requests.get(0).body()).contains("\"sha\":\"abc123\"");
    }

    @Test
    void editsTheBodyOfAPullRequest() {
        responder = request -> new Answer(200, "{\"number\":5}");

        client.editPullRequestBody(5, "A \"new\" body");

        assertThat(requests.get(0).method()).isEqualTo("PATCH");
        assertThat(requests.get(0).target()).isEqualTo("/repos/o/r/pulls/5");
        assertThat(requests.get(0).body()).isEqualTo("{\"body\":\"A \\\"new\\\" body\"}");
    }

    @Test
    void neverPutsTheTokenInAnErrorOfAnEditOfAPullRequestBody() {
        responder = request -> new Answer(500, "{\"message\":\"boom\"}");

        assertThatThrownBy(() -> client.editPullRequestBody(5, "Body"))
                .isInstanceOf(GitHubException.class)
                .hasMessageContaining("PATCH")
                .satisfies(exception -> assertThat(exception.getMessage()).doesNotContain(TOKEN));
    }

    @Test
    void readsTheHeadOfABranch() {
        responder = request -> new Answer(200, "{\"name\":\"factory/issue-7\",\"commit\":{\"sha\":\"abc\"}}");

        assertThat(client.findBranchHead("factory/issue-7")).contains("abc");
        assertThat(requests.get(requests.size() - 1).target()).isEqualTo("/repos/o/r/branches/factory%2Fissue-7");
    }

    @Test
    void aBranchThatGitHubDoesNotHaveHasNoHead() {
        responder = request -> new Answer(404, "{\"message\":\"Branch not found\"}");

        assertThat(client.findBranchHead("factory/issue-7")).isEmpty();
    }

    @Test
    void readsTheBodyOfAPullRequest() {
        responder = request -> new Answer(200, "[{\"number\":5,\"html_url\":\"u\",\"head\":{\"ref\":\"b\"},"
                + "\"body\":\"The body\"}]");

        assertThat(client.openPullRequest("b")).map(PullRequest::body).contains("The body");
    }

    @Test
    void readsTheMergedFieldOfThePullRequest() {
        responder = request -> new Answer(200, "{\"number\":5,\"merged\":true}");

        assertThat(client.isMerged(5)).isTrue();
        assertThat(requests.get(0).method()).isEqualTo("GET");
        assertThat(requests.get(0).target()).isEqualTo("/repos/o/r/pulls/5");
    }

    @Test
    void readsAPullRequestWithoutTheMergedFieldAsNotMerged() {
        responder = request -> new Answer(200, "{\"number\":5}");

        assertThat(client.isMerged(5)).isFalse();
    }

    @Test
    void readsTheHeadCommitOfThePullRequest() {
        responder = request -> new Answer(200,
                "{\"number\":5,\"head\":{\"ref\":\"factory/issue-7\",\"sha\":\"abc123\"}}");

        assertThat(client.pullRequestHead(5)).isEqualTo("abc123");
        assertThat(requests.get(0).method()).isEqualTo("GET");
        assertThat(requests.get(0).target()).isEqualTo("/repos/o/r/pulls/5");
    }

    @Test
    void failsWhenThePullRequestHasNoHeadCommit() {
        responder = request -> new Answer(200, "{\"number\":5}");

        assertThatThrownBy(() -> client.pullRequestHead(5)).isInstanceOf(GitHubException.class)
                .hasMessage("GitHub sent the pull request #5 without a head commit.");
    }

    @Test
    void readsTheTreeOfTheMergeCommit() {
        responder = request -> request.target().equals("/repos/o/r/pulls/5")
                ? new Answer(200, "{\"number\":5,\"merged\":true,\"merge_commit_sha\":\"c0ffee\"}")
                : new Answer(200, "{\"sha\":\"c0ffee\",\"tree\":{\"sha\":\"7ree\"}}");

        assertThat(client.mergeCommitTree(5)).contains("7ree");
        assertThat(requests).extracting(RecordedRequest::target)
                .containsExactly("/repos/o/r/pulls/5", "/repos/o/r/git/commits/c0ffee");
    }

    @Test
    void readsNoMergeTreeWhenThePullRequestHasNoMergeCommit() {
        responder = request -> new Answer(200, "{\"number\":5,\"merged\":false,\"merge_commit_sha\":null}");

        assertThat(client.mergeCommitTree(5)).isEmpty();
        assertThat(requests).hasSize(1);
    }

    @Test
    void reportsAChangedHeadOnA409() {
        responder = request -> new Answer(409, "{\"message\":\"Head branch was modified\"}");

        assertThat(client.squashMerge(5, "abc123")).isEqualTo(MergeOutcome.HEAD_CHANGED);
    }

    @Test
    void failsWithTheStatusAndThePath() {
        responder = request -> new Answer(500, "{\"message\":\"boom\"}");

        assertThatThrownBy(() -> client.authenticatedLogin())
                .isInstanceOf(GitHubException.class)
                .hasMessageContaining("500")
                .hasMessageContaining("/user");
    }

    @Test
    void neverPutsTheTokenInAnError() {
        responder = request -> new Answer(500, "{\"message\":\"boom\"}");

        assertThatThrownBy(() -> client.authenticatedLogin())
                .isInstanceOf(GitHubException.class)
                .satisfies(exception -> assertThat(exception.getMessage()).doesNotContain(TOKEN));
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @Test
    void followsTheLinkHeaderToTheNextPage() {
        responder = request -> {
            boolean secondPage = request.target().contains("page=2");
            String body = "[{\"number\":" + (secondPage ? 2 : 1) + ",\"node_id\":\"N\",\"title\":\"T\",\"labels\":[]}]";
            String link = secondPage ? null : "<" + baseUrl() + request.target() + "&page=2>; rel=\"next\"";
            return new Answer(200, body, link);
        };

        assertThat(client.openIssuesWithLabel("factory")).extracting(Issue::number).containsExactly(1, 2);
    }

    @Test
    void refusesANextLinkToAnotherHost() {
        String otherHostLink = "http://localhost:" + server.getAddress().getPort() + "/repos/o/r/issues?page=2";
        responder = request -> new Answer(200, "[]", requests.size() == 1 ? "<" + otherHostLink + ">; rel=\"next\"" : null);

        assertThatThrownBy(() -> client.openIssuesWithLabel("factory"))
                .isInstanceOf(GitHubException.class)
                .hasMessageContaining(otherHostLink)
                .satisfies(exception -> assertThat(exception.getMessage()).doesNotContain(TOKEN));
        assertThat(requests).hasSize(1);
    }

    @Test
    void countsEachRequestByMethodAndPathTemplate() {
        responder = request -> {
            boolean firstCommentPage = request.target().contains("/comments") && !request.target().contains("page=2");
            String link = firstCommentPage ? "<" + baseUrl() + request.target() + "&page=2>; rel=\"next\"" : null;
            return new Answer(200, request.target().contains("/comments") ? commentsJson(1, 100) : issueJson(), link);
        };

        client.issue(4);
        client.issue(12);
        client.comments(4);

        assertThat(client.requestCounts()).containsExactlyInAnyOrderEntriesOf(Map.of(
                "GET /repos/{o}/{r}/issues/{n}", 2,
                "GET /repos/{o}/{r}/issues/{n}/comments", 2));
    }

    @Test
    void resetsTheRequestCounts() {
        responder = request -> new Answer(200, issueJson());
        client.issue(4);

        client.resetRequestCounts();
        client.issue(4);

        assertThat(client.requestCounts()).containsExactlyEntriesOf(Map.of("GET /repos/{o}/{r}/issues/{n}", 1));
    }

    @Test
    void neverPutsTheTokenInAnyError() {
        responder = request -> new Answer(404, "{\"message\":\"Not Found\"}");
        assertThatThrownBy(() -> client.issue(9)).isInstanceOf(GitHubException.class)
                .satisfies(exception -> assertThat(exception.getMessage()).doesNotContain(TOKEN));
    }

    private static Map<String, String> rateLimitHeaders(int remaining, Instant reset) {
        return Map.of("X-RateLimit-Remaining", Integer.toString(remaining),
                "X-RateLimit-Reset", Long.toString(reset.getEpochSecond()));
    }

    @Test
    void raisesTheRateLimitWithTheResetTimeOnA403WithoutRemainingRequests() {
        responder = request -> new Answer(403, "{\"message\":\"API rate limit exceeded\"}",
                rateLimitHeaders(0, RESET));

        assertThatThrownBy(() -> client.issue(4))
                .isInstanceOf(GitHubRateLimitException.class)
                .satisfies(exception -> {
                    assertThat(((GitHubRateLimitException) exception).resetTime()).isEqualTo(RESET);
                    assertThat(exception.getMessage()).doesNotContain(TOKEN);
                });
    }

    @Test
    void waitsTheRetryAfterTimeOfA429() {
        responder = request -> new Answer(429, "{\"message\":\"secondary rate limit\"}",
                Map.of("Retry-After", "30"));

        assertThatThrownBy(() -> client.issue(4))
                .isInstanceOf(GitHubRateLimitException.class)
                .satisfies(exception -> assertThat(((GitHubRateLimitException) exception).resetTime())
                        .isEqualTo(NOW.plusSeconds(30)));

        responder = request -> new Answer(200, issueJson());
        clock.set(NOW.plusSeconds(29));
        assertThatThrownBy(() -> client.issue(4)).isInstanceOf(GitHubRateLimitException.class);
        assertThat(requests).hasSize(1);

        clock.set(NOW.plusSeconds(30));
        assertThat(client.issue(4).number()).isEqualTo(4);
        assertThat(requests).hasSize(2);
    }

    @Test
    void reportsTheResetWhenFewRequestsRemainButStillSends() {
        responder = request -> new Answer(200, issueJson(), rateLimitHeaders(50, RESET));

        client.issue(4);
        client.issue(4);

        assertThat(requests).hasSize(2);
        assertThat(client.findLowRemainingReset()).contains(RESET);

        clock.set(RESET);
        assertThat(client.findLowRemainingReset()).isEmpty();
    }

    @Test
    void continuesWhenEnoughRequestsRemain() {
        responder = request -> new Answer(200, issueJson(), rateLimitHeaders(100, RESET));

        client.issue(4);
        client.issue(4);

        assertThat(requests).hasSize(2);
        assertThat(client.findLowRemainingReset()).isEmpty();
    }

    @Test
    void treatsA403WithoutARateLimitHeaderAsAnOrdinaryFailure() {
        responder = request -> new Answer(403, "{\"message\":\"Resource not accessible\"}",
                Map.of("X-RateLimit-Remaining", "4000"));

        assertThatThrownBy(() -> client.issue(4))
                .isInstanceOf(GitHubException.class)
                .isNotInstanceOf(GitHubRateLimitException.class)
                .hasMessageContaining("403");
    }

    private static Answer answerWithEntityTag(String entityTag, String body) {
        return new Answer(200, body, Map.of("ETag", entityTag));
    }

    private Function<RecordedRequest, Answer> answerNotModifiedFor(String entityTag, String body) {
        return request -> entityTag.equals(request.headers().get("If-None-Match")) ? new Answer(304, "")
                : answerWithEntityTag(entityTag, body);
    }

    @Test
    void reusesTheIssueListOnANotModifiedAnswer() {
        responder = answerNotModifiedFor("\"list-1\"",
                "[{\"number\":1,\"title\":\"One\",\"body\":\"text\",\"labels\":[{\"name\":\"factory\"}]}]");

        List<Issue> first = client.openIssuesWithLabel("factory");
        List<Issue> second = client.openIssuesWithLabel("factory");

        assertThat(second).isEqualTo(first).extracting(Issue::number).containsExactly(1);
        assertThat(requests.get(0).headers().get("If-None-Match")).isEmpty();
        assertThat(requests.get(1).headers().get("If-None-Match")).isEqualTo("\"list-1\"");
        assertThat(client.requestCounts()).containsExactlyInAnyOrderEntriesOf(Map.of(
                "GET /repos/{o}/{r}/issues", 1,
                "GET /repos/{o}/{r}/issues (304)", 1));
    }

    @Test
    void reusesTheCommentsOnANotModifiedAnswer() {
        responder = answerNotModifiedFor("\"comments-1\"",
                "[{\"id\":9,\"user\":{\"login\":\"bot\"},\"body\":\"Hello\"}]");

        client.comments(4);
        List<IssueComment> second = client.comments(4);

        assertThat(second).containsExactly(new IssueComment(9, "bot", "Hello"));
        assertThat(requests.get(1).headers().get("If-None-Match")).isEqualTo("\"comments-1\"");
        assertThat(client.requestCounts()).containsEntry("GET /repos/{o}/{r}/issues/{n}/comments (304)", 1);
    }

    @Test
    void followsTheCachedNextLinkOnANotModifiedPage() {
        responder = request -> {
            boolean secondPage = request.target().contains("page=2");
            String entityTag = secondPage ? "\"page-2\"" : "\"page-1\"";
            if (entityTag.equals(request.headers().get("If-None-Match"))) {
                return new Answer(304, "");
            }
            String body = "[{\"number\":" + (secondPage ? 2 : 1) + ",\"title\":\"T\",\"labels\":[]}]";
            Map<String, String> headers = secondPage ? Map.of("ETag", entityTag)
                    : Map.of("ETag", entityTag, "Link", "<" + baseUrl() + request.target() + "&page=2>; rel=\"next\"");
            return new Answer(200, body, headers);
        };

        client.openIssuesWithLabel("factory");
        List<Issue> second = client.openIssuesWithLabel("factory");

        assertThat(second).extracting(Issue::number).containsExactly(1, 2);
        assertThat(requests).hasSize(4);
        assertThat(client.requestCounts()).containsEntry("GET /repos/{o}/{r}/issues (304)", 2);
    }

    @Test
    void keepsTheEntityTagsOfTheLastTwoHundredLists() {
        responder = request -> answerWithEntityTag("\"tag\"", "[]");
        for (int issueNumber = 1; issueNumber <= HttpGitHubClient.CACHED_LIST_LIMIT + 1; issueNumber++) {
            client.comments(issueNumber);
        }
        requests.clear();

        client.comments(1);
        client.comments(HttpGitHubClient.CACHED_LIST_LIMIT + 1);

        assertThat(requests.get(0).headers().get("If-None-Match")).isEmpty();
        assertThat(requests.get(1).headers().get("If-None-Match")).isEqualTo("\"tag\"");
    }

    @Test
    void sendsNoEntityTagForOneIssue() {
        responder = request -> answerWithEntityTag("\"tag\"", issueJson());

        client.issue(4);
        client.issue(4);

        assertThat(requests).extracting(request -> request.headers().get("If-None-Match")).containsOnly("");
    }

    private static String commentsJson(int firstId, int count) {
        List<String> comments = new ArrayList<>();
        for (int id = firstId; id < firstId + count; id++) {
            comments.add("{\"id\":" + id + ",\"user\":{\"login\":\"bot\"},\"body\":\"c\"}");
        }
        return "[" + String.join(",", comments) + "]";
    }

    @Test
    void readsTheNewPageAfterAFullLastPage() {
        boolean[] hasTheNewComment = {false};
        responder = request -> {
            if (request.target().contains("page=2")) {
                return new Answer(200, commentsJson(101, 1), Map.of("ETag", "\"page-2\""));
            }
            if ("\"page-1\"".equals(request.headers().get("If-None-Match"))) {
                return new Answer(304, "");
            }
            Map<String, String> headers = hasTheNewComment[0]
                    ? Map.of("ETag", "\"page-1\"", "Link", "<" + baseUrl() + request.target() + "&page=2>; rel=\"next\"")
                    : Map.of("ETag", "\"page-1\"");
            return new Answer(200, commentsJson(1, 100), headers);
        };
        assertThat(client.comments(4)).hasSize(100);

        hasTheNewComment[0] = true;

        assertThat(client.comments(4)).hasSize(101).last().extracting(IssueComment::id).isEqualTo(101L);
    }
}
