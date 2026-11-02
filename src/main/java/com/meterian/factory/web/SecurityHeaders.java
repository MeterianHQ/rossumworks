package com.meterian.factory.web;

import com.sun.net.httpserver.Filter;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;

/** Sets the security headers on every answer, before the handler writes it. */
final class SecurityHeaders extends Filter {

    static final String CONTENT_SECURITY_POLICY = "default-src 'none'; script-src 'self'; style-src 'self'; "
            + "connect-src 'self'; form-action 'self'; frame-ancestors 'none'; base-uri 'none'";

    @Override
    public void doFilter(HttpExchange exchange, Chain chain) throws IOException {
        Headers responseHeaders = exchange.getResponseHeaders();
        responseHeaders.set("Content-Security-Policy", CONTENT_SECURITY_POLICY);
        responseHeaders.set("X-Content-Type-Options", "nosniff");
        // Not "no-referrer": with it, a browser sends "Origin: null" and SameOriginCheck refuses every form POST.
        responseHeaders.set("Referrer-Policy", "same-origin");
        chain.doFilter(exchange);
    }

    @Override
    public String description() {
        return "Sets the security headers of the page.";
    }

    /** The HTML and the events show the state of the board, so the browser must not keep a copy. */
    static void forbidCaching(Headers responseHeaders) {
        responseHeaders.set("Cache-Control", "no-store");
    }
}
