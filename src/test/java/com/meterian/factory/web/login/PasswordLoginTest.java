package com.meterian.factory.web.login;

import static org.assertj.core.api.Assertions.assertThat;

import com.meterian.factory.testing.MutableClock;
import com.meterian.factory.web.login.LoginResult.Outcome;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PasswordLoginTest {

    private static final int FAST_ITERATIONS = 1000;
    private static final String CORRECT = "correct horse battery";
    private static final String WRONG = "wrong horse battery";
    private static final Instant START = Instant.parse("2026-11-01T10:00:00Z");

    private final MutableClock clock = new MutableClock(START);
    private final PasswordLogin login =
            new PasswordLogin(PasswordHash.create(CORRECT.toCharArray(), FAST_ITERATIONS), clock, new SecureRandom());

    private HttpServer server;
    private HttpClient client;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/login", login::serveLogin);
        server.start();
        client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void aCorrectPasswordGivesASessionFor30Days() {
        LoginResult result = login.logIn(CORRECT.toCharArray());

        assertThat(result.outcome()).isEqualTo(Outcome.ACCEPTED);
        String token = result.sessionToken().orElseThrow();
        clock.set(START.plus(Duration.ofDays(30)).minusSeconds(1));
        assertThat(login.isValidSession(token)).isTrue();
        clock.set(START.plus(Duration.ofDays(30)).plusSeconds(1));
        assertThat(login.isValidSession(token)).isFalse();
    }

    @Test
    void aWrongPasswordGivesNoSession() {
        LoginResult result = login.logIn(WRONG.toCharArray());

        assertThat(result.outcome()).isEqualTo(Outcome.WRONG_PASSWORD);
        assertThat(result.sessionToken()).isEmpty();
        assertThat(result.retryAfter()).isEmpty();
    }

    @Test
    void logInClearsThePasswordArray() {
        char[] password = CORRECT.toCharArray();

        login.logIn(password);

        assertThat(password).containsOnly('\0');
    }

    @Test
    void fiveWrongPasswordsLockNewAttemptsFor15Minutes() {
        for (int attempt = 0; attempt < 5; attempt++) {
            login.logIn(WRONG.toCharArray());
        }

        char[] password = CORRECT.toCharArray();
        LoginResult locked = login.logIn(password);
        assertThat(locked.outcome()).isEqualTo(Outcome.LOCKED);
        assertThat(locked.retryAfter()).contains(START.plus(Duration.ofMinutes(15)));
        assertThat(locked.sessionToken()).isEmpty();
        assertThat(password).containsOnly('\0');

        clock.set(START.plus(Duration.ofMinutes(15)));
        assertThat(login.logIn(CORRECT.toCharArray()).outcome()).isEqualTo(Outcome.ACCEPTED);
    }

    @Test
    void aCorrectPasswordResetsTheCountOfWrongPasswords() {
        for (int attempt = 0; attempt < 4; attempt++) {
            login.logIn(WRONG.toCharArray());
        }
        login.logIn(CORRECT.toCharArray());
        for (int attempt = 0; attempt < 4; attempt++) {
            assertThat(login.logIn(WRONG.toCharArray()).outcome()).isEqualTo(Outcome.WRONG_PASSWORD);
        }

        assertThat(login.logIn(CORRECT.toCharArray()).outcome()).isEqualTo(Outcome.ACCEPTED);
    }

    @Test
    void theCookieIsHttpOnlyAndStrict() {
        assertThat(login.sessionCookie("abc"))
                .isEqualTo("factory_session=abc; Max-Age=2592000; Path=/; HttpOnly; SameSite=Strict");
    }

    @Test
    void isLoggedInFindsTheCookieAmongOthers() {
        String token = login.logIn(CORRECT.toCharArray()).sessionToken().orElseThrow();

        assertThat(login.isLoggedIn(cookieHeaders("a=b; factory_session=" + token))).isTrue();
        assertThat(login.isLoggedIn(cookieHeaders("a=b; factory_session=" + otherToken(token)))).isFalse();
    }

    @Test
    void isLoggedInRefusesAPrefixOfTheToken() {
        String token = login.logIn(CORRECT.toCharArray()).sessionToken().orElseThrow();

        assertThat(login.isLoggedIn(cookieHeaders("factory_session=" + token.substring(0, 20)))).isFalse();
        assertThat(login.isLoggedIn(cookieHeaders("factory_session=" + token + "x"))).isFalse();
        assertThat(login.isLoggedIn(cookieHeaders("other_factory_session=" + token))).isFalse();
    }

    @Test
    void isLoggedInRefusesNoCookieAndBrokenCookies() {
        assertThat(login.isLoggedIn(new Headers())).isFalse();
        assertThat(login.isLoggedIn(cookieHeaders(";;=; factory_session; =x; factory_session="))).isFalse();
    }

    @Test
    void eachSessionTokenIsNew() {
        String first = login.logIn(CORRECT.toCharArray()).sessionToken().orElseThrow();
        String second = login.logIn(CORRECT.toCharArray()).sessionToken().orElseThrow();

        assertThat(first).hasSize(43).matches("[A-Za-z0-9_-]+");
        assertThat(second).hasSize(43).isNotEqualTo(first);
    }

    @Test
    void theOldestSessionGoesAfter20Sessions() {
        List<String> tokens = new ArrayList<>();
        for (int session = 0; session < PasswordLogin.MAXIMUM_SESSIONS + 1; session++) {
            tokens.add(login.logIn(CORRECT.toCharArray()).sessionToken().orElseThrow());
        }

        assertThat(login.isValidSession(tokens.get(0))).isFalse();
        for (String token : tokens.subList(1, tokens.size())) {
            assertThat(login.isValidSession(token)).isTrue();
        }
    }

    @Test
    void getLoginShowsTheFormWithoutInlineScriptOrStyle() throws Exception {
        HttpResponse<String> response = send(HttpRequest.newBuilder(loginUri()).GET());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type")).contains("text/html; charset=utf-8");
        assertThat(response.body())
                .contains("<form method=\"post\" action=\"/login\">")
                .contains("name=\"password\"")
                .contains("href=\"/page.css\"")
                .doesNotContain("<script")
                .doesNotContain("style=");
    }

    @Test
    void postLoginWithTheCorrectPasswordSetsTheCookieAndGoesToThePage() throws Exception {
        HttpResponse<String> response = postForm("password=correct+horse%20battery");

        assertThat(response.statusCode()).isEqualTo(303);
        assertThat(response.headers().firstValue("Location")).contains("/");
        String cookie = response.headers().firstValue("Set-Cookie").orElseThrow();
        assertThat(cookie).startsWith("factory_session=").endsWith("; HttpOnly; SameSite=Strict");
        assertThat(login.isLoggedIn(cookieHeaders(cookie.substring(0, cookie.indexOf(';'))))).isTrue();
    }

    @Test
    void postLoginWithAWrongPasswordGives401AndTheMessage() throws Exception {
        HttpResponse<String> response = postForm("password=wrong");

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.body()).containsSubsequence("<main class=\"login\"><div class=\"login-box\">",
                "<p class=\"login-message\">The password is not correct.</p>", "<form", "</div></main>");
        assertThat(response.headers().firstValue("Set-Cookie")).isEmpty();
    }

    @Test
    void postLoginDuringTheLockGives429AndTheTime() throws Exception {
        for (int attempt = 0; attempt < 5; attempt++) {
            login.logIn(WRONG.toCharArray());
        }

        HttpResponse<String> response = postForm("password=correct+horse+battery");

        assertThat(response.statusCode()).isEqualTo(429);
        assertThat(response.body()).contains("Too many wrong passwords. Try again after 10:15.");
    }

    @Test
    void postLoginRefusesABodyOfMoreThan4KiB() throws Exception {
        HttpResponse<String> response = postForm("password=" + "a".repeat(PasswordLogin.MAXIMUM_BODY_BYTES));

        assertThat(response.statusCode()).isEqualTo(413);
    }

    @Test
    void postLoginRefusesABrokenEncoding() throws Exception {
        assertThat(postForm("password=%zz").statusCode()).isEqualTo(400);
        assertThat(postForm("password=%4").statusCode()).isEqualTo(400);
        assertThat(postForm("name=value").statusCode()).isEqualTo(400);
    }

    @Test
    void postLoginRefusesAnotherContentType() throws Exception {
        HttpResponse<String> response = send(HttpRequest.newBuilder(loginUri())
                .header("Content-Type", "text/plain")
                .POST(HttpRequest.BodyPublishers.ofString("password=correct+horse+battery")));

        assertThat(response.statusCode()).isEqualTo(415);
    }

    @Test
    void anotherMethodGives405() throws Exception {
        HttpResponse<String> response = send(HttpRequest.newBuilder(loginUri())
                .method("PUT", HttpRequest.BodyPublishers.ofString("password=x")));

        assertThat(response.statusCode()).isEqualTo(405);
        assertThat(response.headers().firstValue("Allow")).contains("GET, POST");
    }

    private Headers cookieHeaders(String cookie) {
        Headers headers = new Headers();
        headers.add("Cookie", cookie);
        return headers;
    }

    private String otherToken(String token) {
        char last = token.charAt(token.length() - 1);
        return token.substring(0, token.length() - 1) + (last == 'A' ? 'B' : 'A');
    }

    private URI loginUri() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/login");
    }

    private HttpResponse<String> postForm(String body) throws Exception {
        return send(HttpRequest.newBuilder(loginUri())
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body)));
    }

    private HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
