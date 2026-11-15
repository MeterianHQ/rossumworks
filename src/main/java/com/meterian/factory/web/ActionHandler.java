package com.meterian.factory.web;

import com.meterian.factory.github.GitHubException;
import com.meterian.factory.web.ActionFormReader.BodyTooLargeException;
import com.meterian.factory.web.PageActions.ActionResult;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Serves POST /issues/n/action, the forms of the buttons. The form has one field, the fingerprint of the card.
 * After the action, the browser goes back to the page. After an Enter in the browser, it goes to the wait page
 * of the live conversation.
 */
final class ActionHandler implements HttpHandler {

    private static final System.Logger LOGGER = System.getLogger(ActionHandler.class.getName());
    private static final Pattern ACTION_PATH = Pattern.compile("/issues/([^/]+)/([^/]+)");
    private static final Pattern ISSUE_NUMBER = Pattern.compile("[1-9][0-9]{0,8}");
    private static final String NOT_POSSIBLE_MESSAGE = "This action is not possible now.";
    private static final String UNKNOWN_ISSUE_MESSAGE = "This issue is no longer on the board.";
    private static final String REFUSED_LABEL_MESSAGE = "GitHub refused the label. ";
    private static final String FAILED_ACTION_MESSAGE = "The action failed. See the log of the worker.";

    /** The issue and the action of one path. */
    private record ActionTarget(int issueNumber, PageAction action) {
    }

    private final PageLogin login;
    private final PageActions actions;
    private final ActionFormReader formReader = new ActionFormReader();

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
        Optional<String> state;
        try {
            state = formReader.readState(exchange.getRequestBody());
        } catch (BodyTooLargeException exception) {
            HttpAnswers.sendMessagePage(exchange, 413, exception.getMessage());
            return;
        }
        performAction(exchange, target.get(), state);
    }

    /** An unexpected failure goes to the log. The page shows only a fixed message. */
    private void performAction(HttpExchange exchange, ActionTarget target, Optional<String> state)
            throws IOException {
        try {
            ActionResult result = actions.perform(target.issueNumber(), target.action(), state,
                    ViewerLocation.isThisMachine(exchange));
            sendResult(exchange, target, result);
        } catch (GitHubException exception) {
            HttpAnswers.sendMessagePage(exchange, 502, REFUSED_LABEL_MESSAGE + exception.getMessage());
        } catch (RuntimeException exception) {
            LOGGER.log(System.Logger.Level.ERROR, "The action " + target.action().pathName() + " on the issue #"
                    + target.issueNumber() + " failed.", exception);
            HttpAnswers.sendMessagePage(exchange, 500, FAILED_ACTION_MESSAGE);
        }
    }

    private static void sendResult(HttpExchange exchange, ActionTarget target, ActionResult result)
            throws IOException {
        switch (result.outcome()) {
            case DONE -> HttpAnswers.sendRedirect(exchange, findPageAfter(target));
            case NOT_POSSIBLE -> HttpAnswers.sendMessagePage(exchange, 409, NOT_POSSIBLE_MESSAGE);
            case UNKNOWN_ISSUE -> HttpAnswers.sendMessagePage(exchange, 404, UNKNOWN_ISSUE_MESSAGE);
            case FAILED -> HttpAnswers.sendMessagePage(exchange, 500, result.failureMessage().orElseThrow());
        }
    }

    private static String findPageAfter(ActionTarget target) {
        return target.action() == PageAction.ENTER_BROWSER ? "/live/" + target.issueNumber() : "/";
    }
}
