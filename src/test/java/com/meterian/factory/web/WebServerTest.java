package com.meterian.factory.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.meterian.factory.agent.LiveContextReader;
import com.meterian.factory.agent.LiveSessionException;
import com.meterian.factory.board.BoardState;
import com.meterian.factory.board.FactorySwitch;
import com.meterian.factory.configuration.ClaudeSettings;
import com.meterian.factory.configuration.FactoryConfiguration;
import com.meterian.factory.configuration.IsolationMode;
import com.meterian.factory.configuration.RepositoryName;
import com.meterian.factory.configuration.WorkerLimits;
import com.meterian.factory.definition.DefinitionLookup;
import com.meterian.factory.definition.BuiltInDefinition;
import com.meterian.factory.configuration.WebSettings;
import com.meterian.factory.github.GitHubException;
import com.meterian.factory.github.Issue;
import com.meterian.factory.state.IssueRecord;
import com.meterian.factory.state.LivePlace;
import com.meterian.factory.state.LiveSession;
import com.meterian.factory.testing.FakeLiveSessions;
import com.meterian.factory.testing.MutableClock;
import com.meterian.factory.testing.RecordStates;
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
import java.util.concurrent.atomic.AtomicInteger;
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
    private static final String LINK = "https://claude.ai/code/session_abc123";
    private static final String EXPECTED_POLICY = "default-src 'none'; script-src 'self'; style-src 'self'; "
            + "connect-src 'self'; form-action 'self'; frame-ancestors 'none'; base-uri 'none'";

    /** The arguments of one call of the label writer. */
    private record LabelCall(int issueNumber, String label) {
    }

    @TempDir
    Path temporaryDirectory;

    private final MutableClock clock = new MutableClock(NOW);
    private final BoardState board = new BoardState(clock);
    private final FakeLiveSessions liveSessions = new FakeLiveSessions();
    private final PageRequests pageRequests = new PageRequests(clock);
    private final List<LabelCall> labelCalls = new CopyOnWriteArrayList<>();
    private volatile Optional<RuntimeException> labelFailure = Optional.empty();
    private final AtomicInteger earlyPollRequests = new AtomicInteger();
    private final HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

    private FactorySwitch factorySwitch;
    private WebServer server;

    @BeforeEach
    void startServer() throws IOException {
        factorySwitch = new FactorySwitch(temporaryDirectory.resolve("work"));
        PasswordLogin login = new PasswordLogin(PasswordHash.create(PASSWORD.toCharArray(), FAST_ITERATIONS), clock,
                new SecureRandom());
        PageActions actions = createPageActions();
        WebSettings settings = new WebSettings("127.0.0.1", 0, Optional.of(PasswordHash.create(PASSWORD.toCharArray(),
                FAST_ITERATIONS)));
        server = new WebServer(settings, login, createModelSource(), new BoardPage(ZoneOffset.UTC), board, actions);
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop();
    }

    private LabelWriter createLabelWriter() {
        return (issueNumber, label) -> recordLabelCall(new LabelCall(issueNumber, label));
    }

    private void recordLabelCall(LabelCall call) {
        labelCalls.add(call);
        labelFailure.ifPresent(failure -> {
            throw failure;
        });
    }

    private static LabelCall added(String label) {
        return new LabelCall(ISSUE_NUMBER, label);
    }

    private PageModelSource createModelSource() {
        Path workDirectory = temporaryDirectory.resolve("work");
        FactoryConfiguration configuration = new FactoryConfiguration(new RepositoryName("MeterianHQ", "target"),
                workDirectory, temporaryDirectory.resolve("gatekeeper.git"), temporaryDirectory.resolve("live"),
                new WorkerLimits(3, Duration.ofSeconds(45), 0.90), Map.of(), IsolationMode.SAME_USER,
                ClaudeSettings.createDefaults(), Optional.empty(), FactoryConfiguration.DEFAULT_TERMINAL_COMMAND);
        UsageTracker usage = new UsageTracker(0.90, clock);
        return new PageModelSource(board, factorySwitch, usage, usage, new ContextTracker(),
                new LiveContextReader(temporaryDirectory.resolve("claude-home")), liveSessions, true, pageRequests,
                new WorkspaceLayout(workDirectory), configuration,
                DefinitionLookup.builtInOnly(BuiltInDefinition.load()), Optional::empty, () -> 1, clock);
    }

    private static IssueRecord afterTheImplementation() {
        return RecordStates.move(RecordStates.queued(ISSUE_NUMBER).withAgentSessionStarted(), "implement");
    }

    private static IssueRecord mergeWaitsForApproval() {
        return RecordStates.move(afterTheImplementation(), "waits-for-approval");
    }

    /** A blocked issue: Enter is possible, Approve is not. */
    private static IssueRecord waitsInTheBlockTarget() {
        return RecordStates.move(afterTheImplementation(), "blocked");
    }

    private static IssueRecord liveConversation() {
        return RecordStates.move(afterTheImplementation(), "live")
                .withLive(new LiveSession("abc", Optional.empty(), LivePlace.GITHUB_COMMENT));
    }

    private static IssueRecord liveConversationWithLink() {
        return liveConversation().withLive(new LiveSession("abc", Optional.of(LINK), LivePlace.GITHUB_COMMENT));
    }

    private static Issue issue() {
        return new Issue(ISSUE_NUMBER, "Fix the login", "body", Set.of("factory"));
    }

    private void recordCard(IssueRecord record) {
        board.recordIssue(issue(), record);
    }

    private void recordCardWithLabel(IssueRecord record, String label) {
        board.recordIssue(new Issue(ISSUE_NUMBER, "Fix the login", "body", Set.of("factory", label)), record);
    }

    private String currentFingerprint() {
        return board.snapshot().cards().stream().filter(card -> card.issueNumber() == ISSUE_NUMBER)
                .map(CardFingerprint::of).findFirst().orElse("0000000000000000");
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

    private HttpResponse<String> postForm(String path, Optional<String> cookie, String body) {
        HttpRequest.Builder request = HttpRequest.newBuilder(address(path))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Origin", sameOrigin());
        cookie.ifPresent(value -> request.header("Cookie", value));
        return send(request.build());
    }

    /** Posts the form of a button as the current page has it, with the fingerprint of the current card. */
    private HttpResponse<String> postAction(String path, Optional<String> cookie) {
        return postForm(path, cookie, "state=" + currentFingerprint());
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
    void aPageWithoutAPasswordOpensWithoutTheLogin() throws IOException {
        startServerWithoutPassword();
        recordCard(mergeWaitsForApproval());

        HttpResponse<String> page = get("/", Optional.empty());
        HttpResponse<String> login = get("/login", Optional.empty());

        assertThat(page.statusCode()).isEqualTo(200);
        assertThat(page.body()).contains("#12");
        assertThat(login.statusCode()).isEqualTo(303);
        assertThat(login.headers().firstValue("Location")).contains("/");
    }

    @Test
    void aPageWithoutAPasswordRefusesAPostFromAnotherSite() throws IOException {
        startServerWithoutPassword();
        recordCard(mergeWaitsForApproval());

        HttpResponse<String> answer = post("/issues/12/approve", Optional.empty(), "http://evil.example");

        assertThat(answer.statusCode()).isEqualTo(403);
        assertThat(labelCalls).isEmpty();
    }

    private void startServerWithoutPassword() throws IOException {
        server.stop();
        WebSettings settings = new WebSettings("127.0.0.1", 0, Optional.empty());
        server = new WebServer(settings, new NoPasswordLogin(), createModelSource(), new BoardPage(ZoneOffset.UTC),
                board, createPageActions());
        server.start();
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

        HttpResponse<String> answer = postAction("/issues/12/approve", logIn());

        assertThat(answer.statusCode()).isEqualTo(303);
        assertThat(answer.headers().firstValue("Location")).contains("/");
        assertThat(labelCalls).containsExactly(added(APPROVE_LABEL));
        assertThat(pageRequests.isRemembered(ISSUE_NUMBER, APPROVE_LABEL)).isTrue();
        assertThat(board.snapshot().cards().get(0).labels()).contains(APPROVE_LABEL);
    }

    @Test
    void continueWritesTheMarkerAndNoLabel() {
        recordCard(liveConversation());

        HttpResponse<String> answer = postAction("/issues/12/continue", logIn());

        assertThat(answer.statusCode()).isEqualTo(303);
        assertThat(liveSessions.isContinueRequested(ISSUE_NUMBER)).isTrue();
        assertThat(labelCalls).isEmpty();
    }

    @Test
    void aSecondContinueGives409() {
        recordCard(liveConversation());
        Optional<String> cookie = logIn();
        postAction("/issues/12/continue", cookie);

        assertThat(postAction("/issues/12/continue", cookie).statusCode()).isEqualTo(409);
    }

    @Test
    void theOwnerStopsTheFactory() {
        HttpResponse<String> answer = postForm("/factory/stop", logIn(), "");

        assertThat(answer.statusCode()).isEqualTo(303);
        assertThat(answer.headers().firstValue("Location")).contains("/");
        assertThat(factorySwitch.isStopRequested()).isTrue();
    }

    @Test
    void theOwnerStartsTheFactoryAgainAndTheWorkerPollsEarly() {
        factorySwitch.requestStop();

        HttpResponse<String> answer = postForm("/factory/start", logIn(), "");

        assertThat(answer.statusCode()).isEqualTo(303);
        assertThat(factorySwitch.isStopRequested()).isFalse();
        assertThat(earlyPollRequests).hasValue(1);
    }

    @Test
    void theFactorySwitchNeedsALogin() {
        HttpResponse<String> answer = postForm("/factory/stop", Optional.empty(), "");

        assertThat(answer.statusCode()).isEqualTo(401);
        assertThat(factorySwitch.isStopRequested()).isFalse();
    }

    @Test
    void theFactorySwitchRefusesAnotherOrigin() {
        HttpResponse<String> answer = post("/factory/stop", logIn(), "https://other.example");

        assertThat(answer.statusCode()).isEqualTo(403);
        assertThat(factorySwitch.isStopRequested()).isFalse();
    }

    @Test
    void theFactorySwitchKnowsOnlyTwoActions() {
        assertThat(postForm("/factory/other", logIn(), "").statusCode()).isEqualTo(404);
    }

    @Test
    void anApproveAsksTheWorkerForAnEarlyPoll() {
        recordCard(mergeWaitsForApproval());

        postAction("/issues/12/approve", logIn());

        assertThat(earlyPollRequests).hasValue(1);
    }

    @Test
    void aContinueAsksTheWorkerForAnEarlyPoll() {
        recordCard(liveConversation());

        postAction("/issues/12/continue", logIn());

        assertThat(earlyPollRequests).hasValue(1);
    }

    @Test
    void anActionThatIsNotPossibleAsksForNoEarlyPoll() {
        recordCard(waitsInTheBlockTarget());

        postAction("/issues/12/approve", logIn());

        assertThat(earlyPollRequests).hasValue(0);
    }

    @Test
    void anActionThatIsNoLongerPossibleGives409() {
        recordCard(waitsInTheBlockTarget());

        HttpResponse<String> answer = postAction("/issues/12/approve", logIn());

        assertThat(answer.statusCode()).isEqualTo(409);
        assertThat(answer.body()).contains("This action is not possible now.");
        assertThat(labelCalls).isEmpty();
    }

    @Test
    void aSecondClickGives409() {
        recordCard(mergeWaitsForApproval());
        Optional<String> cookie = logIn();

        assertThat(postAction("/issues/12/approve", cookie).statusCode()).isEqualTo(303);
        assertThat(postAction("/issues/12/approve", cookie).statusCode()).isEqualTo(409);
        assertThat(labelCalls).hasSize(1);
    }

    @Test
    void anUnknownIssueOrActionGives404() {
        recordCard(mergeWaitsForApproval());
        Optional<String> cookie = logIn();

        assertThat(postAction("/issues/99/approve", cookie).statusCode()).isEqualTo(404);
        assertThat(postAction("/issues/12/merge", cookie).statusCode()).isEqualTo(404);
        assertThat(postAction("/issues/twelve/approve", cookie).statusCode()).isEqualTo(404);
        assertThat(postAction("/issues/12/approve/again", cookie).statusCode()).isEqualTo(404);
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

        HttpResponse<String> answer = postAction("/issues/12/approve", logIn());

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
                postAction("/issues/12/approve", cookie));

        for (HttpResponse<String> answer : answers) {
            assertThat(answer.headers().firstValue("Content-Security-Policy")).as(answer.uri().toString())
                    .contains(EXPECTED_POLICY);
            assertThat(answer.headers().firstValue("X-Content-Type-Options")).contains("nosniff");
            // With "no-referrer", a browser sends "Origin: null" on a form POST, and the origin check refuses the login.
            assertThat(answer.headers().firstValue("Referrer-Policy")).contains("same-origin");
        }
    }

    @Test
    void aLabelThatThePageDidNotRequestShowsTheButton() {
        recordCardWithLabel(mergeWaitsForApproval(), APPROVE_LABEL);

        HttpResponse<String> page = get("/board", logIn());

        assertThat(page.body()).contains("action=\"/issues/12/approve\"");
    }

    @Test
    void anApproveOnALabelThatIsAlreadyThereOnlyAddsTheLabel() {
        recordCardWithLabel(mergeWaitsForApproval(), APPROVE_LABEL);

        HttpResponse<String> answer = postAction("/issues/12/approve", logIn());

        assertThat(answer.statusCode()).isEqualTo(303);
        assertThat(labelCalls).containsExactly(added(APPROVE_LABEL));
        assertThat(pageRequests.isRemembered(ISSUE_NUMBER, APPROVE_LABEL)).isTrue();
    }

    @Test
    void aRememberedLabelHidesTheButton() {
        recordCardWithLabel(mergeWaitsForApproval(), APPROVE_LABEL);
        pageRequests.remember(ISSUE_NUMBER, APPROVE_LABEL);
        Optional<String> cookie = logIn();

        assertThat(get("/board", cookie).body()).doesNotContain("action=\"/issues/12/approve\"");
        assertThat(postAction("/issues/12/approve", cookie).statusCode()).isEqualTo(409);
        assertThat(labelCalls).isEmpty();
    }

    @Test
    void anApproveFromAPageBeforeANewApprovalStateGives409() {
        recordCard(mergeWaitsForApproval());
        String oldFingerprint = currentFingerprint();
        recordCard(RecordStates.move(mergeWaitsForApproval(), "text-changed"));

        HttpResponse<String> answer = postForm("/issues/12/approve", logIn(), "state=" + oldFingerprint);

        assertThat(answer.statusCode()).isEqualTo(409);
        assertThat(answer.body()).contains("This action is not possible now.");
        assertThat(labelCalls).isEmpty();
    }

    @Test
    void anApproveFromAPageBeforeANewEntryGives409() {
        recordCard(mergeWaitsForApproval());
        String oldFingerprint = currentFingerprint();
        recordCard(mergeWaitsForApproval().withEntryCounted("implement"));

        HttpResponse<String> answer = postForm("/issues/12/approve", logIn(), "state=" + oldFingerprint);

        assertThat(answer.statusCode()).isEqualTo(409);
        assertThat(labelCalls).isEmpty();
    }

    @Test
    void anActionWithoutTheStateGives409() {
        recordCard(mergeWaitsForApproval());
        Optional<String> cookie = logIn();

        assertThat(post("/issues/12/approve", cookie, sameOrigin()).statusCode()).isEqualTo(409);
        assertThat(postForm("/issues/12/approve", cookie, "other=1").statusCode()).isEqualTo(409);
        assertThat(postForm("/issues/12/approve", cookie, "state=").statusCode()).isEqualTo(409);
        assertThat(labelCalls).isEmpty();
    }

    @Test
    void theStateCanBeAnyFieldOfTheForm() {
        recordCard(mergeWaitsForApproval());

        HttpResponse<String> answer = postForm("/issues/12/approve", logIn(), "a=1&state=" + currentFingerprint());

        assertThat(answer.statusCode()).isEqualTo(303);
    }

    @Test
    void aLargeBodyGives413() {
        recordCard(mergeWaitsForApproval());

        HttpResponse<String> answer = postForm("/issues/12/approve", logIn(),
                "state=" + currentFingerprint() + "&padding=" + "x".repeat(5000));

        assertThat(answer.statusCode()).isEqualTo(413);
        assertThat(labelCalls).isEmpty();
    }

    @Test
    void aCardThatWentAwayGives404WithAMessage() {
        recordCard(mergeWaitsForApproval());
        Optional<String> cookie = logIn();
        String fingerprint = currentFingerprint();
        board.recordPoll(List.of());

        HttpResponse<String> answer = postForm("/issues/12/approve", cookie, "state=" + fingerprint);

        assertThat(answer.statusCode()).isEqualTo(404);
        assertThat(answer.body()).contains("<body class=\"login-page\"><main class=\"login\"><div class=\"login-box\">"
                + "<p class=\"message\">This issue is no longer on the board.</p>"
                + "<p><a href=\"/\">Back to the board</a></p></div></main></body>");
    }

    @Test
    void aFailureOfTheActionGives500WithAMessage() {
        recordCard(mergeWaitsForApproval());
        labelFailure = Optional.of(new IllegalStateException("broken"));

        HttpResponse<String> answer = postAction("/issues/12/approve", logIn());

        assertThat(answer.statusCode()).isEqualTo(500);
        assertThat(answer.body()).contains("The action failed. See the log of the worker.").doesNotContain("broken");
    }

    @Test
    void aTerminalActionFromAnotherDeviceIsNotPossible() {
        recordCard(waitsInTheBlockTarget());

        PageActions.ActionResult result = createPageActions()
                .perform(ISSUE_NUMBER, PageAction.ENTER_TERMINAL, Optional.of(currentFingerprint()), false);

        assertThat(result.outcome()).isEqualTo(PageActions.ActionOutcome.NOT_POSSIBLE);
        assertThat(labelCalls).isEmpty();
        assertThat(earlyPollRequests.get()).isZero();
        assertThat(liveSessions.openedTerminals()).isEmpty();
    }

    @Test
    void anOpenTerminalFromAnotherDeviceIsNotPossible() {
        recordCard(liveConversationWithLink());

        PageActions.ActionResult result = createPageActions()
                .perform(ISSUE_NUMBER, PageAction.OPEN_TERMINAL, Optional.of(currentFingerprint()), false);

        assertThat(result.outcome()).isEqualTo(PageActions.ActionOutcome.NOT_POSSIBLE);
        assertThat(liveSessions.openedTerminals()).isEmpty();
    }

    @Test
    void anEnterInTheBrowserRedirectsToTheWaitPage() {
        recordCard(waitsInTheBlockTarget());

        HttpResponse<String> answer = postAction("/issues/12/enter-browser", logIn());

        assertThat(answer.statusCode()).isEqualTo(303);
        assertThat(answer.headers().firstValue("Location")).contains("/live/12");
        assertThat(labelCalls).containsExactly(added("factory:enter"));
        assertThat(earlyPollRequests.get()).isEqualTo(1);
    }

    @Test
    void anEnterInTheTerminalRemembersThePlace() {
        recordCard(waitsInTheBlockTarget());

        HttpResponse<String> answer = postAction("/issues/12/enter-terminal", logIn());

        assertThat(answer.statusCode()).isEqualTo(303);
        assertThat(answer.headers().firstValue("Location")).contains("/");
        assertThat(pageRequests.isRemembered(ISSUE_NUMBER, "factory:enter")).isTrue();
        assertThat(pageRequests.takeEnterPlace(ISSUE_NUMBER)).isEqualTo(LivePlace.TERMINAL);
    }

    @Test
    void aRefusedEnterForgetsThePlace() {
        recordCard(waitsInTheBlockTarget());
        labelFailure = Optional.of(new GitHubException("no rights"));

        postAction("/issues/12/enter-terminal", logIn());

        assertThat(pageRequests.takeEnterPlace(ISSUE_NUMBER)).isEqualTo(LivePlace.GITHUB_COMMENT);
    }

    @Test
    void openTerminalOpensTheTerminalWithoutAGitHubRequest() {
        recordCard(liveConversationWithLink());

        HttpResponse<String> answer = postAction("/issues/12/open-terminal", logIn());

        assertThat(answer.statusCode()).isEqualTo(303);
        assertThat(answer.headers().firstValue("Location")).contains("/");
        assertThat(liveSessions.openedTerminals()).containsExactly(ISSUE_NUMBER);
        assertThat(labelCalls).isEmpty();
        assertThat(earlyPollRequests.get()).isZero();
    }

    @Test
    void aTerminalThatDoesNotOpenGives500WithTheMessage() {
        recordCard(liveConversationWithLink());
        liveSessions.failOpenTerminalWith(new LiveSessionException("The terminal command gnome-terminal failed."));

        HttpResponse<String> answer = postAction("/issues/12/open-terminal", logIn());

        assertThat(answer.statusCode()).isEqualTo(500);
        assertThat(answer.body()).contains("The terminal command gnome-terminal failed.");
        assertThat(earlyPollRequests.get()).isZero();
    }

    @Test
    void anEnterWithoutTheLiveModeIsNotPossible() {
        recordCard(waitsInTheBlockTarget());

        PageActions.ActionResult terminal = createPageActionsWithoutTheLiveMode()
                .perform(ISSUE_NUMBER, PageAction.ENTER_TERMINAL, Optional.of(currentFingerprint()), true);
        PageActions.ActionResult browser = createPageActionsWithoutTheLiveMode()
                .perform(ISSUE_NUMBER, PageAction.ENTER_BROWSER, Optional.of(currentFingerprint()), true);

        assertThat(terminal.outcome()).isEqualTo(PageActions.ActionOutcome.NOT_POSSIBLE);
        assertThat(browser.outcome()).isEqualTo(PageActions.ActionOutcome.NOT_POSSIBLE);
        assertThat(labelCalls).isEmpty();
        assertThat(pageRequests.isRemembered(ISSUE_NUMBER, "factory:enter")).isFalse();
        assertThat(earlyPollRequests.get()).isZero();
    }

    @Test
    void anOpenTerminalWithoutTheLiveModeIsNotPossible() {
        recordCard(liveConversationWithLink());

        PageActions.ActionResult result = createPageActionsWithoutTheLiveMode()
                .perform(ISSUE_NUMBER, PageAction.OPEN_TERMINAL, Optional.of(currentFingerprint()), true);

        assertThat(result.outcome()).isEqualTo(PageActions.ActionOutcome.NOT_POSSIBLE);
        assertThat(liveSessions.openedTerminals()).isEmpty();
    }

    private PageActions createPageActionsWithoutTheLiveMode() {
        return createPageActions(false);
    }

    private PageActions createPageActions() {
        return createPageActions(true);
    }

    private PageActions createPageActions(boolean liveModeAvailable) {
        return new PageActions(createLabelWriter(), pageRequests, liveSessions, liveModeAvailable, board,
                factorySwitch, earlyPollRequests::incrementAndGet, DefinitionLookup.builtInOnly(BuiltInDefinition.load()));
    }

    @Test
    void theWaitPageNeedsTheLogin() {
        HttpResponse<String> answer = get("/live/12", Optional.empty());

        assertThat(answer.statusCode()).isEqualTo(303);
        assertThat(answer.headers().firstValue("Location")).contains("/login");
    }

    @Test
    void theWaitPageNamesTheIssue() {
        HttpResponse<String> answer = get("/live/12", logIn());

        assertThat(answer.statusCode()).isEqualTo(200);
        assertThat(answer.body()).contains("data-issue=\"12\"").contains("src=\"/live.js\"")
                .contains("<title>RossumWorks \u2014 #12</title>")
                .doesNotContainPattern("<script>\\s*\\S");
    }

    @Test
    void theWaitPageShowsForAnUnknownIssue() {
        assertThat(get("/live/99", logIn()).statusCode()).isEqualTo(200);
    }

    @Test
    void aNumberThatIsNotAnIssueNumberGives404() {
        assertThat(get("/live/abc", logIn()).statusCode()).isEqualTo(404);
        assertThat(get("/live/1234567890/link", logIn()).statusCode()).isEqualTo(404);
    }

    @Test
    void theLinkNeedsTheLogin() {
        assertThat(get("/live/12/link", Optional.empty()).statusCode()).isEqualTo(401);
    }

    @Test
    void theLinkIsMissingBeforeTheSessionOpens() {
        recordCard(liveConversation());

        assertThat(get("/live/12/link", logIn()).statusCode()).isEqualTo(204);
    }

    @Test
    void theLinkIsMissingForAnUnknownIssue() {
        assertThat(get("/live/99/link", logIn()).statusCode()).isEqualTo(204);
    }

    @Test
    void theLinkComesWhenTheSessionIsOpen() {
        recordCard(liveConversationWithLink());

        HttpResponse<String> answer = get("/live/12/link", logIn());

        assertThat(answer.statusCode()).isEqualTo(200);
        assertThat(answer.headers().firstValue("Content-Type")).contains("application/json");
        assertThat(answer.headers().firstValue("Cache-Control")).contains("no-store");
        assertThat(answer.body()).isEqualTo("{\"link\":\"https://claude.ai/code/session_abc123\"}");
    }

    @Test
    void aLinkThatIsNotRemoteControlIsNeverGiven() {
        recordCard(liveConversation().withLive(new LiveSession("abc", Optional.of("javascript:alert(1)"), LivePlace.GITHUB_COMMENT)));

        assertThat(get("/live/12/link", logIn()).statusCode()).isEqualTo(204);
    }

    @Test
    void theWaitPageAcceptsOnlyGet() {
        HttpResponse<String> answer = post("/live/12/link", logIn(), sameOrigin());

        assertThat(answer.statusCode()).isEqualTo(405);
    }

    @Test
    void theScriptOfTheWaitPageIsServed() {
        HttpResponse<String> answer = get("/live.js", Optional.empty());

        assertThat(answer.statusCode()).isEqualTo(200);
        assertThat(answer.headers().firstValue("Content-Type")).hasValueSatisfying(
                value -> assertThat(value).startsWith("text/javascript"));
    }
}
