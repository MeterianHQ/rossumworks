package com.meterian.factory.web;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;

/** Serves POST /factory/stop and POST /factory/start, the forms of the button in the top right corner. */
final class FactorySwitchHandler implements HttpHandler {

    private static final System.Logger LOGGER = System.getLogger(FactorySwitchHandler.class.getName());
    private static final String STOP_PATH = "/factory/stop";
    private static final String START_PATH = "/factory/start";
    private static final String FAILED_MESSAGE = "The action failed. See the log of the worker.";

    private final PageLogin login;
    private final PageActions actions;

    FactorySwitchHandler(PageLogin login, PageActions actions) {
        this.login = login;
        this.actions = actions;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            String path = exchange.getRequestURI().getPath();
            if (!STOP_PATH.equals(path) && !START_PATH.equals(path)) {
                HttpAnswers.sendStatus(exchange, 404);
            } else if (!"POST".equals(exchange.getRequestMethod())) {
                HttpAnswers.sendMethodNotAllowed(exchange, "POST");
            } else if (!login.isLoggedIn(exchange.getRequestHeaders())) {
                HttpAnswers.sendStatus(exchange, 401);
            } else {
                changeFactoryState(exchange, path);
            }
        }
    }

    /** An unexpected failure goes to the log. The page shows only a fixed message. */
    private void changeFactoryState(HttpExchange exchange, String path) throws IOException {
        try {
            if (STOP_PATH.equals(path)) {
                actions.stopFactory();
            } else {
                actions.startFactory();
            }
            HttpAnswers.sendRedirect(exchange, "/");
        } catch (RuntimeException exception) {
            LOGGER.log(System.Logger.Level.ERROR, "The change of the factory state failed.", exception);
            HttpAnswers.sendMessagePage(exchange, 500, FAILED_MESSAGE);
        }
    }
}
