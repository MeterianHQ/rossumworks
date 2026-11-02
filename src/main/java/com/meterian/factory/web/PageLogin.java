package com.meterian.factory.web;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;

/** The login of the page. The server knows only this interface, so another method can replace the password. */
public interface PageLogin {

    /** Tells if the request comes from a person who is logged in. */
    boolean isLoggedIn(Headers requestHeaders);

    /** Answers GET /login with the form and POST /login with the check. Another method gives 405. */
    void serveLogin(HttpExchange exchange) throws IOException;
}
