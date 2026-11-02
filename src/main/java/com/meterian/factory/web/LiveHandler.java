package com.meterian.factory.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meterian.factory.agent.TmuxLiveSessions;
import com.meterian.factory.board.BoardState;
import com.meterian.factory.board.IssueCard;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Serves the wait page /live/n of Browser and its data /live/n/link. The page waits until the live conversation
 * has its Remote Control link. Without the login, the page goes to /login and the data gives 401.
 */
final class LiveHandler implements HttpHandler {

    private static final Pattern PATH = Pattern.compile("/live/([0-9]{1,9})(/link)?");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final PageLogin login;
    private final BoardState board;

    LiveHandler(PageLogin login, BoardState board) {
        this.login = login;
        this.board = board;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            Matcher path = PATH.matcher(exchange.getRequestURI().getPath());
            if (!path.matches()) {
                HttpAnswers.sendStatus(exchange, 404);
            } else if (!"GET".equals(exchange.getRequestMethod())) {
                HttpAnswers.sendMethodNotAllowed(exchange, "GET");
            } else if (!login.isLoggedIn(exchange.getRequestHeaders())) {
                answerWithoutLogin(exchange, path.group(2) != null);
            } else if (path.group(2) != null) {
                serveLink(exchange, Integer.parseInt(path.group(1)));
            } else {
                HttpAnswers.sendHtml(exchange, 200, LivePage.render(Integer.parseInt(path.group(1))));
            }
        }
    }

    private void answerWithoutLogin(HttpExchange exchange, boolean isData) throws IOException {
        if (isData) {
            HttpAnswers.sendStatus(exchange, 401);
        } else {
            HttpAnswers.sendRedirect(exchange, "/login");
        }
    }

    private void serveLink(HttpExchange exchange, int issueNumber) throws IOException {
        Optional<String> link = findRemoteControlLink(issueNumber);
        if (link.isEmpty()) {
            HttpAnswers.sendStatus(exchange, 204);
        } else {
            sendLink(exchange, link.get());
        }
    }

    private Optional<String> findRemoteControlLink(int issueNumber) {
        return board.snapshot().cards().stream()
                .filter(card -> card.issueNumber() == issueNumber)
                .findFirst()
                .flatMap(LiveHandler::readLink)
                .filter(TmuxLiveSessions::isRemoteControlLink);
    }

    private static Optional<String> readLink(IssueCard card) {
        return card.record().live().flatMap(live -> live.link());
    }

    private void sendLink(HttpExchange exchange, String link) throws IOException {
        byte[] body = writeJson(link).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        SecurityHeaders.forbidCaching(exchange.getResponseHeaders());
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(body);
        }
    }

    private static String writeJson(String link) {
        try {
            return MAPPER.writeValueAsString(Map.of("link", link));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
