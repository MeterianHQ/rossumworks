package com.meterian.factory.web;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;

/** Serves one file of the folder web of the resources. The file needs no login, because it holds no data. */
final class ResourceHandler implements HttpHandler {

    private final String path;
    private final String contentType;
    private final byte[] content;

    /**
     * @param fileName the name of the file in the folder web of the resources, for example page.css
     * @param contentType the value of the header Content-Type
     */
    ResourceHandler(String fileName, String contentType) {
        this.path = "/" + fileName;
        this.contentType = contentType;
        this.content = readResource("/web/" + fileName);
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            if (!path.equals(exchange.getRequestURI().getPath())) {
                HttpAnswers.sendStatus(exchange, 404);
            } else if (!"GET".equals(exchange.getRequestMethod())) {
                HttpAnswers.sendMethodNotAllowed(exchange, "GET");
            } else {
                sendContent(exchange);
            }
        }
    }

    private void sendContent(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(200, content.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(content);
        }
    }

    private static byte[] readResource(String resourceName) {
        try (InputStream input = ResourceHandler.class.getResourceAsStream(resourceName)) {
            if (input == null) {
                throw new IllegalStateException("The resource " + resourceName + " is missing.");
            }
            return input.readAllBytes();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}
