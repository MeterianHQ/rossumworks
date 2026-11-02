package com.meterian.factory.web;

import com.meterian.factory.board.BoardState;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * The event stream of the page (Server-Sent Events). Each change of the board sends the event "board", and
 * page.js then reads /board. Without a change, a comment line keeps the connection open.
 */
final class EventsHandler implements HttpHandler {

    static final Duration KEEP_ALIVE_INTERVAL = Duration.ofSeconds(25);

    private static final String EVENTS_PATH = "/events";
    private static final byte[] KEEP_ALIVE = ": keep-alive\n\n".getBytes(StandardCharsets.UTF_8);

    private final PageLogin login;
    private final BoardState board;

    EventsHandler(PageLogin login, BoardState board) {
        this.login = login;
        this.board = board;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            if (!EVENTS_PATH.equals(exchange.getRequestURI().getPath())) {
                HttpAnswers.sendStatus(exchange, 404);
            } else if (!"GET".equals(exchange.getRequestMethod())) {
                HttpAnswers.sendMethodNotAllowed(exchange, "GET");
            } else if (!login.isLoggedIn(exchange.getRequestHeaders())) {
                HttpAnswers.sendStatus(exchange, 401);
            } else {
                streamChanges(exchange);
            }
        }
    }

    /** The version is read before the headers go out, so that no change after the open is lost. */
    private void streamChanges(HttpExchange exchange) throws IOException {
        long knownVersion = board.snapshot().version();
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
        SecurityHeaders.forbidCaching(exchange.getResponseHeaders());
        exchange.sendResponseHeaders(200, 0);
        try (OutputStream output = exchange.getResponseBody()) {
            sendEventsUntilTheEnd(output, knownVersion);
        } catch (IOException exception) {
            // The browser left: the stream ends.
        }
    }

    private void sendEventsUntilTheEnd(OutputStream output, long knownVersion) throws IOException {
        try {
            while (true) {
                long currentVersion = board.awaitChange(knownVersion, KEEP_ALIVE_INTERVAL);
                output.write(currentVersion == knownVersion ? KEEP_ALIVE : describeChange(currentVersion));
                output.flush();
                knownVersion = currentVersion;
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private static byte[] describeChange(long version) {
        return ("event: board\ndata: " + version + "\n\n").getBytes(StandardCharsets.UTF_8);
    }
}
