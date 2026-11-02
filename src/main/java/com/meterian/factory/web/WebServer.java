package com.meterian.factory.web;

import com.meterian.factory.board.BoardState;
import com.meterian.factory.configuration.WebSettings;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The web server of the page. Each request runs in a virtual thread, so an open event stream costs little.
 * Every answer has the security headers, and every POST from another origin gets 403 before the login check.
 */
public final class WebServer {

    private final WebSettings settings;
    private final SecurityHeaders securityHeaders = new SecurityHeaders();
    private final SameOriginCheck sameOriginCheck = new SameOriginCheck();
    private final HttpHandler pageHandler;
    private final HttpHandler loginHandler;
    private final HttpHandler eventsHandler;
    private final HttpHandler actionHandler;
    private final HttpHandler liveHandler;
    private HttpServer server;
    private ExecutorService executor;

    public WebServer(WebSettings settings, PageLogin login, PageModelSource models, BoardPage page,
            BoardState board, PageActions actions) {
        this.settings = settings;
        this.pageHandler = new PageHandler(login, models, page);
        this.loginHandler = new LoginHandler(login);
        this.eventsHandler = new EventsHandler(login, board);
        this.actionHandler = new ActionHandler(login, actions);
        this.liveHandler = new LiveHandler(login, board);
    }

    public synchronized void start() throws IOException {
        if (server != null) {
            throw new IllegalStateException("The web server runs already.");
        }
        server = HttpServer.create(new InetSocketAddress(settings.address(), settings.port()), 0);
        executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        addContext("/", pageHandler);
        addContext("/login", loginHandler);
        addContext("/events", eventsHandler);
        addContext("/issues/", actionHandler);
        addContext("/live/", liveHandler);
        addContext("/page.css", new ResourceHandler("page.css", "text/css; charset=utf-8"));
        addContext("/page.js", new ResourceHandler("page.js", "text/javascript; charset=utf-8"));
        addContext("/live.js", new ResourceHandler("live.js", "text/javascript; charset=utf-8"));
        server.start();
    }

    public synchronized int port() {
        if (server == null) {
            throw new IllegalStateException("The web server does not run.");
        }
        return server.getAddress().getPort();
    }

    /** Stops the server and interrupts the open event streams. A second call does nothing. */
    public synchronized void stop() {
        if (server == null) {
            return;
        }
        server.stop(0);
        executor.shutdownNow();
        server = null;
        executor = null;
    }

    private void addContext(String path, HttpHandler handler) {
        HttpContext context = server.createContext(path, handler);
        context.getFilters().add(securityHeaders);
        context.getFilters().add(sameOriginCheck);
    }
}
