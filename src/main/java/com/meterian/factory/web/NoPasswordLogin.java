package com.meterian.factory.web;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;

/** The login of a page without a password: every request is logged in. The server still checks the origin. */
public final class NoPasswordLogin implements PageLogin {

    @Override
    public boolean isLoggedIn(Headers requestHeaders) {
        return true;
    }

    /** There is no form, so the login path goes to the board. */
    @Override
    public void serveLogin(HttpExchange exchange) throws IOException {
        try (exchange) {
            HttpAnswers.sendRedirect(exchange, "/");
        }
    }
}
