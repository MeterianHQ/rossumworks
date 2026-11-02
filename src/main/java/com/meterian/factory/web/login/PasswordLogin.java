package com.meterian.factory.web.login;

import com.meterian.factory.web.PageLogin;
import com.meterian.factory.web.login.PasswordFormReader.RefusedFormException;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * The login with one password for the worker (spec section 9.6).
 * A correct password gives a random session cookie for 30 days. Five wrong passwords in a row lock the login
 * for 15 minutes. The sessions are in memory, so a restart of the worker asks for the password again.
 */
public final class PasswordLogin implements PageLogin {

    public static final int MAXIMUM_SESSIONS = 20;
    public static final int MAXIMUM_BODY_BYTES = 4 * 1024;
    public static final int MAXIMUM_WRONG_PASSWORDS = 5;
    public static final Duration LOCK_DURATION = Duration.ofMinutes(15);
    public static final Duration SESSION_LIFETIME = Duration.ofDays(30);

    private static final String SESSION_COOKIE_NAME = "factory_session";
    private static final String FORM_CONTENT_TYPE = "application/x-www-form-urlencoded";
    private static final String WRONG_PASSWORD_MESSAGE = "The password is not correct.";
    private static final String LOCKED_MESSAGE = "Too many wrong passwords. Try again after %s.";
    private static final DateTimeFormatter LOCK_END_FORMAT = DateTimeFormatter.ofPattern("HH:mm");

    private final PasswordHash hash;
    private final Clock clock;
    private final SessionStore sessions;
    private final WrongPasswordLock lock = new WrongPasswordLock(MAXIMUM_WRONG_PASSWORDS, LOCK_DURATION);
    private final PasswordFormReader formReader = new PasswordFormReader(MAXIMUM_BODY_BYTES);

    public PasswordLogin(PasswordHash hash, Clock clock, SecureRandom random) {
        this.hash = hash;
        this.clock = clock;
        this.sessions = new SessionStore(random, MAXIMUM_SESSIONS);
    }

    /** Checks the password and clears the array. During a lock, it does not check the password. */
    public LoginResult logIn(char[] password) {
        try {
            synchronized (lock) {
                return checkPassword(password);
            }
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    public boolean isValidSession(String token) {
        return token != null && sessions.isValidSession(token, clock.instant());
    }

    public String sessionCookie(String token) {
        return SESSION_COOKIE_NAME + "=" + token + "; Max-Age=" + SESSION_LIFETIME.toSeconds()
                + "; Path=/; HttpOnly; SameSite=Strict";
    }

    @Override
    public boolean isLoggedIn(Headers requestHeaders) {
        List<String> cookieHeaders = requestHeaders.get("Cookie");
        if (cookieHeaders == null) {
            return false;
        }
        return cookieHeaders.stream().anyMatch(this::hasValidSessionCookie);
    }

    @Override
    public void serveLogin(HttpExchange exchange) throws IOException {
        try (exchange) {
            switch (exchange.getRequestMethod()) {
                case "GET" -> sendPage(exchange, 200, "");
                case "POST" -> answerLoginAttempt(exchange);
                default -> sendMethodNotAllowed(exchange);
            }
        }
    }

    private LoginResult checkPassword(char[] password) {
        Instant now = clock.instant();
        Optional<Instant> activeLock = lock.findActiveLock(now);
        if (activeLock.isPresent()) {
            return LoginResult.locked(activeLock.get());
        }
        if (hash.matches(password)) {
            lock.recordCorrectPassword();
            return LoginResult.accepted(sessions.createSession(now, now.plus(SESSION_LIFETIME)));
        }
        return lock.recordWrongPassword(now).map(LoginResult::locked).orElseGet(LoginResult::wrongPassword);
    }

    private boolean hasValidSessionCookie(String cookieHeader) {
        for (String cookie : cookieHeader.split(";")) {
            int separator = cookie.indexOf('=');
            if (separator > 0 && cookie.substring(0, separator).trim().equals(SESSION_COOKIE_NAME)
                    && isValidSession(cookie.substring(separator + 1).trim())) {
                return true;
            }
        }
        return false;
    }

    private void answerLoginAttempt(HttpExchange exchange) throws IOException {
        if (!hasFormContentType(exchange.getRequestHeaders())) {
            sendStatus(exchange, 415);
            return;
        }
        char[] password;
        try (InputStream body = exchange.getRequestBody()) {
            password = formReader.readPassword(body);
        } catch (RefusedFormException exception) {
            sendStatus(exchange, exception.statusCode());
            return;
        }
        sendLoginResult(exchange, logIn(password));
    }

    private static boolean hasFormContentType(Headers requestHeaders) {
        String contentType = requestHeaders.getFirst("Content-Type");
        if (contentType == null) {
            return false;
        }
        int parameters = contentType.indexOf(';');
        String mediaType = parameters < 0 ? contentType : contentType.substring(0, parameters);
        return mediaType.trim().equalsIgnoreCase(FORM_CONTENT_TYPE);
    }

    private void sendLoginResult(HttpExchange exchange, LoginResult result) throws IOException {
        switch (result.outcome()) {
            case ACCEPTED -> sendSessionAndGoToThePage(exchange, result.sessionToken().orElseThrow());
            case WRONG_PASSWORD -> sendPage(exchange, 401, WRONG_PASSWORD_MESSAGE);
            case LOCKED -> sendPage(exchange, 429, LOCKED_MESSAGE.formatted(formatLockEnd(result)));
        }
    }

    private String formatLockEnd(LoginResult result) {
        return LOCK_END_FORMAT.format(result.retryAfter().orElseThrow().atZone(clock.getZone()));
    }

    private void sendSessionAndGoToThePage(HttpExchange exchange, String token) throws IOException {
        Headers responseHeaders = exchange.getResponseHeaders();
        responseHeaders.set("Set-Cookie", sessionCookie(token));
        responseHeaders.set("Location", "/");
        responseHeaders.set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(303, -1);
    }

    private static void sendMethodNotAllowed(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().set("Allow", "GET, POST");
        sendStatus(exchange, 405);
    }

    private static void sendStatus(HttpExchange exchange, int statusCode) throws IOException {
        exchange.sendResponseHeaders(statusCode, -1);
    }

    private static void sendPage(HttpExchange exchange, int statusCode, String message) throws IOException {
        byte[] page = LoginPage.render(message).getBytes(StandardCharsets.UTF_8);
        Headers responseHeaders = exchange.getResponseHeaders();
        responseHeaders.set("Content-Type", "text/html; charset=utf-8");
        responseHeaders.set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(statusCode, page.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(page);
        }
    }
}
