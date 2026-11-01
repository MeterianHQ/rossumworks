package com.meterian.factory.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.meterian.factory.configuration.ProjectReference;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class HttpGitHubClientTest {

    private static final String TOKEN = "secret-token-value";

    private record RecordedRequest(String method, String target, Map<String, String> headers, String body) {
    }

    private record Answer(int status, String body) {
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
            exchange.sendResponseHeaders(answer.status(), bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) {
                exchange.getResponseBody().write(bytes);
            }
            exchange.close();
        });
        server.start();
        URI apiBase = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        TargetRepository target = new TargetRepository("o", "r", Path.of("clone"), "true", Map.of());
        client = new HttpGitHubClient(apiBase, TOKEN, target, new ProjectReference("org", 3), HttpClient.newHttpClient());
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private static String header(String value) {
        return value == null ? "" : value;
    }

    private static String statusFieldJson(String... optionNames) {
        List<String> options = new ArrayList<>();
        for (String name : optionNames) {
            options.add("{\"id\":\"opt-" + name.replace(' ', '-') + "\",\"name\":\"" + name + "\"}");
        }
        return "{\"data\":{\"organization\":{\"projectV2\":{\"id\":\"proj-1\",\"field\":{\"id\":\"field-1\","
                + "\"options\":[" + String.join(",", options) + "]}}}}}";
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
        assertThat(issues).containsExactly(new Issue(1, "N1", "One", "text", java.util.Set.of("factory")));
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

    @Test
    void setsTheStatusThroughGraphQl() {
        responder = request -> {
            if (request.body().contains("projectV2(number")) {
                return new Answer(200, statusFieldJson("Queued", "In review"));
            }
            return new Answer(200, "{\"data\":{\"addProjectV2ItemById\":{\"item\":{\"id\":\"item-9\"}}}}");
        };

        client.setStatus(new Issue(4, "NODE4", "Title", "", java.util.Set.of()), IssueStatus.IN_REVIEW);

        String allBodies = String.join("\n", requests.stream().map(RecordedRequest::body).toList());
        assertThat(allBodies).contains("addProjectV2ItemById").contains("NODE4")
                .contains("updateProjectV2ItemFieldValue").contains("opt-In-review").contains("item-9");
        assertThat(requests).allSatisfy(request -> assertThat(request.target()).isEqualTo("/graphql"));
    }

    @Test
    void namesTheMissingStatusOption() {
        responder = request -> new Answer(200, statusFieldJson("Queued"));

        assertThatThrownBy(() -> client.setStatus(new Issue(4, "NODE4", "T", "", java.util.Set.of()), IssueStatus.BLOCKED))
                .isInstanceOf(GitHubException.class)
                .hasMessageContaining("the Project field Status has no option Blocked");
    }

    @Test
    void reportsNotMergeableOnA405() {
        responder = request -> new Answer(405, "{\"message\":\"Pull Request is not mergeable\"}");

        assertThat(client.squashMerge(5)).isEqualTo(MergeOutcome.NOT_MERGEABLE);
        assertThat(requests.get(0).method()).isEqualTo("PUT");
        assertThat(requests.get(0).target()).isEqualTo("/repos/o/r/pulls/5/merge");
    }

    @Test
    void squashMergeSendsTheSquashMethod() {
        responder = request -> new Answer(200, "{\"merged\":true}");

        assertThat(client.squashMerge(5)).isEqualTo(MergeOutcome.MERGED);
        assertThat(requests.get(0).body()).contains("\"merge_method\":\"squash\"");
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
}
