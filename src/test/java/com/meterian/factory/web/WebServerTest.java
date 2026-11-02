package com.meterian.factory.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.meterian.factory.agent.LiveContextReader;
import com.meterian.factory.board.BoardState;
import com.meterian.factory.configuration.ClaudeSettings;
import com.meterian.factory.configuration.FactoryConfiguration;
import com.meterian.factory.configuration.IsolationMode;
import com.meterian.factory.configuration.Limits;
import com.meterian.factory.configuration.TargetRepository;
import com.meterian.factory.configuration.WebSettings;
import com.meterian.factory.github.GitHubException;
import com.meterian.factory.github.Issue;
import com.meterian.factory.state.IssueRecord;
import com.meterian.factory.state.IssueStatus;
import com.meterian.factory.state.LiveSession;
import com.meterian.factory.state.Phase;
import com.meterian.factory.state.WaitReason;
import com.meterian.factory.testing.FakeLiveSessions;
import com.meterian.factory.testing.MutableClock;
import com.meterian.factory.usage.ContextTracker;
import com.meterian.factory.usage.UsageTracker;
import com.meterian.factory.web.login.PasswordHash;
import com.meterian.factory.web.login.PasswordLogin;
import com.meterian.factory.workflow.PageRequests;
import com.meterian.factory.workflow.WorkspaceLayout;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WebServerTest {

    private static final Instant NOW = Instant.parse("2026-11-02T10:00:00Z");
    private static final String PASSWORD = "correct horse battery";
    private static final int FAST_ITERATIONS = 1000;
    private static final int ISSUE_NUMBER = 12;
    private static final String APPROVE_LABEL = "factory:approve";
    private static final String EXPECTED_POLICY = "default-src 'none'; script-src 'self'; style-src 'self'; "
            + "connect-src 'self'; form-action 'self'; frame-ancestors 'none'; base-uri 'none'";

    /** The arguments of one call of addLabel. */
    private record LabelCall(int issueNumber, String label) {
    }

    @TempDir
    Path temporaryDirectory;

    private final MutableClock clock = new MutableClock(NOW);
    private final BoardState board = new BoardState(clock);
    private final FakeLiveSessions liveSessions = new FakeLiveSessions();
    private final PageRequests pageRequests = new PageRequests(clock);
    private final List<LabelCall> labelCalls = new CopyOnWriteArrayList<>();
    private volatile Optional<GitHubException> labelFailure = Optional.empty();
    private final HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

    private WebServer server;

    @BeforeEach
    void startServer() throws IOException {
        PasswordLogin login = new PasswordLogin(PasswordHash.create(PASSWORD.toCharArray(), FAST_ITERATIONS), clock,
                new SecureRandom());
        LabelAdder labels = (issueNumber, label) -> {
            labelCalls.add(new LabelCall(issueNumber, label));
            labelFailure.ifPresent(failure -> {
                throw failure;
            });
        };
        PageActions actions = new PageActions(labels, pageRequests, liveSessions, board);
        WebSettings settings = new WebSettings("127.0.0.1", 0, PasswordHash.create(PASSWORD.toCharArray(),
                FAST_ITERATIONS));
        server = new WebServer(settings, login, createModelSource(), new BoardPage(ZoneOffset.UTC), board, actions);
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop();
    }

    private PageModelSource createModelSource() {
        Path workDirectory = temporaryDirectory.resolve("work");
        TargetRepository target = new TargetRepository("MeterianHQ", "target", temporaryDirectory.resolve("clone"),
                "true", Map.of());
        FactoryConfiguration configuration = new FactoryConfiguration(target, "main",
                new Limits(3, 4, Duration.ofSeconds(45), 0.90), List.of("bbossola"), IsolationMode.SAME_USER,
                ClaudeSettings.createDefaults(), workDirectory, temporaryDirectory.resolve("gatekeeper.git"),
                Optional.empty(), temporaryDirectory.resolve("live"), Optional.empty());
        return new PageModelSource(board, new UsageTracker(0.90, clock), new ContextTracker(),
                new LiveContextReader(temporaryDirectory.resolve("claude-home")), liveSessions,
                new WorkspaceLayout(workDirectory), configuration, () -> 1, clock);
    }

    private static IssueRecord mergeWaitsForApproval() {
        return IssueRecord.queued(ISSUE_NUMBER).withAgentSessionStarted().withStatus(IssueStatus.WAITS_FOR_YOU)
                .withPhase(Phase.MERGE).withWaitReason(WaitReason.MERGE_NEEDS_APPROVAL);
    }

    private static IssueRecord waitsForAnAnswer() {
        return IssueRecord.queued(ISSUE_NUMBER).withAgentSessionStarted().withStatus(IssueStatus.WAITS_FOR_YOU)
                .withPhase(Phase.IMPLEMENT).withWaitReason(WaitReason.QUESTION);
    }

    private static IssueRecord liveConversation() {
        return IssueRecord.queued(ISSUE_NUMBER).withAgentSessionStarted().withStatus(IssueStatus.WAITS_FOR_YOU)
                .withPhase(Phase.IMPLEMENT).withWaitReason(WaitReason.LIVE_CONVERSATION)
                .withLive(new LiveSession("abc", IssueStatus.IN_PROGRESS, Optional.empty(), Optional.empty()));
    }

    private static Issue issue() {
        return new Issue(ISSUE_NUMBER, "Fix the login", "body", Set.of("factory"));
    }

    private void recordCard(IssueRecord record) {
        board.recordIssue(issue(), record);
    }

    private URI address(String path) {
        return URI.create("http://127.0.0.1:" + server.port() + path);
    }

    private String sameOrigin() {
        return "http://127.0.0.1:" + server.port();
    }

    private HttpResponse<String> send(HttpRequest request) {
        try {
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    private HttpResponse<String> get(String path, Optional<String> cookie) {
        HttpRequest.Builder request = HttpRequest.newBuilder(address(path)).GET();
        cookie.ifPresent(value -> request.header("Cookie", value));
        return send(request.build());
    }

    private HttpResponse<String> post(String path, Optional<String> cookie, String origin) {
        HttpRequest.Builder request = HttpRequest.newBuilder(address(path))
                .POST(HttpRequest.BodyPublishers.noBody())
                .header("Origin", origin);
        cookie.ifPresent(value -> request.header("Cookie", value));
        return send(request.build());
    }

    private HttpResponse<String> postPassword(String origin) {
        return send(HttpRequest.newBuilder(address("/login"))
                .POST(HttpRequest.BodyPublishers.ofString("password=" + PASSWORD.replace(' ', '+')))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Origin", origin)
                .build());
    }

    private Optional<String> logIn() {
        HttpResponse<String> response = postPassword(sameOrigin());
        String setCookie = response.headers().firstValue("Set-Cookie").orElseThrow();
        return Optional.of(setCookie.substring(0, setCookie.indexOf(';')));
    }

    private HttpResponse<InputStream> openEvents(Optional<String> cookie) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(address("/events")).GET();
        cookie.ifPresent(value -> request.header("Cookie", value));
        return client.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
    }

    private static CompletableFuture<Boolean> readUntilLine(InputStream stream, String expectedLine) {
        return CompletableFuture.supplyAsync(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.equals(expectedLine)) {
                        return true;
                    }
                }
                return false;
            } catch (IOException exception) {
                return false;
            }
        });
    }

    @Test
    void thePageNeedsTheLogin() {
        HttpResponse<String> page = get("/", Optional.empty());

        assertThat(page.statusCode()).isEqualTo(303);
        assertThat(page.headers().firstValue("Location")).contains("/login");
        assertThat(get("/board", Optional.empty()).statusCode()).isEqualTo(401);
        assertThat(get("/events", Optional.empty()).statusCode()).isEqualTo(401);
    }

    @Test
    void aForgedCookieGivesNoAccess() {
        assertThat(get("/board", Optional.of("factory_session=forged")).statusCode()).isEqualTo(401);
    }

    @Test
    void theLoginOpensThePage() {
        recordCard(mergeWaitsForApproval());
        Optional<String> cookie = logIn();

        HttpResponse<String> page = get("/", cookie);

        assertThat(page.statusCode()).isEqualTo(200);
        assertThat(page.body()).contains("#12");
        assertThat(page.headers().firstValue("Cache-Control")).contains("no-store");
    }

    @Test
    void theBoardGivesTheFragmentOfTheBoard() {
        recordCard(mergeWaitsForApproval());

        HttpResponse<String> fragment = get("/board", logIn());

        assertThat(fragment.statusCode()).isEqualTo(200);
        assertThat(fragment.body()).contains("#12").doesNotContain("<html");
    }

    @Test
    void thePageMakesNoGitHubRequest() throws Exception {
        recordCard(mergeWaitsForApproval());
        Optional<String> cookie = logIn();
        for (int request = 0; request < 3; request++) {
            assertThat(get("/", cookie).statusCode()).isEqualTo(200);
            assertThat(get("/board", cookie).statusCode()).isEqualTo(200);
        }
        HttpResponse<InputStream> events = openEvents(cookie);
        CompletableFuture<Boolean> change = readUntilLine(events.body(), "event: board");

        for (int poll = 0; poll < 3; poll++) {
            board.recordPoll(List.of(issue()));
        }

        assertThat(change.get(5, TimeUnit.SECONDS)).isTrue();
        assertThat(labelCalls).isEmpty();
    }

    @Test
    void approveAddsExactlyOneLabel() {
        recordCard(mergeWaitsForApproval());

        HttpResponse<String> answer = post("/issues/12/approve", logIn(), sameOrigin());

        assertThat(answer.statusCode()).isEqualTo(303);
        assertThat(answer.headers().firstValue("Location")).contains("/");
        assertThat(labelCalls).containsExactly(new LabelCall(ISSUE_NUMBER, APPROVE_LABEL));
        assertThat(pageRequests.isRemembered(ISSUE_NUMBER, APPROVE_LABEL)).isTrue();
        assertThat(board.snapshot().cards().get(0).labels()).contains(APPROVE_LABEL);
    }

    @Test
    void continueWritesTheMarkerAndNoLabel() {
        recordCard(liveConversation());

        HttpResponse<String> answer = post("/issues/12/continue", logIn(), sameOrigin());

        assertThat(answer.statusCode()).isEqualTo(303);
        assertThat(liveSessions.isContinueRequested(ISSUE_NUMBER)).isTrue();
        assertThat(labelCalls).isEmpty();
    }

    @Test
    void aSecondContinueGives409() {
        recordCard(liveConversation());
        Optional<String> cookie = logIn();
        post("/issues/12/continue", cookie, sameOrigin());

        assertThat(post("/issues/12/continue", cookie, sameOrigin()).statusCode()).isEqualTo(409);
    }

    @Test
    void anActionThatIsNoLongerPossibleGives409() {
        recordCard(waitsForAnAnswer());

        HttpResponse<String> answer = post("/issues/12/approve", logIn(), sameOrigin());

        assertThat(answer.statusCode()).isEqualTo(409);
        assertThat(answer.body()).contains("This action is not possible now.");
        assertThat(labelCalls).isEmpty();
    }

    @Test
    void aSecondClickGives409() {
        recordCard(mergeWaitsForApproval());
        Optional<String> cookie = logIn();

        assertThat(post("/issues/12/approve", cookie, sameOrigin()).statusCode()).isEqualTo(303);
        assertThat(post("/issues/12/approve", cookie, sameOrigin()).statusCode()).isEqualTo(409);
        assertThat(labelCalls).hasSize(1);
    }

    @Test
    void anUnknownIssueOrActionGives404() {
        recordCard(mergeWaitsForApproval());
        Optional<String> cookie = logIn();

        assertThat(post("/issues/99/approve", cookie, sameOrigin()).statusCode()).isEqualTo(404);
        assertThat(post("/issues/12/merge", cookie, sameOrigin()).statusCode()).isEqualTo(404);
        assertThat(post("/issues/twelve/approve", cookie, sameOrigin()).statusCode()).isEqualTo(404);
        assertThat(post("/issues/12/approve/again", cookie, sameOrigin()).statusCode()).isEqualTo(404);
        assertThat(labelCalls).isEmpty();
    }

    @Test
    void anActionWithoutTheLoginGives401() {
        recordCard(mergeWaitsForApproval());

        assertThat(post("/issues/12/approve", Optional.empty(), sameOrigin()).statusCode()).isEqualTo(401);
        assertThat(labelCalls).isEmpty();
    }

    @Test
    void aRefusedLabelGives502AndKeepsTheButton() {
        recordCard(mergeWaitsForApproval());
        labelFailure = Optional.of(new GitHubException("<b>no</b> rights"));

        HttpResponse<String> answer = post("/issues/12/approve", logIn(), sameOrigin());

        assertThat(answer.statusCode()).isEqualTo(502);
        assertThat(answer.body()).contains("GitHub refused the label.").contains("&lt;b&gt;no&lt;/b&gt; rights")
                .doesNotContain("<b>no</b>");
        assertThat(board.snapshot().cards().get(0).labels()).doesNotContain(APPROVE_LABEL);
    }

    @Test
    void aPostFromAnotherSiteGives403() {
        recordCard(mergeWaitsForApproval());

        HttpResponse<String> answer = post("/issues/12/approve", logIn(), "http://evil.example");

        assertThat(answer.statusCode()).isEqualTo(403);
        assertThat(labelCalls).isEmpty();
    }

    @Test
    void aLoginFromAnotherSiteGives403() {
        HttpResponse<String> answer = postPassword("http://evil.example");

        assertThat(answer.statusCode()).isEqualTo(403);
        assertThat(answer.headers().firstValue("Set-Cookie")).isEmpty();
    }

    @Test
    void anotherPathGives404AndAnotherMethodGives405() {
        Optional<String> cookie = logIn();

        assertThat(get("/other", cookie).statusCode()).isEqualTo(404);
        assertThat(get("/boards", cookie).statusCode()).isEqualTo(404);
        assertThat(get("/issues/12/approve", cookie).statusCode()).isEqualTo(405);
        assertThat(post("/board", cookie, sameOrigin()).statusCode()).isEqualTo(405);
    }

    @Test
    void theResourcesNeedNoLogin() {
        HttpResponse<String> style = get("/page.css", Optional.empty());
        HttpResponse<String> script = get("/page.js", Optional.empty());

        assertThat(style.statusCode()).isEqualTo(200);
        assertThat(style.headers().firstValue("Content-Type")).hasValueSatisfying(type -> type.startsWith("text/css"));
        assertThat(script.statusCode()).isEqualTo(200);
        assertThat(script.body()).contains("EventSource");
    }

    @Test
    void theEventStreamSignalsAChange() throws Exception {
        HttpResponse<InputStream> events = openEvents(logIn());
        assertThat(events.statusCode()).isEqualTo(200);
        assertThat(events.headers().firstValue("Content-Type")).contains("text/event-stream");
        CompletableFuture<Boolean> change = readUntilLine(events.body(), "event: board");

        board.recordPoll(List.of());

        assertThat(change.get(5, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void theStopEndsTheEventStream() throws Exception {
        HttpResponse<InputStream> events = openEvents(logIn());
        CompletableFuture<Boolean> change = readUntilLine(events.body(), "event: board");

        server.stop();

        assertThat(change.get(5, TimeUnit.SECONDS)).isFalse();
    }

    @Test
    void everyAnswerHasTheSecurityHeaders() {
        recordCard(mergeWaitsForApproval());
        Optional<String> cookie = logIn();
        List<HttpResponse<String>> answers = List.of(
                get("/login", Optional.empty()),
                postPassword(sameOrigin()),
                get("/", Optional.empty()),
                get("/", cookie),
                get("/board", Optional.empty()),
                get("/page.css", Optional.empty()),
                get("/other", Optional.empty()),
                post("/board", cookie, sameOrigin()),
                post("/issues/12/approve", cookie, "http://evil.example"),
                post("/issues/12/approve", cookie, sameOrigin()));

        for (HttpResponse<String> answer : answers) {
            assertThat(answer.headers().firstValue("Content-Security-Policy")).as(answer.uri().toString())
                    .contains(EXPECTED_POLICY);
            assertThat(answer.headers().firstValue("X-Content-Type-Options")).contains("nosniff");
            assertThat(answer.headers().firstValue("Referrer-Policy")).contains("no-referrer");
        }
    }
}
