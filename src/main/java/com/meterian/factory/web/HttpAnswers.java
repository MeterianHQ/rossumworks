package com.meterian.factory.web;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/** Writes the answers of the handlers. The caller closes the exchange. */
final class HttpAnswers {

    private HttpAnswers() {
    }

    static void sendStatus(HttpExchange exchange, int statusCode) throws IOException {
        SecurityHeaders.forbidCaching(exchange.getResponseHeaders());
        exchange.sendResponseHeaders(statusCode, -1);
    }

    static void sendMethodNotAllowed(HttpExchange exchange, String allowedMethod) throws IOException {
        exchange.getResponseHeaders().set("Allow", allowedMethod);
        sendStatus(exchange, 405);
    }

    static void sendRedirect(HttpExchange exchange, String location) throws IOException {
        exchange.getResponseHeaders().set("Location", location);
        sendStatus(exchange, 303);
    }

    static void sendHtml(HttpExchange exchange, int statusCode, String html) throws IOException {
        byte[] body = html.getBytes(StandardCharsets.UTF_8);
        Headers responseHeaders = exchange.getResponseHeaders();
        responseHeaders.set("Content-Type", "text/html; charset=utf-8");
        SecurityHeaders.forbidCaching(responseHeaders);
        exchange.sendResponseHeaders(statusCode, body.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(body);
        }
    }

    /** A short page with the message and a link back to the board, in the same centered box as the login. */
    static void sendMessagePage(HttpExchange exchange, int statusCode, String message) throws IOException {
        sendHtml(exchange, statusCode, "<!DOCTYPE html>"
                + "<html lang=\"en\"><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
                + "<meta name=\"color-scheme\" content=\"dark\">"
                + "<title>Factory</title><link rel=\"stylesheet\" href=\"/page.css\"></head>"
                + "<body class=\"login-page\"><main class=\"login\"><div class=\"login-box\">"
                + "<p class=\"message\">" + Html.escape(message) + "</p>"
                + "<p><a href=\"/\">Back to the board</a></p></div></main></body></html>");
    }
}
