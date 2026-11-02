package com.meterian.factory.web;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;

/** Gives the path /login to the login. The login writes its own answer. */
final class LoginHandler implements HttpHandler {

    private static final String LOGIN_PATH = "/login";

    private final PageLogin login;

    LoginHandler(PageLogin login) {
        this.login = login;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (LOGIN_PATH.equals(exchange.getRequestURI().getPath())) {
            login.serveLogin(exchange);
            return;
        }
        try (exchange) {
            HttpAnswers.sendStatus(exchange, 404);
        }
    }
}
