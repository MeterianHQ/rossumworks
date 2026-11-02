package com.meterian.factory.web;

import com.meterian.factory.github.GitHubException;
import com.meterian.factory.web.PageActions.ActionOutcome;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Serves POST /issues/n/action, the forms of the buttons. The body of the form is empty and is never read.
 * After the action, the browser goes back to the page.
 */
final class ActionHandler implements HttpHandler {

    private static final Pattern ACTION_PATH = Pattern.compile("/issues/([^/]+)/([^/]+)");
    private static final Pattern ISSUE_NUMBER = Pattern.compile("[1-9][0-9]{0,8}");
    private static final String NOT_POSSIBLE_MESSAGE = "This action is not possible now.";
    private static final String REFUSED_LABEL_MESSAGE = "GitHub refused the label. ";

    /** The issue and the action of one path. */
    private record ActionTarget(int issueNumber, PageAction action) {
    }

    private final PageLogin login;
    private final PageActions actions;

    ActionHandler(PageLogin login, PageActions actions) {
        this.login = login;
        this.actions = actions;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            Matcher path = ACTION_PATH.matcher(exchange.getRequestURI().getPath());
            if (!path.matches()) {
                HttpAnswers.sendStatus(exchange, 404);
            } else if (!"POST".equals(exchange.getRequestMethod())) {
                HttpAnswers.sendMethodNotAllowed(exchange, "POST");
            } else if (!login.isLoggedIn(exchange.getRequestHeaders())) {
                HttpAnswers.sendStatus(exchange, 401);
            } else {
                serveAction(exchange, findTarget(path.group(1), path.group(2)));
            }
        }
    }

    private static Optional<ActionTarget> findTarget(String issueText, String actionName) {
        if (!ISSUE_NUMBER.matcher(issueText).matches()) {
            return Optional.empty();
        }
        return PageAction.findByPathName(actionName)
                .map(action -> new ActionTarget(Integer.parseInt(issueText), action));
    }

    private void serveAction(HttpExchange exchange, Optional<ActionTarget> target) throws IOException {
        if (target.isEmpty()) {
            HttpAnswers.sendStatus(exchange, 404);
            return;
        }
        try {
            sendOutcome(exchange, actions.perform(target.get().issueNumber(), target.get().action()));
        } catch (GitHubException exception) {
            HttpAnswers.sendMessagePage(exchange, 502, REFUSED_LABEL_MESSAGE + exception.getMessage());
        }
    }

    private static void sendOutcome(HttpExchange exchange, ActionOutcome outcome) throws IOException {
        switch (outcome) {
            case DONE -> HttpAnswers.sendRedirect(exchange, "/");
            case NOT_POSSIBLE -> HttpAnswers.sendMessagePage(exchange, 409, NOT_POSSIBLE_MESSAGE);
            case UNKNOWN_ISSUE -> HttpAnswers.sendStatus(exchange, 404);
        }
    }
}
