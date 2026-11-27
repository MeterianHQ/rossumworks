package com.meterian.factory.testing;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class FakeGitHubServerTest {

    private static final int ISSUE = 7;

    private final FakeGitHubClient fakeGitHub = new FakeGitHubClient();
    private final FakeGitHubServer server = new FakeGitHubServer(fakeGitHub, "MeterianHQ", "sample");
    private final HttpClient httpClient = HttpClient.newHttpClient();

    @AfterEach
    void stopTheServer() {
        server.close();
    }

    @Test
    void countsARequestToAnUnknownRouteThatGetsANotFoundAnswer() throws Exception {
        int status = sendGet("/repos/MeterianHQ/sample/issues/7/unknown");

        assertThat(status).isEqualTo(404);
        assertThat(server.requestCounts()).containsEntry("GET /repos/{o}/{r}/issues/{n}/unknown", 1);
    }

    @Test
    void countsAReadOfTheIssueEvents() throws Exception {
        fakeGitHub.addIssue(ISSUE, "Change the greeting", "The greeting must change.");

        int status = sendGet("/repos/MeterianHQ/sample/issues/7/events");

        assertThat(status).isEqualTo(200);
        assertThat(server.requestCounts()).containsEntry("GET /repos/{o}/{r}/issues/{n}/events", 1);
    }

    private int sendGet(String path) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(server.apiBase().resolve(URI.create(path))).GET().build();
        return httpClient.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
    }
}
