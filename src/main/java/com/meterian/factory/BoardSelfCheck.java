package com.meterian.factory;

import com.meterian.factory.agent.UnavailableLiveSessions;
import com.meterian.factory.configuration.ConfigurationLoader;
import com.meterian.factory.configuration.FactoryConfiguration;
import com.meterian.factory.definition.BuiltInDefinition;
import com.meterian.factory.definition.DefinitionLookup;
import com.meterian.factory.definition.FactoryDefinition;
import com.meterian.factory.github.Issue;
import com.meterian.factory.state.IssueRecord;
import com.meterian.factory.web.LabelWriter;
import com.meterian.factory.web.WebServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** The step board of the command self-check: the worker serves the board with one card, on a free loopback port. */
final class BoardSelfCheck {

    private static final String LOOPBACK_ADDRESS = "127.0.0.1";
    private static final String CARD_TITLE = "The card of the self-check";
    private static final List<String> RESOURCE_PATHS = List.of("/page.css", "/page.js", "/live.js");
    private static final int HTTP_OK = 200;

    private BoardSelfCheck() {
    }

    static void verifyBoard(Path folder) throws Exception {
        FactoryConfiguration configuration = ConfigurationLoader.load(writeWorkerFile(folder, findFreePort()));
        Main.SharedState shared = Main.SharedState.create(configuration);
        FactoryDefinition definition = BuiltInDefinition.load();
        shared.board().recordIssue(new Issue(1, CARD_TITLE, "", Set.of("factory")),
                IssueRecord.queued(1, definition.workflow().start()));
        WebServer server = Main.createWebServer(configuration, shared, new UnavailableLiveSessions(), false,
                refuseLabels(), DefinitionLookup.builtInOnly(definition), Optional::empty, () -> 0, () -> 0, () -> { },
                folder.resolve("claude-home")).orElseThrow();
        server.start();
        try {
            verifyPages(URI.create("http://" + LOOPBACK_ADDRESS + ":" + server.port()));
        } finally {
            server.stop();
        }
    }

    /** The web section has no password, so the page needs no login. */
    private static Path writeWorkerFile(Path folder, int port) throws IOException {
        return Files.writeString(folder.resolve("worker.yaml"), """
                repository: %s
                work-directory: %s
                gatekeeper-directory: %s
                web:
                  address: %s
                  port: %d
                """.formatted(SelfCheckSteps.CHECK_REPOSITORY, folder.resolve("work"),
                folder.resolve("gatekeeper.git"), LOOPBACK_ADDRESS, port));
    }

    private static int findFreePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        }
    }

    private static LabelWriter refuseLabels() {
        return (issueNumber, label) -> {
            throw new IllegalStateException("The self-check adds no label.");
        };
    }

    private static void verifyPages(URI base) throws IOException, InterruptedException {
        HttpClient client = Main.createHttpClient();
        String page = fetch(client, base.resolve("/"));
        if (!page.contains(CARD_TITLE)) {
            throw new IllegalStateException("The board does not show the card.");
        }
        for (String path : RESOURCE_PATHS) {
            fetch(client, base.resolve(path));
        }
    }

    private static String fetch(HttpClient client, URI address) throws IOException, InterruptedException {
        HttpResponse<String> response = client.send(HttpRequest.newBuilder(address).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != HTTP_OK) {
            throw new IllegalStateException(address.getPath() + " gave the status " + response.statusCode() + ".");
        }
        return response.body();
    }
}
