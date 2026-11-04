package com.meterian.factory.web;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;

/** Serves POST /pick-up/pause and POST /pick-up/resume, the forms of the button in the header. */
final class PickUpHandler implements HttpHandler {

    private static final System.Logger LOGGER = System.getLogger(PickUpHandler.class.getName());
    private static final String PAUSE_PATH = "/pick-up/pause";
    private static final String RESUME_PATH = "/pick-up/resume";
    private static final String FAILED_MESSAGE = "The action failed. See the log of the worker.";

    private final PageLogin login;
    private final PageActions actions;

    PickUpHandler(PageLogin login, PageActions actions) {
        this.login = login;
        this.actions = actions;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            String path = exchange.getRequestURI().getPath();
            if (!PAUSE_PATH.equals(path) && !RESUME_PATH.equals(path)) {
                HttpAnswers.sendStatus(exchange, 404);
            } else if (!"POST".equals(exchange.getRequestMethod())) {
                HttpAnswers.sendMethodNotAllowed(exchange, "POST");
            } else if (!login.isLoggedIn(exchange.getRequestHeaders())) {
                HttpAnswers.sendStatus(exchange, 401);
            } else {
                changePickUp(exchange, path);
            }
        }
    }

    /** An unexpected failure goes to the log. The page shows only a fixed message. */
    private void changePickUp(HttpExchange exchange, String path) throws IOException {
        try {
            if (PAUSE_PATH.equals(path)) {
                actions.pausePickUp();
            } else {
                actions.resumePickUp();
            }
            HttpAnswers.sendRedirect(exchange, "/");
        } catch (RuntimeException exception) {
            LOGGER.log(System.Logger.Level.ERROR, "The change of the pick up failed.", exception);
            HttpAnswers.sendMessagePage(exchange, 500, FAILED_MESSAGE);
        }
    }
}
