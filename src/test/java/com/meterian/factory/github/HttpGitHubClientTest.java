package com.meterian.factory.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.meterian.factory.configuration.TargetRepository;
import com.meterian.factory.state.IssueStatus;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class HttpGitHubClientTest {

    private static final String TOKEN = "secret-token-value";

    private record RecordedRequest(String method, String target, Map<String, String> headers, String body) {
    }

    private record Answer(int status, String body, String link) {
        Answer(int status, String body) {
            this(status, body, null);
        }
    }

    private HttpServer server;
    private final List<RecordedRequest> requests = new CopyOnWriteArrayList<>();
    private Function<RecordedRequest, Answer> responder = request -> new Answer(200, "{}");
    private HttpGitHubClient client;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            RecordedRequest request = new RecordedRequest(exchange.getRequestMethod(),
                    exchange.getRequestURI().toString(),
                    Map.of("Authorization", header(exchange.getRequestHeaders().getFirst("Authorization")),
                            "X-GitHub-Api-Version", header(exchange.getRequestHeaders().getFirst("X-GitHub-Api-Version"))),
                    body);
            requests.add(request);
            Answer answer = responder.apply(request);
            byte[] bytes = answer.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            if (answer.link() != null) {
                exchange.getResponseHeaders().add("Link", answer.link());
            }
            exchange.sendResponseHeaders(answer.status(), bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) {
                exchange.getResponseBody().write(bytes);
            }
            exchange.close();
        });
        server.start();
        URI apiBase = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        TargetRepository target = new TargetRepository("o", "r", Path.of("clone"), "true", Map.of());
        client = new HttpGitHubClient(apiBase, TOKEN, target, HttpClient.newHttpClient());
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
        TargetRepository target = new TargetRepository("o", "r", Path.of("clone"), "true", Map.of());
        HttpGitHubClient slowClient = new HttpGitHubClient(apiBase, TOKEN, target, HttpClient.newHttpClient(),
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
    void findsTheActorOfTheNewestLabelEvent() {
        responder = request -> new Answer(200, "["
                + "{\"event\":\"labeled\",\"created_at\":\"2026-11-01T10:00:00Z\",\"label\":{\"name\":\"factory:approve\"},\"actor\":{\"login\":\"old\"}},"
                + "{\"event\":\"labeled\",\"created_at\":\"2026-11-01T12:00:00Z\",\"label\":{\"name\":\"factory:approve\"},\"actor\":{\"login\":\"new\"}},"
                + "{\"event\":\"labeled\",\"created_at\":\"2026-11-01T13:00:00Z\",\"label\":{\"name\":\"other\"},\"actor\":{\"login\":\"x\"}}]");

        Optional<String> actor = client.lastLabelActor(4, "factory:approve");

        assertThat(actor).contains("new");
        assertThat(requests.get(0).target()).startsWith("/repos/o/r/issues/4/events");
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
                .map(request -> request.method() + " " + request.target()).toList();
    }

    @Test
    void setsTheStatusAsALabel() {
        answerIssueWithLabels("factory:status:queued", "bug");

        client.setStatus(new Issue(4, "T", "", Set.of()), IssueStatus.IN_REVIEW);

        assertThat(changes()).containsExactly("DELETE /repos/o/r/issues/4/labels/factory%3Astatus%3Aqueued",
                "POST /repos/o/r/issues/4/labels");
        assertThat(requests.get(requests.size() - 1).body()).contains("factory:status:in-review");
    }

    @Test
    void keepsOnlyOneStatusLabel() {
        answerIssueWithLabels("factory:status:queued", "factory:status:blocked", "factory:status:done");

        client.setStatus(new Issue(4, "T", "", Set.of()), IssueStatus.DONE);

        assertThat(changes()).containsExactlyInAnyOrder("DELETE /repos/o/r/issues/4/labels/factory%3Astatus%3Aqueued",
                "DELETE /repos/o/r/issues/4/labels/factory%3Astatus%3Ablocked");
    }

    @Test
    void addsNothingWhenTheStatusIsAlreadySet() {
        answerIssueWithLabels("factory:status:in-review", "bug");

        client.setStatus(new Issue(4, "T", "", Set.of()), IssueStatus.IN_REVIEW);

        assertThat(changes()).isEmpty();
    }

    @Test
    void ensuresTheSixStatusLabels() {
        client.ensureStatusLabels();

        assertThat(requests).hasSize(6).allSatisfy(request -> {
            assertThat(request.method()).isEqualTo("POST");
            assertThat(request.target()).isEqualTo("/repos/o/r/labels");
        });
        assertThat(requests.get(0).body()).contains("\"name\":\"factory:status:queued\"")
                .contains("\"description\":\"Factory status: Queued. The factory sets this label.\"");
        assertThat(requests.stream().map(RecordedRequest::body).toList())
                .anyMatch(body -> body.contains("factory:status:waits-for-you"));
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

    private static String labeledEvent(String time, String label, String actorJson) {
        return "{\"event\":\"labeled\",\"created_at\":\"" + time + "\",\"label\":{\"name\":\"" + label
                + "\"},\"actor\":" + actorJson + "}";
    }

    @Test
    void followsTheLinkHeaderToTheNextPage() {
        responder = request -> {
            boolean secondPage = request.target().contains("page=2");
            boolean events = request.target().contains("/events");
            String body = events
                    ? "[" + (secondPage ? labeledEvent("2026-11-01T12:00:00Z", "factory:approve", "{\"login\":\"newest\"}")
                    : labeledEvent("2026-11-01T10:00:00Z", "factory:approve", "{\"login\":\"older\"}")) + "]"
                    : "[{\"number\":" + (secondPage ? 2 : 1) + ",\"node_id\":\"N\",\"title\":\"T\",\"labels\":[]}]";
            String link = secondPage ? null : "<" + baseUrl() + request.target() + "&page=2>; rel=\"next\"";
            return new Answer(200, body, link);
        };

        assertThat(client.lastLabelActor(4, "factory:approve")).contains("newest");
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
    void returnsNoActorWhenNoEventMatches() {
        responder = request -> new Answer(200, "[]");
        assertThat(client.lastLabelActor(4, "factory:approve")).isEmpty();

        responder = request -> new Answer(200, "[" + labeledEvent("2026-11-01T10:00:00Z", "other",
                "{\"login\":\"x\"}") + "]");
        assertThat(client.lastLabelActor(4, "factory:approve")).isEmpty();
    }

    @Test
    void returnsNoActorWhenTheNewestEventHasNoActor() {
        responder = request -> new Answer(200, "["
                + labeledEvent("2026-11-01T10:00:00Z", "factory:approve", "{\"login\":\"old\"}") + ","
                + labeledEvent("2026-11-01T12:00:00Z", "factory:approve", "null") + "]");

        assertThat(client.lastLabelActor(4, "factory:approve")).isEmpty();
    }

    @Test
    void neverPutsTheTokenInAnyError() {
        responder = request -> new Answer(404, "{\"message\":\"Not Found\"}");
        assertThatThrownBy(() -> client.issue(9)).isInstanceOf(GitHubException.class)
                .satisfies(exception -> assertThat(exception.getMessage()).doesNotContain(TOKEN));
    }
}
