package com.meterian.factory.web;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;

/**
 * Serves the page at / and its board at /board. Without the login, / goes to /login and /board gives 401.
 * page.js reads /board with fetch, and a redirect would put the login page into the board.
 */
final class PageHandler implements HttpHandler {

    private final PageLogin login;
    private final PageModelSource models;
    private final BoardPage page;

    PageHandler(PageLogin login, PageModelSource models, BoardPage page) {
        this.login = login;
        this.models = models;
        this.page = page;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            switch (exchange.getRequestURI().getPath()) {
                case "/" -> servePage(exchange);
                case "/board" -> serveBoard(exchange);
                default -> HttpAnswers.sendStatus(exchange, 404);
            }
        }
    }

    private void servePage(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            HttpAnswers.sendMethodNotAllowed(exchange, "GET");
        } else if (!login.isLoggedIn(exchange.getRequestHeaders())) {
            HttpAnswers.sendRedirect(exchange, "/login");
        } else {
            HttpAnswers.sendHtml(exchange, 200, page.renderPage(models.read()));
        }
    }

    private void serveBoard(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            HttpAnswers.sendMethodNotAllowed(exchange, "GET");
        } else if (!login.isLoggedIn(exchange.getRequestHeaders())) {
            HttpAnswers.sendStatus(exchange, 401);
        } else {
            HttpAnswers.sendHtml(exchange, 200, page.renderBoard(models.read()));
        }
    }
}
