package com.meterian.factory.web;

import com.sun.net.httpserver.HttpExchange;

/**
 * Tells where the browser of a request runs. Only the address of the connection counts: a header such as
 * X-Forwarded-For comes from the client, so this class never reads one.
 */
public final class ViewerLocation {

    private ViewerLocation() {
    }

    /** True when the connection comes from a loopback address, that is from the machine of the worker. */
    public static boolean isThisMachine(HttpExchange exchange) {
        return exchange.getRemoteAddress().getAddress().isLoopbackAddress();
    }
}
