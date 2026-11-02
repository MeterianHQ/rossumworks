package com.meterian.factory.web;

import com.sun.net.httpserver.Filter;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;

/**
 * Refuses a POST that another site sends from the browser of the owner, also a POST to /login. This adds a
 * check to the cookie with SameSite=Strict. A POST without the header Origin passes.
 */
final class SameOriginCheck extends Filter {

    @Override
    public void doFilter(HttpExchange exchange, Chain chain) throws IOException {
        if (isPostFromAnotherOrigin(exchange)) {
            try (exchange) {
                HttpAnswers.sendStatus(exchange, 403);
            }
            return;
        }
        chain.doFilter(exchange);
    }

    @Override
    public String description() {
        return "Refuses a POST from another origin.";
    }

    private static boolean isPostFromAnotherOrigin(HttpExchange exchange) {
        if (!"POST".equals(exchange.getRequestMethod())) {
            return false;
        }
        String origin = exchange.getRequestHeaders().getFirst("Origin");
        if (origin == null) {
            return false;
        }
        String host = exchange.getRequestHeaders().getFirst("Host");
        return host == null || !origin.equalsIgnoreCase("http://" + host);
    }
}
